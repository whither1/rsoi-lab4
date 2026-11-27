package org.lab2.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Services services = new Services();
    private final CircuitBreaker circuitBreaker = new CircuitBreaker();

    public Services getServices() {
        return services;
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public static class Services {

        private String flight;
        private String ticket;
        private String bonus;

        public String getFlight() {
            return flight;
        }

        public void setFlight(String flight) {
            this.flight = flight;
        }

        public String getTicket() {
            return ticket;
        }

        public void setTicket(String ticket) {
            this.ticket = ticket;
        }

        public String getBonus() {
            return bonus;
        }

        public void setBonus(String bonus) {
            this.bonus = bonus;
        }
    }

    /**
     * Настройки Circuit Breaker на основе time-based sliding window.
     */
    public static class CircuitBreaker {

        /** Размер скользящего окна в секундах (число корзин циклического массива). */
        private int slidingWindowSize = 10;

        /** Минимум вызовов за окно, прежде чем оценивать долю отказов. */
        private int minimumNumberOfCalls = 2;

        /** Порог доли отказов (в процентах), при котором цепь размыкается. */
        private double failureRateThreshold = 50.0;

        /** Порог доли медленных вызовов (в процентах). */
        private double slowCallRateThreshold = 100.0;

        /** Вызов дольше этого значения (мс) считается медленным. */
        private long slowCallDuration = 1000;

        /** Пауза (мс), после которой разомкнутая цепь переходит в полуоткрытое состояние. */
        private long waitDurationInOpenState = 10000;

        /** Число пробных вызовов в полуоткрытом состоянии. */
        private int permittedCallsInHalfOpenState = 1;

        public int getSlidingWindowSize() {
            return slidingWindowSize;
        }

        public void setSlidingWindowSize(int slidingWindowSize) {
            this.slidingWindowSize = slidingWindowSize;
        }

        public int getMinimumNumberOfCalls() {
            return minimumNumberOfCalls;
        }

        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
        }

        public double getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(double failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public double getSlowCallRateThreshold() {
            return slowCallRateThreshold;
        }

        public void setSlowCallRateThreshold(double slowCallRateThreshold) {
            this.slowCallRateThreshold = slowCallRateThreshold;
        }

        public long getSlowCallDuration() {
            return slowCallDuration;
        }

        public void setSlowCallDuration(long slowCallDuration) {
            this.slowCallDuration = slowCallDuration;
        }

        public long getWaitDurationInOpenState() {
            return waitDurationInOpenState;
        }

        public void setWaitDurationInOpenState(long waitDurationInOpenState) {
            this.waitDurationInOpenState = waitDurationInOpenState;
        }

        public int getPermittedCallsInHalfOpenState() {
            return permittedCallsInHalfOpenState;
        }

        public void setPermittedCallsInHalfOpenState(int permittedCallsInHalfOpenState) {
            this.permittedCallsInHalfOpenState = permittedCallsInHalfOpenState;
        }
    }
}
