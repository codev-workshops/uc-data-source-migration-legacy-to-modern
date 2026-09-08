package com.workshop.loanservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.workshop.loanservice.JsonAssertions.assertArrayEqualsByKey;
import static com.workshop.loanservice.JsonAssertions.assertJsonEquals;
import static com.workshop.loanservice.JsonAssertions.textValues;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Characterization tests for the public REST contract.
 *
 * Responses are compared semantically against baselines captured from the
 * pre-migration application (src/test/resources/golden). Array order is treated
 * as contractual only for payment history, which is payment-date descending.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:characterization;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class LoanApiCharacterizationTest {

    private static final List<String> LOAN_IDS = List.of(
            "LN-2019-00142", "LN-2020-00398", "LN-2018-00089", "LN-2021-00567", "LN-2017-00034");
    private static final DateTimeFormatter API_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void getAllLoansMatchesBaseline() throws Exception {
        JsonNode actual = getJson("/api/loans");
        JsonNode expected = golden("loans.json");

        assertTrue(actual.isArray(), "/api/loans must return an array");
        assertArrayEqualsByKey(expected, actual, "loanAccountNumber", "loans");
    }

    @ParameterizedTest
    @ValueSource(strings = {"LN-2019-00142", "LN-2020-00398", "LN-2018-00089", "LN-2021-00567", "LN-2017-00034"})
    void getLoanByIdMatchesBaseline(String loanId) throws Exception {
        JsonNode actual = getJson("/api/loans/" + loanId);
        JsonNode expected = golden("loan-" + loanId + ".json");

        assertJsonEquals(expected, actual, "loan[" + loanId + "]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"LN-2019-00142", "LN-2020-00398", "LN-2018-00089", "LN-2021-00567", "LN-2017-00034"})
    void getPaymentsByLoanMatchesBaselineInDateDescendingOrder(String loanId) throws Exception {
        JsonNode actual = getJson("/api/loans/" + loanId + "/payments");
        JsonNode expected = golden("payments-" + loanId + ".json");

        assertTrue(actual.isArray(), "payment history must be an array");
        assertJsonEquals(expected, actual, "payments[" + loanId + "]");
        assertPaymentDatesDescending(actual, loanId);
    }

    @Test
    void getAllBorrowersMatchesBaseline() throws Exception {
        JsonNode actual = getJson("/api/borrowers");
        JsonNode expected = golden("borrowers.json");

        assertTrue(actual.isArray(), "/api/borrowers must return an array");
        assertArrayEqualsByKey(expected, actual, "id", "borrowers");
    }

    @ParameterizedTest
    @ValueSource(strings = {"B-10001", "B-10002", "B-10003", "B-10004", "B-10005"})
    void getBorrowerByIdMatchesBaselineIncludingNestedLoans(String borrowerId) throws Exception {
        JsonNode actual = getJson("/api/borrowers/" + borrowerId);
        JsonNode expected = golden("borrower-" + borrowerId + ".json");

        assertArrayEqualsByKey(expected.get("loans"), actual.get("loans"), "loanAccountNumber",
                "borrower[" + borrowerId + "].loans");
        assertJsonEquals(withoutLoans(expected), withoutLoans(actual), "borrower[" + borrowerId + "]");
    }

    @Test
    void everyLoanExposesItsPaymentHistory() throws Exception {
        for (String loanId : LOAN_IDS) {
            JsonNode payments = getJson("/api/loans/" + loanId + "/payments");
            assertFalse(payments.isEmpty(), "expected payment history for " + loanId);
        }
    }

    private void assertPaymentDatesDescending(JsonNode payments, String loanId) {
        List<String> dates = textValues(payments, "paymentDate");
        for (int i = 1; i < dates.size(); i++) {
            LocalDate previous = LocalDate.parse(dates.get(i - 1), API_DATE);
            LocalDate current = LocalDate.parse(dates.get(i), API_DATE);
            assertFalse(current.isAfter(previous),
                    "payments for " + loanId + " must be payment-date descending but were " + dates);
        }
    }

    private JsonNode withoutLoans(JsonNode borrower) {
        ObjectNode copy = borrower.deepCopy();
        copy.remove("loans");
        return copy;
    }

    private JsonNode getJson(String path) throws Exception {
        String body = mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode golden(String fileName) throws IOException {
        return objectMapper.readTree(new ClassPathResource("golden/" + fileName).getInputStream());
    }
}
