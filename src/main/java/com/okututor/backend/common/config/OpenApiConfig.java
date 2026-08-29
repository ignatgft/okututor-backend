package com.okututor.backend.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Конфигурация OpenAPI/Swagger. Отключается в production-профиле
 * (нет /v3/api-docs и /swagger-ui.html на проде).
 */
@Configuration
@Profile("!prod")
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Okututor API")
                        .version("v1")
                        .description("""
                                Backend API for front_okututor. All JSON keys are snake_case.
                                Auth: POST /api/v1/auth/login -> Bearer access token.
                                OAuth: GET /api/v1/oauth2/authorization/google?role=STUDENT|TUTOR redirects back to
                                {FRONTEND_URL}/oauth/callback?access_token=..&refresh_token=.. (query string contract).
                                """))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
