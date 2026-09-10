package com.workshop.loanservice.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Referential-integrity and domain checks on the migrated modern tables.
 * Allowed sets come from {@code data/mappings/column_mappings.md}.
 */
@SpringBootTest
class MigrationIntegrityTest {

    static final Set<String> BORROWER_STATUS = Set.of("ACTIVE", "INACTIVE");
    static final Set<String> LOAN_STATUS = Set.of("ACTIVE", "CLOSED", "DEFAULT", "FORBEARANCE");
    static final Set<String> PROPERTY_TYPE = Set.of("SINGLE_FAMILY", "CONDOMINIUM", "MULTI_FAMILY", "TOWNHOUSE");
    static final Set<String> PAYMENT_TYPE = Set.of("REGULAR", "EXTRA", "PARTIAL", "PREPAYMENT");
    static final Set<String> PAYMENT_STATUS = Set.of("POSTED", "REVERSED", "NSF", "PENDING");

    @Autowired JdbcTemplate jdbc;

    @Test
    void rowCountsMatchLegacySource() {
        assertEquals(5, count("borrowers"));
        assertEquals(5, count("loan_products"));
        assertEquals(5, count("loan_accounts"));
        assertEquals(10, count("payments"));
        assertEquals(count("CDW_BORR_MSTR"), count("borrowers"));
        assertEquals(count("CDW_LN_PROD"), count("loan_products"));
        assertEquals(count("CDW_LN_ACCT"), count("loan_accounts"));
        assertEquals(count("CDW_PMT_HIST"), count("payments"));
    }

    @Test
    void noOrphanedForeignKeys() {
        assertEquals(0, count("loan_accounts la LEFT JOIN borrowers b ON b.id = la.borrower_id WHERE b.id IS NULL"),
                "loan_accounts.borrower_id orphans");
        assertEquals(0, count("loan_accounts la LEFT JOIN loan_products p ON p.id = la.product_id WHERE p.id IS NULL"),
                "loan_accounts.product_id orphans");
        assertEquals(0, count("payments pm LEFT JOIN loan_accounts la ON la.id = pm.loan_account_id WHERE la.id IS NULL"),
                "payments.loan_account_id orphans");
    }

    @Test
    void everyLegacyKeyResolvesToTheSameModernRelationship() {
        assertEquals(0, count("CDW_LN_ACCT l JOIN loan_accounts la ON la.account_number = l.LN_ACCT_NBR "
                + "JOIN borrowers b ON b.id = la.borrower_id WHERE b.external_id <> l.BORR_ID"),
                "loan -> borrower FK differs from legacy BORR_ID");
        assertEquals(0, count("CDW_LN_ACCT l JOIN loan_accounts la ON la.account_number = l.LN_ACCT_NBR "
                + "JOIN loan_products p ON p.id = la.product_id WHERE p.code <> l.PROD_CD"),
                "loan -> product FK differs from legacy PROD_CD");
        assertEquals(0, count("CDW_PMT_HIST h JOIN payments pm ON pm.external_id = h.PMT_SEQ_NBR "
                + "JOIN loan_accounts la ON la.id = pm.loan_account_id WHERE la.account_number <> h.LN_ACCT_NBR"),
                "payment -> loan FK differs from legacy LN_ACCT_NBR");
    }

    @Test
    void migratedCodesAreWithinAllowedSets() {
        assertWithin("borrowers", "status", BORROWER_STATUS);
        assertWithin("loan_accounts", "status", LOAN_STATUS);
        assertWithin("loan_accounts", "property_type", PROPERTY_TYPE);
        assertWithin("payments", "type", PAYMENT_TYPE);
        assertWithin("payments", "status", PAYMENT_STATUS);
        assertEquals(0, count("loan_products WHERE is_active IS NULL"), "loan_products.is_active must be boolean");
    }

    @Test
    void requiredTypedColumnsAreNeverNull() {
        assertEquals(0, count("loan_accounts WHERE origination_date IS NULL OR original_amount IS NULL "
                + "OR current_balance IS NULL OR interest_rate IS NULL OR monthly_payment IS NULL"));
        assertEquals(0, count("payments WHERE payment_date IS NULL OR total_amount IS NULL"));
        assertEquals(0, count("borrowers WHERE external_id IS NULL OR first_name IS NULL OR last_name IS NULL"));
    }

    private void assertWithin(String table, String column, Set<String> allowed) {
        List<String> values = jdbc.queryForList("SELECT DISTINCT " + column + " FROM " + table, String.class);
        assertTrue(!values.isEmpty() && values.stream().allMatch(v -> v != null && allowed.contains(v)),
                () -> table + "." + column + " has values outside " + allowed + ": " + values);
    }

    private long count(String fromClause) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + fromClause, Long.class);
        return n == null ? -1 : n;
    }
}
