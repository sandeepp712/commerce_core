package com.commercecore.backend;
import io.jsonwebtoken.Jwts;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.SecretKey;
import java.util.Base64;

@SpringBootTest
@Testcontainers
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

	@Container
	static final PostgreSQLContainer<?> postgres =
			new PostgreSQLContainer<>("postgres:16-alpine")
					.withDatabaseName("testdb")
					.withUsername("test")
					.withPassword("test");

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
		registry.add("spring.flyway.enabled", () -> "true"); // Disable Flyway for this simple test
	}

	public static void main(String[] args) {
//		BCryptPasswordEncoder encoder  = new BCryptPasswordEncoder(12);
//		System.out.println(encoder.encode("password123"));
//		SecretKey key = Jwts.SIG.HS256.key().build();
//
//		// Convert to Base64 string if you want to store it in a config file
//		String base64Key = Base64.getEncoder().encodeToString(key.getEncoded());
//
//		System.out.println("Generated Secret: " + base64Key);
	}

	@Test
	void generatePasswordHash() {
		BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
		String hash = encoder.encode("password1234");

		System.out.println("\n========================================");
		System.out.println("Generated BCrypt Hash: " + hash);
		System.out.println("========================================\n");
	}
}
