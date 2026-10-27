package com.commercecore.backend;

import com.commercecore.backend.shared.config.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@SpringBootTest
class LoadTestTokenGenerator {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtService jwtService;

    @Test
    void generateTokens() throws IOException {
        List<UUID> userIds = jdbc.queryForList(
                "SELECT id FROM users WHERE email LIKE 'loadtest%' ORDER BY email",
                UUID.class
        );

        StringBuilder csv = new StringBuilder("userId,token\n");
        for (UUID userId : userIds) {
            String token = jwtService.generateToken(userId, "CUSTOMER");
            csv.append(userId).append(",").append(token).append("\n");
        }

        Files.writeString(Path.of("../k6/loadtest-tokens.csv"), csv.toString());
        System.out.println("Generated " + userIds.size() + " tokens in loadtest-tokens.csv");
    }
}