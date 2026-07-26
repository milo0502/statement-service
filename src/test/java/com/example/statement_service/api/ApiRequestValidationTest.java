package com.example.statement_service.api;

import java.time.LocalDate;

import com.example.statement_service.service.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiRequestValidationTest {

    @Test
    void acceptsBoundaryMetadataPageSizeSortAndAuditActionValues() {
        String maxLengthId = "a".repeat(128);

        assertThatCode(() -> ApiRequestValidation.validateCustomerId(maxLengthId)).doesNotThrowAnyException();
        assertThatCode(() -> ApiRequestValidation.validateAccountId("account_1.2026")).doesNotThrowAnyException();
        assertThatCode(() -> ApiRequestValidation.validatePeriodRange(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 1)
        )).doesNotThrowAnyException();
        assertThatCode(() -> ApiRequestValidation.validateStatementPageable(
                PageRequest.of(0, 100, Sort.by("uploadedAt"))
        )).doesNotThrowAnyException();
        assertThatCode(() -> ApiRequestValidation.validateAuditPageable(
                PageRequest.of(0, 100, Sort.by("createdAt"))
        )).doesNotThrowAnyException();
        assertThatCode(() -> ApiRequestValidation.validateAuditAction("GENERATE_LINK")).doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidMetadataPeriodPageableSortAndAuditValues() {
        assertBadRequest(() -> ApiRequestValidation.validateCustomerId("a".repeat(129)), "customerId");
        assertBadRequest(() -> ApiRequestValidation.validateAccountId("../account"), "accountId");
        assertBadRequest(() -> ApiRequestValidation.validatePeriodRange(
                LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 1, 31)
        ), "periodEnd");
        assertBadRequest(() -> ApiRequestValidation.validateStatementPageable(
                PageRequest.of(0, 101, Sort.by("uploadedAt"))
        ), "size");
        assertBadRequest(() -> ApiRequestValidation.validateStatementPageable(
                PageRequest.of(0, 10, Sort.by("sha256"))
        ), "sort field");
        assertBadRequest(() -> ApiRequestValidation.validateAuditPageable(
                PageRequest.of(0, 10, Sort.by("ip"))
        ), "sort field");
        assertBadRequest(() -> ApiRequestValidation.validateAuditAction("DELETE"), "action");
    }

    private void assertBadRequest(ThrowingCallable callable, String message) {
        assertThatThrownBy(callable::call)
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(message);
    }

    private interface ThrowingCallable {
        void call();
    }
}
