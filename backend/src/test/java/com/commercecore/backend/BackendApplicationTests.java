package com.commercecore.backend;
import io.jsonwebtoken.Jwts;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.crypto.SecretKey;
import java.util.Base64;

@SpringBootTest
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

	public static void main(String[] args) {
		BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
		System.out.println(encoder.encode("password123"));
//		SecretKey key = Jwts.SIG.HS256.key().build();
//
//		// Convert to Base64 string if you want to store it in a config file
//		String base64Key = Base64.getEncoder().encodeToString(key.getEncoded());
//
//		System.out.println("Generated Secret: " + base64Key);
	}
}
