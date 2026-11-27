package org.lab2.gateway.resilience;

import org.junit.jupiter.api.Test;

import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class TimeBasedSlidingWindowTest {

    private static final class MutableClock implements LongSupplier {
        private long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        @Override
        public long getAsLong() {
            return millis;
        }

        private void advanceSeconds(long seconds) {
            millis += seconds * 1000L;
        }
    }

    @Test
    void aggregatesCallsInCurrentSecond() {
        MutableClock clock = new MutableClock(0);
        TimeBasedSlidingWindow window = new TimeBasedSlidingWindow(10, clock);

        window.record(false, false);
        window.record(true, false);
        window.record(true, true);

        TimeBasedSlidingWindow.Metrics metrics = window.metrics();
        assertThat(metrics.calls()).isEqualTo(3);
        assertThat(metrics.failures()).isEqualTo(2);
        assertThat(metrics.slowCalls()).isEqualTo(1);
        assertThat(metrics.failureRatePercent()).isBetween(66.0, 67.0);
        assertThat(metrics.slowCallRatePercent()).isBetween(33.0, 34.0);
    }

    @Test
    void evictsOldestBucketWhenWindowAdvances() {
        MutableClock clock = new MutableClock(0);
        TimeBasedSlidingWindow window = new TimeBasedSlidingWindow(3, clock);

        window.record(true, false); // second 0: 1 failure
        clock.advanceSeconds(1);
        window.record(false, false); // second 1
        clock.advanceSeconds(1);
        window.record(false, false); // second 2

        TimeBasedSlidingWindow.Metrics beforeEviction = window.metrics();
        assertThat(beforeEviction.calls()).isEqualTo(3);
        assertThat(beforeEviction.failures()).isEqualTo(1);

        clock.advanceSeconds(1); // second 3 -> evicts the failure from second 0
        window.record(false, false);

        TimeBasedSlidingWindow.Metrics afterEviction = window.metrics();
        assertThat(afterEviction.calls()).isEqualTo(3);
        assertThat(afterEviction.failures()).isZero();
    }

    @Test
    void resetsWhenWholeWindowIsStale() {
        MutableClock clock = new MutableClock(0);
        TimeBasedSlidingWindow window = new TimeBasedSlidingWindow(3, clock);

        window.record(true, false);
        window.record(true, false);

        clock.advanceSeconds(10); // старше всего окна

        TimeBasedSlidingWindow.Metrics metrics = window.metrics();
        assertThat(metrics.calls()).isZero();
        assertThat(metrics.failures()).isZero();
        assertThat(metrics.slowCalls()).isZero();
    }

    @Test
    void resetClearsEverything() {
        MutableClock clock = new MutableClock(0);
        TimeBasedSlidingWindow window = new TimeBasedSlidingWindow(10, clock);

        window.record(true, true);
        window.reset();

        TimeBasedSlidingWindow.Metrics metrics = window.metrics();
        assertThat(metrics.calls()).isZero();
        assertThat(metrics.failures()).isZero();
        assertThat(metrics.slowCalls()).isZero();
    }
}
