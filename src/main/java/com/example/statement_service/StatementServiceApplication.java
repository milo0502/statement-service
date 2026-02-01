package com.example.statement_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main application class for the Statement Service.
 * This class initializes and starts the Spring Boot application.
 */
@SpringBootApplication
@org.springframework.boot.context.properties.ConfigurationPropertiesScan
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