package com.example.statement_service.service;

import java.time.LocalDate;

import com.example.statement_service.observability.StatementMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StatementServiceUploadValidationTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 1, 31);

    private final StatementMetrics metrics = mock(StatementMetrics.class);
    private final StatementService service = new StatementService(null, null, null, null, null, metrics, null);

    @Test
    void rejectsEmptyFile() {
        assertInvalidUpload(pdf("statement.pdf", "application/pdf", new byte[0]), "PDF file is required");
    }

    @Test
    void rejectsOversizedFile() {
        assertInvalidUpload(
                pdf("statement.pdf", "application/pdf", new byte[(10 * 1024 * 1024) + 1]),
                "exceeds the 10MB upload limit"
        );
    }

    @Test
    void rejectsWrongContentType() {
        assertInvalidUpload(
                pdf("statement.pdf", "text/plain", "%PDF-1.4\n".getBytes()),
                "Only application/pdf is supported"
        );
    }

    @Test
    void rejectsFileWithoutPdfSignature() {
        assertInvalidUpload(
                pdf("statement.pdf", "application/pdf", "not a pdf".getBytes()),
                "valid PDF signature"
        );
    }

    @Test
    void rejectsSuspiciousFilename() {
        assertInvalidUpload(
                pdf("../statement.pdf", "application/pdf", "%PDF-1.4\n".getBytes()),
                "filename is not supported"
        );
    }

    @Test
    void rejectsSuspiciousMetadata() {
        assertThatThrownBy(() -> service.upload("../customer", "account-1", PERIOD_START, PERIOD_END,
                pdf("statement.pdf", "application/pdf", "%PDF-1.4\n".getBytes())))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("customerId contains unsupported characters");

        verify(metrics).uploadFailure();
    }

    private void assertInvalidUpload(MockMultipartFile file, String message) {
        assertThatThrownBy(() -> service.upload("customer-1", "account-1", PERIOD_START, PERIOD_END, file))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(message);

        verify(metrics).uploadFailure();
    }

    private MockMultipartFile pdf(String filename, String contentType, byte[] content) {
        return new MockMultipartFile("file", filename, contentType, content);
    }
}
