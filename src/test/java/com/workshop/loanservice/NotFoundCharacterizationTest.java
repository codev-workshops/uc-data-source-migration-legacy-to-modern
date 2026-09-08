package com.workshop.loanservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterization of the current not-found behaviour: the service throws an
 * uncaught RuntimeException and, with no exception handling in place, the
 * application answers HTTP 500.
 *
 * Only the status is asserted; the error body contains volatile,
 * environment-dependent fields (timestamp, trace) that are not part of the
 * observed contract.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:notfoundcharacterization;DB_CLOSE_DELAY=-1")
class NotFoundCharacterizationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void unknownLoanReturnsInternalServerError() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/loans/LN-DOES-NOT-EXIST", String.class);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    void unknownBorrowerReturnsInternalServerError() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/borrowers/B-99999", String.class);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    void unknownLoanPaymentHistoryReturnsEmptyArray() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/loans/LN-DOES-NOT-EXIST/payments", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("[]", response.getBody());
    }
}
