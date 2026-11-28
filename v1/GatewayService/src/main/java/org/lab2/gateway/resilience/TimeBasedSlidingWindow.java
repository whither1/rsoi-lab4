package org.lab2.gateway.resilience;

import java.util.function.LongSupplier;

/**
 * Скользящее временное окно (time-based sliding window) для сбора статистики вызовов.
 *
 * <p>Используется циклический массив из {@code sizeSeconds} корзин — по одной на секунду.
 * Голова массива соответствует текущей секунде, остальные корзины — предыдущим секундам.
 * Отдельные результаты вызовов не хранятся: каждая корзина содержит частичную агрегацию из
 * трёх счётчиков (число неуспешных, число медленных и общее число вызовов), а общая
 * агрегация обновляется инкрементально.
 *
 * <p>При переходе в новую секунду голова сдвигается; корзина, которая становится новой
 * головой, — самая старая. Её частичная агрегация вычитается из общей и корзина сбрасывается.
 * Если с момента последней записи прошло не меньше {@code sizeSeconds} секунд, всё окно
 * считается устаревшим и очищается целиком.
 */
public class TimeBasedSlidingWindow {

    private final int sizeSeconds;
    private final Bucket[] buckets;
    private final LongSupplier clockMillis;

    private int head;
    private long headSecond;
    private int totalCalls;
    private int totalFailures;
    private int totalSlowCalls;

    public TimeBasedSlidingWindow(int sizeSeconds, LongSupplier clockMillis) {
        if (sizeSeconds <= 0) {
            throw new IllegalArgumentException("sizeSeconds must be positive");
        }
        this.sizeSeconds = sizeSeconds;
        this.clockMillis = clockMillis;
        this.buckets = new Bucket[sizeSeconds];
        for (int i = 0; i < sizeSeconds; i++) {
            this.buckets[i] = new Bucket();
        }
        this.head = 0;
        this.headSecond = currentSecond();
    }

    /**
     * Записывает результат вызова в корзину текущей секунды.
     */
    public synchronized void record(boolean failed, boolean slow) {
        advance(currentSecond());
        Bucket bucket = buckets[head];
        bucket.calls++;
        totalCalls++;
        if (failed) {
            bucket.failures++;
            totalFailures++;
        }
        if (slow) {
            bucket.slowCalls++;
            totalSlowCalls++;
        }
    }

    /**
     * Возвращает агрегированную статистику за текущее окно (с ленивым вытеснением старых корзин).
     */
    public synchronized Metrics metrics() {
        advance(currentSecond());
        return new Metrics(totalCalls, totalFailures, totalSlowCalls);
    }

    /**
     * Полностью очищает окно (например, при успешном восстановлении цепи).
     */
    public synchronized void reset() {
        for (Bucket bucket : buckets) {
            bucket.clear();
        }
        totalCalls = 0;
        totalFailures = 0;
        totalSlowCalls = 0;
        head = 0;
        headSecond = currentSecond();
    }

    private void advance(long nowSecond) {
        long delta = nowSecond - headSecond;
        if (delta <= 0) {
            return;
        }
        if (delta >= sizeSeconds) {
            for (Bucket bucket : buckets) {
                bucket.clear();
            }
            totalCalls = 0;
            totalFailures = 0;
            totalSlowCalls = 0;
            head = 0;
            headSecond = nowSecond;
            return;
        }
        for (long i = 0; i < delta; i++) {
            head = (head + 1) % sizeSeconds;
            Bucket evicted = buckets[head];
            totalCalls -= evicted.calls;
            totalFailures -= evicted.failures;
            totalSlowCalls -= evicted.slowCalls;
            evicted.clear();
        }
        headSecond = nowSecond;
    }

    private long currentSecond() {
        return clockMillis.getAsLong() / 1000L;
    }

    private static final class Bucket {
        private int calls;
        private int failures;
        private int slowCalls;

        private void clear() {
            calls = 0;
            failures = 0;
            slowCalls = 0;
        }
    }

    public record Metrics(int calls, int failures, int slowCalls) {

        public double failureRatePercent() {
            return calls == 0 ? 0.0 : (failures * 100.0) / calls;
        }

        public double slowCallRatePercent() {
            return calls == 0 ? 0.0 : (slowCalls * 100.0) / calls;
        }
    }
}
