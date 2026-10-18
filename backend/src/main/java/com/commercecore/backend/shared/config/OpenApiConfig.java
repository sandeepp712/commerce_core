package com.commercecore.backend.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    private static final String SECURITY_SCHEME_NAME = "Bearer Authentication";

    @Bean
    public OpenAPI commerceCoreOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                    .title("Commerce Core API")
                        .description("""
                            Production-grade E-commerce Backend
                            
                            Key endpoints:
                             - POST /api/v1/checkout — Full checkout with inventory reservation
                             - POST /api/v1/auth/register — User registration
                             - POST /api/v1/auth/login — Login (returns JWT)
                             - POST /webhooks/stripe — Payment webhook (HMAC verified)
                                
                            Load-tested with k6: 500 concurrent users, zero overselling.
                        """)
                        .version("1.0.0"))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new io.swagger.v3.oas.models.Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .name(SECURITY_SCHEME_NAME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Paste the JWT returned by POST /api/v1/auth/login")));
    }
}