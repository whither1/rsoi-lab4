package org.lab2.gateway.resilience;

import org.junit.jupiter.api.Test;
import org.lab2.gateway.config.AppProperties;
import org.springframework.web.client.ResourceAccessException;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {

    private static final Supplier<String> FALLBACK = () -> "fallback";

    private static final class MutableClock implements LongSupplier {
        private long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        @Override
        public long getAsLong() {
            return millis;
        }

        private void advanceMillis(long millis) {
            this.millis += millis;
        }
    }

    @Test
    void staysClosedUntilFailureRateThresholdReached() {
        MutableClock clock = new MutableClock(0);
        CircuitBreaker breaker = new CircuitBreaker("test", config(), clock);

        breaker.execute(failing(), FALLBACK);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        breaker.execute(failing(), FALLBACK);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void openCircuitDoesNotCallDownstream() {
        MutableClock clock = new MutableClock(0);
        CircuitBreaker breaker = new CircuitBreaker("test", config(), clock);
        openCircuit(breaker);

        AtomicInteger invocations = new AtomicInteger();
        Supplier<String> counted = () -> {
            invocations.incrementAndGet();
            return "ok";
        };

        String result = breaker.execute(counted, FALLBACK);

        assertThat(result).isEqualTo("fallback");
        assertThat(invocations.get()).isZero();
    }

    @Test
    void closesAfterSuccessfulProbeOnceWaitDurationPassed() {
        MutableClock clock = new MutableClock(0);
        CircuitBreaker breaker = new CircuitBreaker("test", config(), clock);
        openCircuit(breaker);

        clock.advanceMillis(10_000);

        String result = breaker.execute(() -> "ok", FALLBACK);

        assertThat(result).isEqualTo("ok");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void reopensWhenProbeFails() {
        MutableClock clock = new MutableClock(0);
        CircuitBreaker breaker = new CircuitBreaker("test", config(), clock);
        openCircuit(breaker);

        clock.advanceMillis(10_000);

        breaker.execute(failing(), FALLBACK);

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    private void openCircuit(CircuitBreaker breaker) {
        breaker.execute(failing(), FALLBACK);
        breaker.execute(failing(), FALLBACK);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    private static Supplier<String> failing() {
        return () -> {
            throw new ResourceAccessException("downstream is down");
        };
    }

    private static AppProperties.CircuitBreaker config() {
        AppProperties.CircuitBreaker config = new AppProperties.CircuitBreaker();
        config.setSlidingWindowSize(10);
        config.setMinimumNumberOfCalls(2);
        config.setFailureRateThreshold(50);
        config.setSlowCallRateThreshold(100);
        config.setSlowCallDuration(1000);
        config.setWaitDurationInOpenState(10_000);
        config.setPermittedCallsInHalfOpenState(1);
        return config;
    }
}
