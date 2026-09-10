package com.workshop.loanservice.migration;

import com.workshop.loanservice.entity.Borrower;
import com.workshop.loanservice.entity.LegacyBorrower;
import com.workshop.loanservice.entity.LegacyLoanAccount;
import com.workshop.loanservice.entity.LegacyLoanProduct;
import com.workshop.loanservice.entity.LegacyPayment;
import com.workshop.loanservice.entity.LoanAccount;
import com.workshop.loanservice.entity.LoanProduct;
import com.workshop.loanservice.entity.Payment;
import com.workshop.loanservice.migration.MigrationReport.TableStats;
import com.workshop.loanservice.repository.BorrowerRepository;
import com.workshop.loanservice.repository.LegacyBorrowerRepository;
import com.workshop.loanservice.repository.LegacyLoanAccountRepository;
import com.workshop.loanservice.repository.LegacyLoanProductRepository;
import com.workshop.loanservice.repository.LegacyPaymentRepository;
import com.workshop.loanservice.repository.LoanAccountRepository;
import com.workshop.loanservice.repository.LoanProductRepository;
import com.workshop.loanservice.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static com.workshop.loanservice.migration.LegacyValueParser.*;

/**
 * One-shot legacy CDW -> modern schema migration, run at startup. Rows are inserted in FK order
 * (borrowers, loan_products, loan_accounts, payments); rows that fail validation or transformation
 * are quarantined in the {@link MigrationReport} instead of aborting the batch.
 */
@Service
public class DataMigrationService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataMigrationService.class);

    private final LegacyBorrowerRepository legacyBorrowers;
    private final LegacyLoanProductRepository legacyProducts;
    private final LegacyLoanAccountRepository legacyAccounts;
    private final LegacyPaymentRepository legacyPayments;
    private final BorrowerRepository borrowers;
    private final LoanProductRepository products;
    private final LoanAccountRepository accounts;
    private final PaymentRepository payments;

    private MigrationReport lastReport;

    public DataMigrationService(LegacyBorrowerRepository legacyBorrowers, LegacyLoanProductRepository legacyProducts,
                                LegacyLoanAccountRepository legacyAccounts, LegacyPaymentRepository legacyPayments,
                                BorrowerRepository borrowers, LoanProductRepository products,
                                LoanAccountRepository accounts, PaymentRepository payments) {
        this.legacyBorrowers = legacyBorrowers;
        this.legacyProducts = legacyProducts;
        this.legacyAccounts = legacyAccounts;
        this.legacyPayments = legacyPayments;
        this.borrowers = borrowers;
        this.products = products;
        this.accounts = accounts;
        this.payments = payments;
    }

    @Override
    public void run(ApplicationArguments args) {
        MigrationReport report = migrate();
        if (report.isSkipped()) {
            log.info("{}", report);
        } else if (report.reconciles()) {
            log.info("Legacy -> modern migration complete and reconciled\n{}", report);
        } else {
            log.warn("Legacy -> modern migration finished with discrepancies\n{}", report);
        }
    }

    public MigrationReport getLastReport() { return lastReport; }

    /** Idempotent: does nothing if any modern table already holds data. */
    @Transactional
    public MigrationReport migrate() {
        MigrationReport report = new MigrationReport();
        if (borrowers.count() > 0 || products.count() > 0 || accounts.count() > 0 || payments.count() > 0) {
            report.markSkipped();
            return lastReport = report;
        }

        Map<String, Borrower> borrowerByExtId = new HashMap<>();
        migrateTable(report, "borrowers", legacyBorrowers.findAll(), LegacyBorrower::getBorrowerId, LegacyBorrower::getAnnualIncome,
                borrowers, Borrower::getAnnualIncome, src -> {
                    Borrower b = toBorrower(src);
                    borrowerByExtId.put(b.getExternalId(), b);
                    return b;
                });

        Map<String, LoanProduct> productByCode = new HashMap<>();
        migrateTable(report, "loan_products", legacyProducts.findAll(), LegacyLoanProduct::getProductCode, LegacyLoanProduct::getMaxAmount,
                products, LoanProduct::getMaxAmount, src -> {
                    LoanProduct p = toProduct(src);
                    productByCode.put(p.getCode(), p);
                    return p;
                });

        Map<String, LoanAccount> accountByNumber = new HashMap<>();
        migrateTable(report, "loan_accounts", legacyAccounts.findAll(), LegacyLoanAccount::getLoanAccountNumber, LegacyLoanAccount::getOriginalAmount,
                accounts, LoanAccount::getOriginalAmount, src -> {
                    LoanAccount a = toAccount(src, borrowerByExtId, productByCode);
                    accountByNumber.put(a.getAccountNumber(), a);
                    return a;
                });

        migrateTable(report, "payments", legacyPayments.findAll(), LegacyPayment::getPaymentSequenceNumber, LegacyPayment::getTotalAmount,
                payments, Payment::getTotalAmount, src -> toPayment(src, accountByNumber));

        return lastReport = report;
    }

    private <S, T> void migrateTable(MigrationReport report, String table, List<S> sources,
                                     Function<S, String> sourceKey, Function<S, String> sourceAmount,
                                     JpaRepository<T, Long> target, Function<T, BigDecimal> targetAmount,
                                     Function<S, T> transform) {
        Set<String> seen = new HashSet<>();
        long migrated = 0, dupes = 0, quarantined = 0;
        BigDecimal sourceSum = BigDecimal.ZERO;
        for (S src : sources) {
            String key = text(sourceKey.apply(src));
            try {
                if (key == null) throw new IllegalArgumentException("Missing business key");
                if (!seen.add(key)) {
                    dupes++;
                    log.warn("Skipping duplicate {} row {}", table, key);
                    continue;
                }
                BigDecimal amt = amount(sourceAmount.apply(src));
                target.save(transform.apply(src));
                migrated++;
                if (amt != null) sourceSum = sourceSum.add(amt);
            } catch (RuntimeException e) {
                quarantined++;
                report.addQuarantined(table, key, e.getMessage());
                log.warn("Quarantined {} row {}: {}", table, key, e.getMessage());
            }
        }
        target.flush();
        BigDecimal targetSum = target.findAll().stream().map(targetAmount)
                .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
        report.addTable(new TableStats(table, sources.size(), migrated, dupes, quarantined,
                target.count(), sourceSum, targetSum));
    }

    private Borrower toBorrower(LegacyBorrower src) {
        Borrower b = new Borrower();
        b.setExternalId(required("BORR_ID", src.getBorrowerId()));
        b.setFirstName(required("BORR_FST_NM", src.getFirstName()));
        b.setLastName(required("BORR_LST_NM", src.getLastName()));
        b.setMiddleInitial(text(src.getMiddleInitial()));
        b.setSsnHash(text(src.getSsnEncrypted()));
        b.setDateOfBirth(date(src.getDateOfBirth()));
        b.setAddressLine1(text(src.getAddressLine1()));
        b.setAddressLine2(text(src.getAddressLine2()));
        b.setCity(text(src.getCity()));
        b.setState(text(src.getStateCode()));
        b.setZipCode(text(src.getZipCode()));
        b.setPhone(text(src.getPhoneNumber()));
        b.setEmail(text(src.getEmail()));
        b.setCreditScore(integer(src.getCreditScore()));
        b.setEmploymentStatus(text(src.getEmploymentStatus()));
        b.setAnnualIncome(amount(src.getAnnualIncome()));
        b.setStatus(borrowerStatus(src.getStatusCode()));
        b.setCreatedAt(timestamp(src.getCreatedDate()));
        b.setUpdatedAt(timestamp(src.getUpdatedDate()));
        return b;
    }

    private LoanProduct toProduct(LegacyLoanProduct src) {
        LoanProduct p = new LoanProduct();
        p.setCode(required("PROD_CD", src.getProductCode()));
        p.setName(required("PROD_DESC_TXT", src.getDescription()));
        p.setType(required("PROD_TYP_CD", src.getTypeCode()));
        p.setTermMonths(requiredValue("PROD_TERM_MOS", integer(src.getTermMonths())));
        p.setRateType(required("PROD_RT_TYP", src.getRateType()));
        p.setMinAmount(amount(src.getMinAmount()));
        p.setMaxAmount(amount(src.getMaxAmount()));
        p.setIsActive(active(src.getStatusCode()));
        p.setEffectiveDate(date(src.getEffectiveDate()));
        p.setExpirationDate(date(src.getExpirationDate()));
        return p;
    }

    private LoanAccount toAccount(LegacyLoanAccount src, Map<String, Borrower> borrowerByExtId,
                                  Map<String, LoanProduct> productByCode) {
        LoanAccount a = new LoanAccount();
        a.setAccountNumber(required("LN_ACCT_NBR", src.getLoanAccountNumber()));
        a.setBorrower(resolve("BORR_ID", src.getBorrowerId(), borrowerByExtId));
        a.setProduct(resolve("PROD_CD", src.getProductCode(), productByCode));
        a.setOriginalAmount(requiredValue("LN_ORIG_AMT", amount(src.getOriginalAmount())));
        a.setCurrentBalance(requiredValue("LN_CURR_BAL", amount(src.getCurrentBalance())));
        a.setInterestRate(requiredValue("LN_INT_RT", amount(src.getInterestRate())));
        a.setTermMonths(requiredValue("LN_TERM_MOS", integer(src.getTermMonths())));
        a.setMonthlyPayment(requiredValue("LN_PMT_AMT", amount(src.getMonthlyPayment())));
        a.setOriginationDate(requiredValue("LN_ORIG_DT", date(src.getOriginationDate())));
        a.setMaturityDate(requiredValue("LN_MAT_DT", date(src.getMaturityDate())));
        a.setFirstPaymentDate(date(src.getFirstPaymentDate()));
        a.setNextPaymentDate(date(src.getNextPaymentDate()));
        a.setStatus(loanStatus(src.getStatusCode()));
        a.setDelinquencyDays(integer(src.getDelinquencyDays()));
        a.setEscrowBalance(amount(src.getEscrowBalance()));
        a.setLtvPercent(amount(src.getLtvPercent()));
        a.setPropertyAddress(text(src.getPropertyAddress()));
        a.setPropertyCity(text(src.getPropertyCity()));
        a.setPropertyState(text(src.getPropertyState()));
        a.setPropertyZip(text(src.getPropertyZip()));
        a.setPropertyType(propertyType(src.getPropertyType()));
        a.setAppraisedValue(amount(src.getAppraisedValue()));
        a.setCreatedAt(timestamp(src.getCreatedDate()));
        a.setUpdatedAt(timestamp(src.getUpdatedDate()));
        return a;
    }

    private Payment toPayment(LegacyPayment src, Map<String, LoanAccount> accountByNumber) {
        Payment p = new Payment();
        p.setExternalId(required("PMT_SEQ_NBR", src.getPaymentSequenceNumber()));
        p.setLoanAccount(resolve("LN_ACCT_NBR", src.getLoanAccountNumber(), accountByNumber));
        p.setPaymentDate(requiredValue("PMT_DT", date(src.getPaymentDate())));
        p.setTotalAmount(requiredValue("PMT_AMT", amount(src.getTotalAmount())));
        p.setPrincipalAmount(amount(src.getPrincipalAmount()));
        p.setInterestAmount(amount(src.getInterestAmount()));
        p.setEscrowAmount(amount(src.getEscrowAmount()));
        p.setLateFee(amount(src.getLateFee()));
        p.setType(requiredValue("PMT_TYP_CD", paymentType(src.getTypeCode())));
        p.setStatus(requiredValue("PMT_STAT_CD", paymentStatus(src.getStatusCode())));
        p.setReceivedDate(date(src.getReceivedDate()));
        p.setProcessedDate(date(src.getProcessedDate()));
        p.setCreatedAt(timestamp(src.getCreatedDate()));
        p.setUpdatedAt(timestamp(src.getUpdatedDate()));
        return p;
    }

    private static String required(String column, String value) {
        return requiredValue(column, text(value));
    }

    private static <V> V requiredValue(String column, V value) {
        if (value == null) throw new IllegalArgumentException("Missing required " + column);
        return value;
    }

    private static <V> V resolve(String column, String key, Map<String, V> lookup) {
        V ref = lookup.get(text(key));
        if (ref == null) throw new IllegalArgumentException("Unresolved FK " + column + "='" + key + "'");
        return ref;
    }
}
