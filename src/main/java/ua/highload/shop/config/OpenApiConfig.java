package ua.highload.shop.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bearer-схема для Swagger UI: заголовок {@code Authorization} як звичайний параметр Swagger UI не надсилає
 * (специфікація OpenAPI його ігнорує), тому токен вводиться через кнопку «Authorize».
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearer";

    @Bean
    public OpenAPI shopOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Shop API").version("1.0")
                        .description("Токен: POST /api/auth/login (user{N}@shop.test / password) → Authorize → вставити token"))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
