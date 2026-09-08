package com.workshop.loanservice.service;

import com.workshop.loanservice.dto.BorrowerDto;
import com.workshop.loanservice.dto.LoanSummaryDto;
import com.workshop.loanservice.dto.PaymentDto;
import com.workshop.loanservice.entity.Borrower;
import com.workshop.loanservice.entity.LoanAccount;
import com.workshop.loanservice.entity.LoanProduct;
import com.workshop.loanservice.entity.Payment;
import com.workshop.loanservice.repository.BorrowerRepository;
import com.workshop.loanservice.repository.LoanAccountRepository;
import com.workshop.loanservice.repository.PaymentRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service layer that reads from the modern, normalized schema and renders the
 * DTOs of the public API.
 *
 * The database stores canonical values (ACTIVE, REGULAR, POSTED, NSF, ...) and
 * typed columns; this layer maps them back to the API labels and the
 * MM/DD/YYYY date strings the API has always exposed.
 */
@Service
public class LoanService {

    private static final DateTimeFormatter API_DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    private static final Map<String, String> LOAN_STATUS_LABELS = Map.of(
            "ACTIVE", "Active",
            "CLOSED", "Closed",
            "DEFAULT", "Default",
            "FORBEARANCE", "Forbearance");

    private static final Map<String, String> PROPERTY_TYPE_LABELS = Map.of(
            "SINGLE_FAMILY_RESIDENCE", "Single Family Residence",
            "CONDOMINIUM", "Condominium",
            "MULTI_FAMILY_RESIDENCE", "Multi-Family Residence",
            "TOWNHOUSE", "Townhouse");

    private static final Map<String, String> PAYMENT_TYPE_LABELS = Map.of(
            "REGULAR", "Regular",
            "EXTRA", "Extra",
            "PARTIAL", "Partial",
            "PREPAYMENT", "Prepayment");

    private static final Map<String, String> PAYMENT_STATUS_LABELS = Map.of(
            "POSTED", "Posted",
            "REVERSED", "Reversed",
            "NSF", "Non-Sufficient Funds",
            "PENDING", "Pending");

    private final BorrowerRepository borrowerRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final PaymentRepository paymentRepository;

    public LoanService(BorrowerRepository borrowerRepository,
                       LoanAccountRepository loanAccountRepository,
                       PaymentRepository paymentRepository) {
        this.borrowerRepository = borrowerRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.paymentRepository = paymentRepository;
    }

    public List<LoanSummaryDto> getAllLoans() {
        return loanAccountRepository.findAll().stream()
                .map(this::toLoanSummary)
                .collect(Collectors.toList());
    }

    public LoanSummaryDto getLoanById(String loanAccountNumber) {
        LoanAccount account = loanAccountRepository.findByAccountNumber(loanAccountNumber)
                .orElseThrow(() -> new RuntimeException("Loan not found: " + loanAccountNumber));
        return toLoanSummary(account);
    }

    public List<BorrowerDto> getAllBorrowers() {
        return borrowerRepository.findAll().stream()
                .map(this::toBorrowerDto)
                .collect(Collectors.toList());
    }

    public BorrowerDto getBorrowerById(String borrowerId) {
        Borrower borrower = borrowerRepository.findByExternalId(borrowerId)
                .orElseThrow(() -> new RuntimeException("Borrower not found: " + borrowerId));
        BorrowerDto dto = toBorrowerDto(borrower);

        List<LoanSummaryDto> loans = loanAccountRepository.findByBorrowerExternalId(borrowerId)
                .stream()
                .map(this::toLoanSummary)
                .collect(Collectors.toList());
        dto.setLoans(loans);

        return dto;
    }

    public List<PaymentDto> getPaymentsByLoan(String loanAccountNumber) {
        return paymentRepository.findByLoanAccountAccountNumberOrderByPaymentDateDesc(loanAccountNumber)
                .stream()
                .map(this::toPaymentDto)
                .collect(Collectors.toList());
    }

    private LoanSummaryDto toLoanSummary(LoanAccount account) {
        Borrower borrower = account.getBorrower();
        LoanProduct product = account.getProduct();

        LoanSummaryDto dto = new LoanSummaryDto();
        dto.setLoanAccountNumber(account.getAccountNumber());
        dto.setBorrowerName(borrower.getFirstName() + " " + borrower.getLastName());
        dto.setProductDescription(product != null ? product.getName() : null);
        dto.setOriginalAmount(account.getOriginalAmount());
        dto.setCurrentBalance(account.getCurrentBalance());
        dto.setInterestRate(account.getInterestRate());
        dto.setMonthlyPayment(account.getMonthlyPayment());
        dto.setStatus(label(LOAN_STATUS_LABELS, account.getStatus()));
        dto.setOriginationDate(formatApiDate(account.getOriginationDate()));
        dto.setPropertyAddress(account.getPropertyAddress() + ", " + account.getPropertyCity()
                + ", " + account.getPropertyState() + " " + account.getPropertyZip());
        dto.setPropertyType(label(PROPERTY_TYPE_LABELS, account.getPropertyType()));
        return dto;
    }

    private BorrowerDto toBorrowerDto(Borrower borrower) {
        BorrowerDto dto = new BorrowerDto();
        dto.setId(borrower.getExternalId());
        String middle = borrower.getMiddleInitial() != null ? " " + borrower.getMiddleInitial() + "." : "";
        dto.setFullName(borrower.getFirstName() + middle + " " + borrower.getLastName());
        dto.setEmail(borrower.getEmail());
        dto.setPhone(borrower.getPhone());
        dto.setCity(borrower.getCity());
        dto.setState(borrower.getState());
        dto.setCreditScore(borrower.getCreditScore());
        dto.setEmploymentStatus(borrower.getEmploymentStatus());
        return dto;
    }

    private PaymentDto toPaymentDto(Payment payment) {
        PaymentDto dto = new PaymentDto();
        dto.setPaymentId(payment.getExternalId());
        dto.setLoanAccountNumber(payment.getLoanAccount().getAccountNumber());
        dto.setPaymentDate(formatApiDate(payment.getPaymentDate()));
        dto.setTotalAmount(payment.getTotalAmount());
        dto.setPrincipalAmount(orZero(payment.getPrincipalAmount()));
        dto.setInterestAmount(orZero(payment.getInterestAmount()));
        dto.setEscrowAmount(orZero(payment.getEscrowAmount()));
        dto.setLateFee(orZero(payment.getLateFee()));
        dto.setType(label(PAYMENT_TYPE_LABELS, payment.getType()));
        dto.setStatus(label(PAYMENT_STATUS_LABELS, payment.getStatus()));
        return dto;
    }

    private BigDecimal orZero(BigDecimal amount) {
        return amount != null ? amount : BigDecimal.ZERO;
    }

    private String formatApiDate(LocalDate date) {
        return date != null ? date.format(API_DATE_FORMAT) : null;
    }

    private String label(Map<String, String> labels, String canonicalValue) {
        if (canonicalValue == null) return "Unknown";
        return labels.getOrDefault(canonicalValue, canonicalValue);
    }
}
