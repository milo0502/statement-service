package com.example.statement_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main application class for the Statement Service.
 * This class initializes and starts the Spring Boot application.
 */
@SpringBootApplication
@EnableScheduling
public class StatementServiceApplication {

	/**
	 * Main entry point of the application.
	 *
	 * @param args command line arguments
	 */
	public static void main(String[] args) {
		SpringApplication.run(StatementServiceApplication.class, args);
	}
}
