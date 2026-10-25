package ua.highload.shop.config;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {

    /** Коли з'єднання з Redis розірване — команди відхиляються одразу, а не стоять у черзі до таймауту. */
    @Bean
    LettuceClientConfigurationBuilderCustomizer rejectWhenDisconnected() {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .autoReconnect(true)
                .build());
    }
}
