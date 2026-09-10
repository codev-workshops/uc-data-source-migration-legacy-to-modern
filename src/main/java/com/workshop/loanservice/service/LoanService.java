package com.workshop.loanservice.service;

import com.workshop.loanservice.dto.BorrowerDto;
import com.workshop.loanservice.dto.LoanSummaryDto;
import com.workshop.loanservice.dto.PaymentDto;
import com.workshop.loanservice.entity.Borrower;
import com.workshop.loanservice.entity.LoanAccount;
import com.workshop.loanservice.entity.Payment;
import com.workshop.loanservice.repository.BorrowerRepository;
import com.workshop.loanservice.repository.LoanAccountRepository;
import com.workshop.loanservice.repository.PaymentRepository;
import com.workshop.loanservice.web.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service layer that reads the modern normalized schema and maps entities to the
 * public DTO contract. All string-to-type parsing happens once, in the startup
 * migration; this class only performs presentation formatting via {@link ApiLabels}.
 */
@Service
@Transactional(readOnly = true)
public class LoanService {

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
        return toLoanSummary(requireLoan(loanAccountNumber));
    }

    public List<BorrowerDto> getAllBorrowers() {
        return borrowerRepository.findAll().stream()
                .map(this::toBorrowerDto)
                .collect(Collectors.toList());
    }

    public BorrowerDto getBorrowerById(String borrowerId) {
        Borrower borrower = borrowerRepository.findByExternalId(borrowerId)
                .orElseThrow(() -> new ResourceNotFoundException("Borrower not found: " + borrowerId));
        BorrowerDto dto = toBorrowerDto(borrower);
        dto.setLoans(loanAccountRepository.findByBorrowerId(borrower.getId()).stream()
                .map(this::toLoanSummary)
                .collect(Collectors.toList()));
        return dto;
    }

    public List<PaymentDto> getPaymentsByLoan(String loanAccountNumber) {
        requireLoan(loanAccountNumber);
        return paymentRepository.findByLoanAccountAccountNumberOrderByPaymentDateDesc(loanAccountNumber)
                .stream()
                .map(this::toPaymentDto)
                .collect(Collectors.toList());
    }

    private LoanAccount requireLoan(String loanAccountNumber) {
        return loanAccountRepository.findByAccountNumber(loanAccountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Loan not found: " + loanAccountNumber));
    }

    private LoanSummaryDto toLoanSummary(LoanAccount acct) {
        Borrower borrower = acct.getBorrower();
        LoanSummaryDto dto = new LoanSummaryDto();
        dto.setLoanAccountNumber(acct.getAccountNumber());
        dto.setBorrowerName(borrower.getFirstName() + " " + borrower.getLastName());
        dto.setProductDescription(acct.getProduct().getName());
        dto.setOriginalAmount(ApiLabels.amount(acct.getOriginalAmount()));
        dto.setCurrentBalance(ApiLabels.amount(acct.getCurrentBalance()));
        dto.setInterestRate(ApiLabels.amount(acct.getInterestRate()));
        dto.setMonthlyPayment(ApiLabels.amount(acct.getMonthlyPayment()));
        dto.setStatus(ApiLabels.loanStatus(acct.getStatus()));
        dto.setOriginationDate(ApiLabels.date(acct.getOriginationDate()));
        dto.setPropertyAddress(acct.getPropertyAddress() + ", " + acct.getPropertyCity()
                + ", " + acct.getPropertyState() + " " + acct.getPropertyZip());
        dto.setPropertyType(ApiLabels.propertyType(acct.getPropertyType()));
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

    private PaymentDto toPaymentDto(Payment pmt) {
        PaymentDto dto = new PaymentDto();
        dto.setPaymentId(pmt.getExternalId());
        dto.setLoanAccountNumber(pmt.getLoanAccount().getAccountNumber());
        dto.setPaymentDate(ApiLabels.date(pmt.getPaymentDate()));
        dto.setTotalAmount(ApiLabels.amount(pmt.getTotalAmount()));
        dto.setPrincipalAmount(ApiLabels.amount(pmt.getPrincipalAmount()));
        dto.setInterestAmount(ApiLabels.amount(pmt.getInterestAmount()));
        dto.setEscrowAmount(ApiLabels.amount(pmt.getEscrowAmount()));
        dto.setLateFee(ApiLabels.amount(pmt.getLateFee()));
        dto.setType(ApiLabels.paymentType(pmt.getType()));
        dto.setStatus(ApiLabels.paymentStatus(pmt.getStatus()));
        return dto;
    }
}
