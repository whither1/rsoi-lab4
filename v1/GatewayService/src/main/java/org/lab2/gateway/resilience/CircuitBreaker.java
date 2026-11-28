package org.lab2.gateway.resilience;

import org.lab2.gateway.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Circuit Breaker на основе скользящего временного окна.
 *
 * <p>В состоянии {@link State#CLOSED} все вызовы проходят, а их результаты (успех/отказ и
 * длительность) пишутся в {@link TimeBasedSlidingWindow}. Когда накопленной статистики достаточно
 * ({@code minimumNumberOfCalls}) и доля отказов (или медленных вызовов) за окно превышает порог,
 * цепь размыкается ({@link State#OPEN}).
 *
 * <p>В состоянии {@link State#OPEN} вызовы к реальному сервису не выполняются — сразу отдаётся
 * fallback. Через {@code waitDurationInOpenState} цепь переходит в {@link State#HALF_OPEN} и
 * пропускает ограниченное число пробных вызовов: если все успешны — цепь замыкается и окно
 * очищается, иначе цепь снова размыкается.
 */
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    private final String name;
    private final TimeBasedSlidingWindow window;
    private final int minimumNumberOfCalls;
    private final double failureRateThreshold;
    private final double slowCallRateThreshold;
    private final long slowCallDurationMillis;
    private final long waitDurationInOpenStateMillis;
    private final int permittedCallsInHalfOpenState;
    private final LongSupplier clockMillis;

    private State state = State.CLOSED;
    private long openedAtMillis;
    private int halfOpenCalls;
    private int halfOpenFailures;

    public CircuitBreaker(String name, AppProperties.CircuitBreaker config, LongSupplier clockMillis) {
        this.name = name;
        this.clockMillis = clockMillis;
        this.window = new TimeBasedSlidingWindow(config.getSlidingWindowSize(), clockMillis);
        this.minimumNumberOfCalls = config.getMinimumNumberOfCalls();
        this.failureRateThreshold = config.getFailureRateThreshold();
        this.slowCallRateThreshold = config.getSlowCallRateThreshold();
        this.slowCallDurationMillis = config.getSlowCallDuration();
        this.waitDurationInOpenStateMillis = config.getWaitDurationInOpenState();
        this.permittedCallsInHalfOpenState = config.getPermittedCallsInHalfOpenState();
    }

    /**
     * Операция чтения из критичного источника. При открытой цепи сразу возвращает fallback.
     * Ответы 4xx пробрасываются как есть (сервис ответил — он доступен), а ошибки
     * недоступности/5xx учитываются как отказ.
     */
    public <T> T execute(Supplier<T> call, Supplier<T> fallback) {
        return executeInternal(call, fallback, true);
    }

    /**
     * Операция чтения из некритичного источника: любая ошибка (в том числе 4xx) заменяется
     * fallback-значением.
     */
    public <T> T executeLenient(Supplier<T> call, Supplier<T> fallback) {
        return executeInternal(call, fallback, false);
    }

    private <T> T executeInternal(Supplier<T> call, Supplier<T> fallback, boolean rethrowClientErrors) {
        if (!allowRequest()) {
            log.warn("[{}] circuit {}, returning fallback", name, state);
            return fallback.get();
        }
        long start = clockMillis.getAsLong();
        try {
            T result = call.get();
            recordResult(false, clockMillis.getAsLong() - start);
            return result;
        } catch (HttpClientErrorException e) {
            // 4xx — сервис ответил, значит он жив; это не отказ доступности
            recordResult(false, clockMillis.getAsLong() - start);
            if (rethrowClientErrors) {
                throw e;
            }
            return fallback.get();
        } catch (RestClientException e) {
            recordResult(true, clockMillis.getAsLong() - start);
            return fallback.get();
        }
    }

    private synchronized boolean allowRequest() {
        if (state == State.OPEN) {
            if (clockMillis.getAsLong() - openedAtMillis < waitDurationInOpenStateMillis) {
                return false;
            }
            state = State.HALF_OPEN;
            halfOpenCalls = 0;
            halfOpenFailures = 0;
            log.info("[{}] circuit HALF_OPEN, probing downstream", name);
        }
        if (state == State.HALF_OPEN) {
            if (halfOpenCalls >= permittedCallsInHalfOpenState) {
                return false;
            }
            halfOpenCalls++;
        }
        return true;
    }

    private synchronized void recordResult(boolean failed, long durationMillis) {
        window.record(failed, durationMillis >= slowCallDurationMillis);

        if (state == State.HALF_OPEN) {
            if (failed) {
                halfOpenFailures++;
            }
            if (halfOpenCalls >= permittedCallsInHalfOpenState) {
                completeHalfOpen();
            }
            return;
        }

        if (state == State.CLOSED && shouldOpen()) {
            open();
        }
    }

    private boolean shouldOpen() {
        TimeBasedSlidingWindow.Metrics metrics = window.metrics();
        if (metrics.calls() < minimumNumberOfCalls) {
            return false;
        }
        return metrics.failureRatePercent() >= failureRateThreshold
                || metrics.slowCallRatePercent() >= slowCallRateThreshold;
    }

    private void completeHalfOpen() {
        if (halfOpenFailures == 0) {
            state = State.CLOSED;
            window.reset();
            log.info("[{}] circuit CLOSED after successful probes", name);
        } else {
            open();
        }
    }

    private void open() {
        state = State.OPEN;
        openedAtMillis = clockMillis.getAsLong();
        log.warn("[{}] circuit OPEN (failure rate over sliding window exceeded)", name);
    }

    public synchronized State getState() {
        return state;
    }
}
