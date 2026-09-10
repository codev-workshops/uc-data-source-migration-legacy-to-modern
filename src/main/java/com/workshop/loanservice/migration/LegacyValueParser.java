package com.workshop.loanservice.migration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Map;

/**
 * Converts legacy all-VARCHAR values into modern typed values per data/mappings/column_mappings.md.
 * Null/blank input yields null; malformed input throws {@link IllegalArgumentException} so callers
 * can quarantine the row.
 */
final class LegacyValueParser {

    private static final DateTimeFormatter LEGACY_DATE = DateTimeFormatter.ofPattern("MM/dd/uuuu")
            .withResolverStyle(ResolverStyle.STRICT);

    private static final Map<String, String> LOAN_STATUS = Map.of(
            "ACT", "ACTIVE", "CLO", "CLOSED", "DFT", "DEFAULT", "FRB", "FORBEARANCE");
    private static final Map<String, String> BORROWER_STATUS = Map.of("ACT", "ACTIVE", "INA", "INACTIVE");
    private static final Map<String, String> PROPERTY_TYPE = Map.of(
            "SFR", "SINGLE_FAMILY", "CND", "CONDOMINIUM", "MFR", "MULTI_FAMILY", "TWN", "TOWNHOUSE");
    private static final Map<String, String> PAYMENT_TYPE = Map.of(
            "REG", "REGULAR", "EXT", "EXTRA", "PRT", "PARTIAL", "PRE", "PREPAYMENT");
    private static final Map<String, String> PAYMENT_STATUS = Map.of(
            "PST", "POSTED", "REV", "REVERSED", "NSF", "NSF", "PND", "PENDING");

    private LegacyValueParser() {}

    static String text(String value) {
        return isBlank(value) ? null : value.trim();
    }

    static LocalDate date(String value) {
        if (isBlank(value)) return null;
        try {
            return LocalDate.parse(value.trim(), LEGACY_DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date '" + value + "'", e);
        }
    }

    static LocalDateTime timestamp(String value) {
        LocalDate d = date(value);
        return d == null ? null : d.atStartOfDay();
    }

    static BigDecimal amount(String value) {
        if (isBlank(value)) return null;
        try {
            return new BigDecimal(value.replace(",", "").trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid amount '" + value + "'", e);
        }
    }

    static Integer integer(String value) {
        if (isBlank(value)) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer '" + value + "'", e);
        }
    }

    static Boolean active(String statusCode) {
        if (isBlank(statusCode)) return null;
        return "ACT".equalsIgnoreCase(statusCode.trim());
    }

    static String loanStatus(String code) { return expand(LOAN_STATUS, code); }
    static String borrowerStatus(String code) { return expand(BORROWER_STATUS, code); }
    static String propertyType(String code) { return expand(PROPERTY_TYPE, code); }
    static String paymentType(String code) { return expand(PAYMENT_TYPE, code); }
    static String paymentStatus(String code) { return expand(PAYMENT_STATUS, code); }

    private static String expand(Map<String, String> mapping, String code) {
        if (isBlank(code)) return null;
        String key = code.trim().toUpperCase();
        return mapping.getOrDefault(key, key);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
