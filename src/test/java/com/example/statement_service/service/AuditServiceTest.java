package com.example.statement_service.service;

import java.util.UUID;

import com.example.statement_service.domain.AuditEvent;
import com.example.statement_service.persistence.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditServiceTest {

    @Test
    void writesAuditEventInRequiresNewTransaction() {
        AuditEventRepository repository = mock(AuditEventRepository.class);
        when(repository.saveAndFlush(any(AuditEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AuditService service = service(repository, new AuditProperties(true, 3, 0));
        UUID statementId = UUID.randomUUID();

        service.log("customer-1", "UPLOAD", statementId, "127.0.0.1", "test-agent");

        ArgumentCaptor<AuditEvent> eventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).saveAndFlush(eventCaptor.capture());
        AuditEvent saved = eventCaptor.getValue();
        assertThat(saved.getCustomerId()).isEqualTo("customer-1");
        assertThat(saved.getAction()).isEqualTo("UPLOAD");
        assertThat(saved.getStatementId()).isEqualTo(statementId);
        assertThat(saved.getIp()).isEqualTo("127.0.0.1");
        assertThat(saved.getUserAgent()).isEqualTo("test-agent");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void retriesTransientAuditWriteFailure() {
        AuditEventRepository repository = mock(AuditEventRepository.class);
        when(repository.saveAndFlush(any(AuditEvent.class)))
                .thenThrow(new RuntimeException("temporary database failure"))
                .thenAnswer(invocation -> invocation.getArgument(0));
        AuditService service = service(repository, new AuditProperties(true, 3, 0));

        service.log("customer-1", "DOWNLOAD", UUID.randomUUID(), "127.0.0.1", "test-agent");

        verify(repository, times(2)).saveAndFlush(any(AuditEvent.class));
    }

    @Test
    void requiredAuditFailureThrowsAfterRetries() {
        AuditEventRepository repository = mock(AuditEventRepository.class);
        RuntimeException databaseFailure = new RuntimeException("database unavailable");
        when(repository.saveAndFlush(any(AuditEvent.class))).thenThrow(databaseFailure);
        AuditService service = service(repository, new AuditProperties(true, 2, 0));

        assertThatThrownBy(() -> service.log("customer-1", "REVOKE", UUID.randomUUID(), "127.0.0.1", "test-agent"))
                .isInstanceOf(AuditLoggingException.class)
                .hasMessageContaining("Audit log write failed after 2 attempt(s)")
                .hasCause(databaseFailure);

        verify(repository, times(2)).saveAndFlush(any(AuditEvent.class));
    }

    @Test
    void optionalAuditFailureIsBestEffortAfterRetries() {
        AuditEventRepository repository = mock(AuditEventRepository.class);
        when(repository.saveAndFlush(any(AuditEvent.class))).thenThrow(new RuntimeException("database unavailable"));
        AuditService service = service(repository, new AuditProperties(false, 2, 0));

        assertThatCode(() -> service.log("customer-1", "GENERATE_LINK", UUID.randomUUID(), "127.0.0.1", "test-agent"))
                .doesNotThrowAnyException();

        verify(repository, times(2)).saveAndFlush(any(AuditEvent.class));
    }

    private AuditService service(AuditEventRepository repository, AuditProperties properties) {
        TransactionTemplate template = new TransactionTemplate(new NoOpTransactionManager());
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new AuditService(repository, properties, template);
    }

    private static class NoOpTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
