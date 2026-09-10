package com.workshop.loanservice.migration;

import com.workshop.loanservice.entity.LoanAccount;
import com.workshop.loanservice.entity.Payment;
import com.workshop.loanservice.repository.LoanAccountRepository;
import com.workshop.loanservice.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DataMigrationServiceTest {

    @Autowired DataMigrationService migrationService;
    @Autowired LoanAccountRepository loanAccounts;
    @Autowired PaymentRepository payments;

    @Test
    void startupMigrationReconcilesRowCountsAndAmounts() {
        MigrationReport report = migrationService.getLastReport();
        assertNotNull(report);
        assertFalse(report.isSkipped());
        assertTrue(report.reconciles(), report.toString());
        assertTrue(report.getQuarantined().isEmpty());

        assertEquals(5, report.table("borrowers").targetRows());
        assertEquals(5, report.table("loan_products").targetRows());
        assertEquals(5, report.table("loan_accounts").targetRows());
        assertEquals(10, report.table("payments").targetRows());

        assertEquals(0, new BigDecimal("505500.00").compareTo(report.table("borrowers").targetAmountSum()));
        assertEquals(0, new BigDecimal("1590000.00").compareTo(report.table("loan_accounts").targetAmountSum()));
        assertEquals(0, new BigDecimal("17536.42").compareTo(report.table("payments").targetAmountSum()));
    }

    @Test
    void secondRunIsSkipped() {
        assertTrue(migrationService.migrate().isSkipped());
        assertEquals(10, payments.count());
    }

    @Test
    @Transactional
    void transformsTypesCodesAndForeignKeys() {
        LoanAccount acct = loanAccounts.findByAccountNumber("LN-2019-00142").orElseThrow();
        assertEquals("B-10001", acct.getBorrower().getExternalId());
        assertEquals("Mitchell", acct.getBorrower().getLastName());
        assertEquals("FXD30", acct.getProduct().getCode());
        assertTrue(acct.getProduct().getIsActive());
        assertEquals(new BigDecimal("271432.56"), acct.getCurrentBalance());
        assertEquals(new BigDecimal("4.750"), acct.getInterestRate());
        assertEquals(LocalDate.of(2019, 2, 15), acct.getOriginationDate());
        assertEquals("ACTIVE", acct.getStatus());
        assertEquals("SINGLE_FAMILY", acct.getPropertyType());
        assertEquals(745, acct.getBorrower().getCreditScore());

        var history = payments.findByLoanAccountAccountNumberOrderByPaymentDateDesc("LN-2019-00142");
        assertEquals(2, history.size());
        Payment latest = history.get(0);
        assertEquals("PMT-2025120001", latest.getExternalId());
        assertEquals(LocalDate.of(2025, 12, 15), latest.getPaymentDate());
        assertEquals("REGULAR", latest.getType());
        assertEquals("POSTED", latest.getStatus());
        assertEquals(new BigDecimal("1487.02"), latest.getTotalAmount());
    }
}
