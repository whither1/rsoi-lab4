package org.lab2.gateway.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;

/**
 * Настройка HTTP-клиента для общения с downstream-сервисами: ограничиваем таймауты, чтобы
 * недоступный контейнер не подвешивал запрос на десятки секунд.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClientCustomizer downstreamRestClientCustomizer() {
        return builder -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(2));
            factory.setReadTimeout(Duration.ofSeconds(10));
            builder.requestFactory(factory);
        };
    }
}
