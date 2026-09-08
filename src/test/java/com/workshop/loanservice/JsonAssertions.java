package com.workshop.loanservice;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Semantic JSON comparison helpers for the characterization tests.
 *
 * Comparison is by property names, values and JSON types; it is independent of
 * whitespace, object property order and numeric scale (1487.02 equals 1487.020).
 */
final class JsonAssertions {

    private JsonAssertions() {
    }

    static void assertJsonEquals(JsonNode expected, JsonNode actual, String path) {
        if (expected.isNumber() || actual.isNumber()) {
            assertTrue(expected.isNumber() && actual.isNumber(),
                    path + ": expected JSON type " + expected.getNodeType() + " but was " + actual.getNodeType());
            assertEquals(0, expected.decimalValue().compareTo(actual.decimalValue()),
                    path + ": expected " + expected.decimalValue() + " but was " + actual.decimalValue());
            return;
        }
        assertEquals(expected.getNodeType(), actual.getNodeType(), path + ": JSON type mismatch");

        if (expected.isObject()) {
            Set<String> expectedFields = fieldNames(expected);
            Set<String> actualFields = fieldNames(actual);
            assertEquals(expectedFields, actualFields, path + ": property names mismatch");
            expectedFields.forEach(field ->
                    assertJsonEquals(expected.get(field), actual.get(field), path + "." + field));
            return;
        }
        if (expected.isArray()) {
            assertEquals(expected.size(), actual.size(), path + ": array size mismatch");
            for (int i = 0; i < expected.size(); i++) {
                assertJsonEquals(expected.get(i), actual.get(i), path + "[" + i + "]");
            }
            return;
        }
        assertEquals(expected, actual, path + ": value mismatch");
    }

    /**
     * Compares two arrays of objects without depending on element order, matching
     * elements on a stable key property.
     */
    static void assertArrayEqualsByKey(JsonNode expected, JsonNode actual, String keyField, String path) {
        assertTrue(expected.isArray() && actual.isArray(), path + ": both nodes must be arrays");
        assertEquals(expected.size(), actual.size(), path + ": array size mismatch");

        Map<String, JsonNode> actualByKey = new HashMap<>();
        actual.forEach(element -> {
            JsonNode key = element.get(keyField);
            assertTrue(key != null && key.isTextual(), path + ": element without textual '" + keyField + "'");
            actualByKey.put(key.asText(), element);
        });

        for (JsonNode expectedElement : expected) {
            String key = expectedElement.get(keyField).asText();
            JsonNode actualElement = actualByKey.get(key);
            if (actualElement == null) {
                fail(path + ": no element with " + keyField + "=" + key + " (present: " + actualByKey.keySet() + ")");
            }
            assertJsonEquals(expectedElement, actualElement, path + "[" + keyField + "=" + key + "]");
        }
    }

    static List<String> textValues(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(element -> values.add(element.get(field).asText()));
        return values;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
