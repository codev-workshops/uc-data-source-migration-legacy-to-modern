package com.workshop.loanservice.golden;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Replays every golden file in {@code src/test/resources/golden/} (captured from the
 * legacy-backed app in Session 1) against the modern-backed API.
 *
 * <p>Comparison is JSON-aware (Jackson trees, numbers as BigDecimal, key order irrelevant,
 * array order significant). Only the fields listed in {@code GOLDEN_DIFFERENCES.md} are
 * normalized before comparison; everything else must match exactly.
 *
 * <p>MockMvc is built from the shared {@code @SpringBootTest} context rather than via
 * {@code @AutoConfigureMockMvc}: a second cached context would re-run the schema scripts
 * against the same {@code DB_CLOSE_DELAY=-1} in-memory H2 and fail with "table already exists".
 */
@SpringBootTest
class GoldenBaselineApiTest {

    static final Path GOLDEN = Path.of("src/test/resources/golden");
    static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    /** Date fields: legacy MM/dd/yyyy and ISO yyyy-MM-dd are equivalent presentations. */
    static final Set<String> DATE_FIELDS = Set.of("originationDate", "paymentDate");
    /** Label fields: title-case legacy label and UPPERCASE modern token are equivalent. */
    static final Set<String> LABEL_FIELDS = Set.of("status", "propertyType", "type");
    static final Map<String, String> LABEL_TO_TOKEN = Map.ofEntries(
            Map.entry("Active", "ACTIVE"), Map.entry("Closed", "CLOSED"), Map.entry("Default", "DEFAULT"),
            Map.entry("Forbearance", "FORBEARANCE"),
            Map.entry("Single Family Residence", "SINGLE_FAMILY"), Map.entry("Condominium", "CONDOMINIUM"),
            Map.entry("Multi-Family Residence", "MULTI_FAMILY"), Map.entry("Townhouse", "TOWNHOUSE"),
            Map.entry("Regular", "REGULAR"), Map.entry("Extra", "EXTRA"), Map.entry("Partial", "PARTIAL"),
            Map.entry("Prepayment", "PREPAYMENT"),
            Map.entry("Posted", "POSTED"), Map.entry("Reversed", "REVERSED"), Map.entry("Non-Sufficient Funds", "NSF"),
            Map.entry("Pending", "PENDING"));
    static final DateTimeFormatter LEGACY_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    MockMvc mockMvc;

    @Autowired
    void setUp(WebApplicationContext context) {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    static Stream<Arguments> goldenFiles() throws IOException {
        try (Stream<Path> files = Files.walk(GOLDEN)) {
            return files.filter(p -> p.toString().endsWith(".json"))
                    .map(p -> Arguments.of(endpointFor(GOLDEN.relativize(p)), p))
                    .sorted((a, b) -> a.get()[0].toString().compareTo(b.get()[0].toString()))
                    .toList().stream();
        }
    }

    static String endpointFor(Path rel) {
        String name = rel.getFileName().toString().replace(".json", "");
        if (rel.getNameCount() == 1) return "/api/" + name;
        return switch (rel.getName(0).toString()) {
            case "loans" -> "/api/loans/" + name;
            case "payments" -> "/api/loans/" + name + "/payments";
            case "borrowers" -> "/api/borrowers/" + name;
            default -> throw new IllegalArgumentException("Unexpected golden file " + rel);
        };
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenFiles")
    void modernResponseMatchesGolden(String endpoint, Path goldenFile) throws Exception {
        JsonNode expected = MAPPER.readTree(Files.readString(goldenFile, StandardCharsets.UTF_8));
        JsonNode actual = MAPPER.readTree(getJson(endpoint));

        assertEquals(normalize(expected.deepCopy()), normalize(actual.deepCopy()),
                () -> "Normalized mismatch for " + endpoint + "\nexpected: " + expected + "\nactual:   " + actual);
    }

    /** Session 3 aimed for byte-identical output; this pins that no normalizer is currently exercised. */
    @Test
    void goldenFilesRequireNoNormalizationToday() throws Exception {
        for (Arguments args : goldenFiles().toList()) {
            String endpoint = (String) args.get()[0];
            JsonNode expected = MAPPER.readTree(Files.readString((Path) args.get()[1], StandardCharsets.UTF_8));
            assertEquals(expected, MAPPER.readTree(getJson(endpoint)), "Raw JSON differs for " + endpoint);
        }
    }

    @Test
    void goldenBaselineCoversEveryEntity() throws Exception {
        assertEquals(17, goldenFiles().count(), "2 list files + 5 loans + 5 payment histories + 5 borrowers");
        assertEquals(5, MAPPER.readTree(getJson("/api/loans")).size());
        assertEquals(5, MAPPER.readTree(getJson("/api/borrowers")).size());
    }

    @Test
    void unknownIdsReturn404ProblemDetail() throws Exception {
        for (String url : List.of("/api/loans/LN-0000-00000", "/api/loans/LN-0000-00000/payments", "/api/borrowers/B-00000")) {
            MvcResult result = mockMvc.perform(get(url)).andExpect(status().isNotFound()).andReturn();
            JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
            assertEquals(404, body.get("status").asInt(), url);
        }
    }

    private String getJson(String endpoint) throws Exception {
        return mockMvc.perform(get(endpoint)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    static JsonNode normalize(JsonNode node) {
        if (node instanceof ArrayNode array) {
            array.forEach(GoldenBaselineApiTest::normalize);
        } else if (node instanceof ObjectNode object) {
            object.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                if (v.isContainerNode()) {
                    normalize(v);
                } else if (v.isTextual() && DATE_FIELDS.contains(e.getKey())) {
                    object.put(e.getKey(), canonicalDate(v.asText()));
                } else if (v.isTextual() && LABEL_FIELDS.contains(e.getKey())) {
                    object.put(e.getKey(), canonicalLabel(v.asText()));
                }
            });
        }
        return node;
    }

    static String canonicalDate(String text) {
        try {
            return LocalDate.parse(text, LEGACY_DATE).toString();
        } catch (DateTimeParseException legacyFormat) {
            return LocalDate.parse(text).toString();
        }
    }

    static String canonicalLabel(String text) {
        return LABEL_TO_TOKEN.getOrDefault(text, text.toUpperCase(Locale.ROOT));
    }
}
