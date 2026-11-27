package org.lab2.gateway.resilience;

/**
 * Выбрасывается, когда некритичный/критичный downstream-сервис недоступен либо вернул 5xx.
 * Обрабатывается {@link org.lab2.gateway.exception.GlobalExceptionHandler} и превращается в
 * ответ 503 Service Unavailable с телом {@code {"message": "<Service> Service unavailable"}}.
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String serviceName) {
        super(serviceName + " Service unavailable");
    }
}
