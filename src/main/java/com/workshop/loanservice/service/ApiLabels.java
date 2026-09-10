package com.workshop.loanservice.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Presentation formatting for the public API contract.
 *
 * <p>Canonical representation decision (Session 3): the database stores the modern
 * UPPERCASE tokens defined in {@code data/mappings/column_mappings.md}
 * ({@code ACTIVE}, {@code SINGLE_FAMILY}, {@code POSTED}, ...), typed dates and
 * scaled decimals. The API keeps emitting exactly what the legacy service emitted so
 * the DTO contract and the golden files in {@code src/test/resources/golden/} stay
 * byte-identical: title-case labels ({@code Active}, {@code Single Family Residence}),
 * {@code MM/dd/yyyy} date strings and numbers without the fixed DB scale
 * ({@code 285000} rather than {@code 285000.00}). Unknown tokens pass through unchanged;
 * a null token renders as {@code Unknown}, matching the legacy behaviour.
 */
final class ApiLabels {

    private static final DateTimeFormatter LEGACY_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    private static final Map<String, String> LOAN_STATUS = Map.of(
            "ACTIVE", "Active", "CLOSED", "Closed", "DEFAULT", "Default", "FORBEARANCE", "Forbearance");
    private static final Map<String, String> PROPERTY_TYPE = Map.of(
            "SINGLE_FAMILY", "Single Family Residence", "CONDOMINIUM", "Condominium",
            "MULTI_FAMILY", "Multi-Family Residence", "TOWNHOUSE", "Townhouse");
    private static final Map<String, String> PAYMENT_TYPE = Map.of(
            "REGULAR", "Regular", "EXTRA", "Extra", "PARTIAL", "Partial", "PREPAYMENT", "Prepayment");
    private static final Map<String, String> PAYMENT_STATUS = Map.of(
            "POSTED", "Posted", "REVERSED", "Reversed", "NSF", "Non-Sufficient Funds", "PENDING", "Pending");

    private ApiLabels() {}

    static String loanStatus(String token) { return label(LOAN_STATUS, token); }
    static String propertyType(String token) { return label(PROPERTY_TYPE, token); }
    static String paymentType(String token) { return label(PAYMENT_TYPE, token); }
    static String paymentStatus(String token) { return label(PAYMENT_STATUS, token); }

    static String date(LocalDate date) {
        return date == null ? null : LEGACY_DATE.format(date);
    }

    /** Null becomes zero and trailing zeros are dropped, as the legacy string parsing did. */
    static BigDecimal amount(BigDecimal value) {
        if (value == null) return BigDecimal.ZERO;
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static String label(Map<String, String> mapping, String token) {
        if (token == null) return "Unknown";
        return mapping.getOrDefault(token, token);
    }
}
