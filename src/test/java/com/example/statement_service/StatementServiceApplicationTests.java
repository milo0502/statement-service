package com.example.statement_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class StatementServiceApplicationTests {

	@MockitoBean
	private S3Client s3Client;

	@MockitoBean
	private S3Presigner s3Presigner;

	@Test
	void contextLoads() {
	}

}
