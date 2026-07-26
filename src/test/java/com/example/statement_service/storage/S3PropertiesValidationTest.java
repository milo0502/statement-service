package com.example.statement_service.storage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class S3PropertiesValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void failsFastWhenRequiredS3PropertiesAreMissing() {
        contextRunner
                .withPropertyValues(
                        "app.s3.connection-timeout-millis=2000",
                        "app.s3.socket-timeout-millis=5000",
                        "app.s3.api-call-timeout-millis=10000",
                        "app.s3.api-call-attempt-timeout-millis=4000",
                        "app.s3.max-retries=3"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure()))
                            .hasMessageContaining("app.s3")
                            .hasMessageContaining("endpoint")
                            .hasMessageContaining("accessKey")
                            .hasMessageContaining("secretKey");
                });
    }

    @Test
    void startsWhenRequiredS3PropertiesArePresent() {
        contextRunner
                .withPropertyValues(
                        "app.s3.endpoint=http://localhost:9000",
                        "app.s3.region=af-south-1",
                        "app.s3.access-key=test-access-key",
                        "app.s3.secret-key=test-secret-key",
                        "app.s3.bucket=statements",
                        "app.s3.connection-timeout-millis=2000",
                        "app.s3.socket-timeout-millis=5000",
                        "app.s3.api-call-timeout-millis=10000",
                        "app.s3.api-call-attempt-timeout-millis=4000",
                        "app.s3.max-retries=3"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(S3Properties.class)
    static class TestConfig {
    }
}
