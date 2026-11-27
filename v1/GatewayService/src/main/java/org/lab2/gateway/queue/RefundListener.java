package org.lab2.gateway.queue;

import org.lab2.gateway.service.GatewayService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Слушатель очереди откатов. Если бонусный сервис снова недоступен, исключение пробрасывается
 * наружу, и сообщение повторно обрабатывается через {@link RabbitConfig} (retry каждые 10 секунд).
 */
@Component
public class RefundListener {

    private final GatewayService gatewayService;

    public RefundListener(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @RabbitListener(queues = RabbitConfig.REFUND_QUEUE)
    public void onRefund(RefundMessage message) {
        gatewayService.processRefund(message.username(), UUID.fromString(message.ticketUid()));
    }
}
