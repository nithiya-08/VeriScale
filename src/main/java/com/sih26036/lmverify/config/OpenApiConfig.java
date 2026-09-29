package com.sih26036.lmverify.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API documentation at /swagger-ui.html (machine-readable at /v3/api-docs). This is the
 * integration contract for the national eMaap portal and state Legal Metrology portals.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI lmVerifyOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("LM Verify API")
                        .version("1.0.0")
                        .description("""
                                SIH26036 - Online Verification System for Weighing and Measuring Instruments.

                                **Auth:** call `POST /api/auth/login`, then click *Authorize* and paste the `token`.
                                Public endpoints (`/api/public/**`, `/api/meta/**`) need no token.

                                **Integration points for eMaap / state portals**
                                * `GET /api/public/verify?c=&s=` - verify any certificate (signature + live status)
                                * `GET /api/public/signing-key` - public key to verify certificate signatures offline
                                * `GET /api/admin/search`, `GET /api/admin/export.csv` - records for reconciliation
                                * `GET /api/dashboard/state` - pendency and enforcement statistics
                                """)
                        .license(new License().name("Prototype for Smart India Hackathon 2026")))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
