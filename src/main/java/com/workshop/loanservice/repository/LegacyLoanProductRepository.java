package com.workshop.loanservice.repository;

import com.workshop.loanservice.entity.LegacyLoanProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Legacy CDW source repository, read only by {@link com.workshop.loanservice.migration.DataMigrationService}
 * at startup. Runtime code must use {@link LoanProductRepository} instead.
 *
 * @deprecated retained solely as migration input; use {@link LoanProductRepository}.
 */
@Deprecated
@Repository
public interface LegacyLoanProductRepository extends JpaRepository<LegacyLoanProduct, String> {
}
