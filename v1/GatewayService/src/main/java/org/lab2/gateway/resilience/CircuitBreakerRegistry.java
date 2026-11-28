package org.lab2.gateway.resilience;

import org.lab2.gateway.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/**
 * Хранит по одному Circuit Breaker на каждый downstream-сервис.
 */
@Component
public class CircuitBreakerRegistry {

    private final CircuitBreaker flight;
    private final CircuitBreaker ticket;
    private final CircuitBreaker bonus;

    public CircuitBreakerRegistry(AppProperties properties) {
        LongSupplier clockMillis = System::currentTimeMillis;
        AppProperties.CircuitBreaker config = properties.getCircuitBreaker();
        this.flight = new CircuitBreaker("flight", config, clockMillis);
        this.ticket = new CircuitBreaker("ticket", config, clockMillis);
        this.bonus = new CircuitBreaker("bonus", config, clockMillis);
    }

    public CircuitBreaker flight() {
        return flight;
    }

    public CircuitBreaker ticket() {
        return ticket;
    }

    public CircuitBreaker bonus() {
        return bonus;
    }
}
