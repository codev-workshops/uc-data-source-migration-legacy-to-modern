package com.workshop.loanservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Reconciles the migrated modern data against the values of the legacy seed
 * (data-legacy.sql) after comma-stripping and type conversion.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:reconciliation;DB_CLOSE_DELAY=-1")
class DataReconciliationTest {

    /** loan account_number -> {original amount, current balance, interest rate, monthly payment} from the legacy seed. */
    private static final Map<String, BigDecimal[]> LEGACY_LOAN_AMOUNTS = new LinkedHashMap<>();

    /** payment external_id -> {loan account number, payment date, total amount} from the legacy seed. */
    private static final Map<String, Object[]> LEGACY_PAYMENTS = new LinkedHashMap<>();

    static {
        LEGACY_LOAN_AMOUNTS.put("LN-2019-00142", amounts("285000", "271432.56", "4.750", "1487.02"));
        LEGACY_LOAN_AMOUNTS.put("LN-2020-00398", amounts("420000", "312876.43", "3.125", "2924.18"));
        LEGACY_LOAN_AMOUNTS.put("LN-2018-00089", amounts("195000", "178234.12", "5.250", "1077.05"));
        LEGACY_LOAN_AMOUNTS.put("LN-2021-00567", amounts("525000", "498123.78", "3.875", "2468.35"));
        LEGACY_LOAN_AMOUNTS.put("LN-2017-00034", amounts("165000", "142567.90", "4.250", "811.61"));

        LEGACY_PAYMENTS.put("PMT-2025120001", payment("LN-2019-00142", "2025-12-15", "1487.02"));
        LEGACY_PAYMENTS.put("PMT-2025110001", payment("LN-2019-00142", "2025-11-15", "1487.02"));
        LEGACY_PAYMENTS.put("PMT-2025120002", payment("LN-2020-00398", "2025-12-01", "2924.18"));
        LEGACY_PAYMENTS.put("PMT-2025110002", payment("LN-2020-00398", "2025-11-01", "2924.18"));
        LEGACY_PAYMENTS.put("PMT-2025120003", payment("LN-2018-00089", "2025-12-01", "1077.05"));
        LEGACY_PAYMENTS.put("PMT-2025110003", payment("LN-2018-00089", "2025-11-01", "1077.05"));
        LEGACY_PAYMENTS.put("PMT-2025120004", payment("LN-2021-00567", "2025-12-01", "2468.35"));
        LEGACY_PAYMENTS.put("PMT-2025110004", payment("LN-2021-00567", "2025-11-01", "2468.35"));
        LEGACY_PAYMENTS.put("PMT-2025120005", payment("LN-2017-00034", "2025-12-01", "811.61"));
        LEGACY_PAYMENTS.put("PMT-2025110005", payment("LN-2017-00034", "2025-11-01", "811.61"));
    }

    private static BigDecimal[] amounts(String... values) {
        BigDecimal[] parsed = new BigDecimal[values.length];
        for (int i = 0; i < values.length; i++) {
            parsed[i] = new BigDecimal(values[i]);
        }
        return parsed;
    }

    private static Object[] payment(String accountNumber, String paymentDate, String totalAmount) {
        return new Object[]{accountNumber, LocalDate.parse(paymentDate), new BigDecimal(totalAmount)};
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void rowCountsMatchLegacySeed() {
        assertEquals(5, count("borrowers"));
        assertEquals(5, count("loan_products"));
        assertEquals(5, count("loan_accounts"));
        assertEquals(10, count("payments"));
    }

    @Test
    void allForeignKeysResolve() {
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM loan_accounts la LEFT JOIN borrowers b ON la.borrower_id = b.id WHERE b.id IS NULL",
                Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM loan_accounts la LEFT JOIN loan_products p ON la.product_id = p.id WHERE p.id IS NULL",
                Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payments pm LEFT JOIN loan_accounts la ON pm.loan_account_id = la.id WHERE la.id IS NULL",
                Integer.class));
    }

    @Test
    void loanAmountsMatchLegacyValuesAfterConversion() {
        LEGACY_LOAN_AMOUNTS.forEach((accountNumber, expected) -> {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT original_amount, current_balance, interest_rate, monthly_payment "
                            + "FROM loan_accounts WHERE account_number = ?", accountNumber);
            assertAmount(expected[0], row.get("original_amount"), accountNumber + ".original_amount");
            assertAmount(expected[1], row.get("current_balance"), accountNumber + ".current_balance");
            assertAmount(expected[2], row.get("interest_rate"), accountNumber + ".interest_rate");
            assertAmount(expected[3], row.get("monthly_payment"), accountNumber + ".monthly_payment");
        });
    }

    @Test
    void everyLegacyPaymentSequenceNumberIsPresentAsExternalId() {
        List<String> externalIds = jdbcTemplate.queryForList(
                "SELECT external_id FROM payments", String.class);
        assertEquals(LEGACY_PAYMENTS.keySet(), new java.util.HashSet<>(externalIds));
        assertEquals(externalIds.size(), new java.util.HashSet<>(externalIds).size(), "external_id values must be unique");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE external_id IS NULL", Integer.class));
    }

    @Test
    void paymentsMatchLegacyLoanDateAndAmount() {
        LEGACY_PAYMENTS.forEach((externalId, expected) -> {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT la.account_number, pm.payment_date, pm.total_amount FROM payments pm "
                            + "JOIN loan_accounts la ON la.id = pm.loan_account_id WHERE pm.external_id = ?",
                    externalId);
            assertNotNull(row);
            assertEquals(expected[0], row.get("account_number"), externalId + ".loan");
            assertEquals(expected[1], ((java.sql.Date) row.get("payment_date")).toLocalDate(), externalId + ".payment_date");
            assertAmount((BigDecimal) expected[2], row.get("total_amount"), externalId + ".total_amount");
        });
    }

    private void assertAmount(BigDecimal expected, Object actual, String label) {
        BigDecimal value = (BigDecimal) actual;
        assertEquals(0, expected.compareTo(value), label + ": expected " + expected + " but was " + value);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
