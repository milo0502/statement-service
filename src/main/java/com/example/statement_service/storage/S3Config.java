package com.example.statement_service.storage;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.DefaultRetryStrategy;
import software.amazon.awssdk.retries.api.RetryStrategy;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Configuration for S3 storage.
 * Configures {@link S3Client} and {@link S3Presigner} for interacting with S3-compatible storage (like MinIO).
 */
@Configuration
@EnableConfigurationProperties(S3Properties.class)
public class S3Config {

    /**
     * Creates an {@link S3Client} bean.
     *
     * @param props the S3 configuration properties
     * @return the S3 client
     */
    @Bean
    S3Client s3Client(S3Properties props) {
        return S3Client.builder()
                .endpointOverride(URI.create(props.endpoint()))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofMillis(props.connectionTimeoutMillis()))
                        .socketTimeout(Duration.ofMillis(props.socketTimeoutMillis())))
                .overrideConfiguration(clientOverrideConfiguration(props))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.accessKey(), props.secretKey())
                ))
                .region(Region.of(props.region()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true) // important for MinIO
                        .build()
                )
                .build();
    }

    private ClientOverrideConfiguration clientOverrideConfiguration(S3Properties props) {
        // Keep S3 calls bounded so servlet threads are not held forever during network or storage issues.
        // The SDK standard strategy retries transient network errors, throttling, and 5xx responses;
        // client/auth failures such as 400 and 403 are not retried. Standard backoff includes jitter.
        RetryStrategy retryStrategy = DefaultRetryStrategy.standardStrategyBuilder()
                .maxAttempts(Math.max(1, props.maxRetries() + 1))
                .build();

        return ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofMillis(props.apiCallTimeoutMillis()))
                .apiCallAttemptTimeout(Duration.ofMillis(props.apiCallAttemptTimeoutMillis()))
                .retryStrategy(retryStrategy)
                .build();
    }

    /**
     * Creates an {@link S3Presigner} bean.
     *
     * @param props the S3 configuration properties
     * @return the S3 presigner
     */
    @Bean
    S3Presigner s3Presigner(S3Properties props) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(props.presignEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.accessKey(), props.secretKey())
                ))
                .region(Region.of(props.region()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build()
                )
                .build();
    }
}
