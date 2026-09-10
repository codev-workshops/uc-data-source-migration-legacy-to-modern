package com.workshop.loanservice.repository;

import com.workshop.loanservice.entity.LegacyBorrower;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Legacy CDW source repository, read only by {@link com.workshop.loanservice.migration.DataMigrationService}
 * at startup. Runtime code must use {@link BorrowerRepository} instead.
 *
 * @deprecated retained solely as migration input; use {@link BorrowerRepository}.
 */
@Deprecated
@Repository
public interface LegacyBorrowerRepository extends JpaRepository<LegacyBorrower, String> {

    List<LegacyBorrower> findByStatusCode(String statusCode);

    List<LegacyBorrower> findByLastNameIgnoreCase(String lastName);
}
