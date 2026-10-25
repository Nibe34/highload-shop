package ua.highload.shop.config;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.Delay;
import io.lettuce.core.resource.DefaultClientResources;
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

    /**
     * ЛР10: backoff перепідключення обмежено 1 с (за замовчуванням Lettuce — до 30 с). Без цього після
     * 20-секундної відмови Redis інтервал між спробами встигав вирости, і застосунок «не помічав»
     * відновлення ще ~20 с — сесії й кошики лишались недоступними, хоча Redis уже працював.
     */
    @Bean(destroyMethod = "shutdown")
    ClientResources lettuceClientResources() {
        return DefaultClientResources.builder()
                .reconnectDelay(Delay.exponential(Duration.ofMillis(50), Duration.ofSeconds(1), 2, TimeUnit.MILLISECONDS))
                .build();
    }
}
