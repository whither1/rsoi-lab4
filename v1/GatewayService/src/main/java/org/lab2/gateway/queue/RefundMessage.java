package org.lab2.gateway.queue;

/**
 * Сообщение очереди на откат бонусной операции при возврате билета.
 */
public record RefundMessage(
        String username,
        String ticketUid
) {
}
