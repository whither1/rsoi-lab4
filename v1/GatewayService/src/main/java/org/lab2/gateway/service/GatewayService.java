package org.lab2.gateway.service;

import org.lab2.dto.FlightResponse;
import org.lab2.dto.PaginationResponse;
import org.lab2.dto.PrivilegeInfoResponse;
import org.lab2.dto.PrivilegeOperationRequest;
import org.lab2.dto.PrivilegeShortInfo;
import org.lab2.dto.TicketPurchaseRequest;
import org.lab2.dto.TicketPurchaseResponse;
import org.lab2.dto.TicketResponse;
import org.lab2.dto.TicketShortInfo;
import org.lab2.dto.UserInfoResponse;
import org.lab2.enums.OperationType;
import org.lab2.enums.TicketStatus;
import org.lab2.gateway.client.BonusClient;
import org.lab2.gateway.client.FlightClient;
import org.lab2.gateway.client.TicketClient;
import org.lab2.gateway.queue.RabbitConfig;
import org.lab2.gateway.queue.RefundMessage;
import org.lab2.gateway.resilience.CircuitBreakerRegistry;
import org.lab2.gateway.resilience.ServiceUnavailableException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GatewayService {

    private static final int BONUS_PERCENT = 10;

    private final FlightClient flightClient;
    private final TicketClient ticketClient;
    private final BonusClient bonusClient;
    private final CircuitBreakerRegistry breakers;
    private final RabbitTemplate rabbitTemplate;

    public GatewayService(FlightClient flightClient,
                          TicketClient ticketClient,
                          BonusClient bonusClient,
                          CircuitBreakerRegistry breakers,
                          RabbitTemplate rabbitTemplate) {
        this.flightClient = flightClient;
        this.ticketClient = ticketClient;
        this.bonusClient = bonusClient;
        this.breakers = breakers;
        this.rabbitTemplate = rabbitTemplate;
    }

    public PaginationResponse<FlightResponse> getFlights(int page, int size) {
        return breakers.flight().execute(
                () -> flightClient.getFlights(page, size),
                () -> {
                    throw new ServiceUnavailableException("Flight");
                });
    }

    public PrivilegeInfoResponse getPrivilege(String username) {
        return breakers.bonus().execute(
                () -> bonusClient.getPrivilege(username),
                () -> {
                    throw new ServiceUnavailableException("Bonus");
                });
    }

    public List<TicketResponse> getTickets(String username) {
        List<TicketShortInfo> tickets = breakers.ticket().execute(
                () -> ticketClient.getTickets(username),
                () -> {
                    throw new ServiceUnavailableException("Ticket");
                });
        Map<String, FlightResponse> flights = new HashMap<>();
        return tickets.stream()
                .map(ticket -> enrich(ticket, flights))
                .toList();
    }

    public TicketResponse getTicket(UUID ticketUid, String username) {
        TicketShortInfo ticket = breakers.ticket().execute(
                () -> ticketClient.getTicket(ticketUid, username),
                () -> {
                    throw new ServiceUnavailableException("Ticket");
                });
        return enrich(ticket, new HashMap<>());
    }

    public UserInfoResponse getMe(String username) {
        List<TicketResponse> tickets = getTickets(username);
        PrivilegeShortInfo privilege = breakers.bonus().executeLenient(
                () -> {
                    PrivilegeInfoResponse info = bonusClient.getPrivilege(username);
                    return new PrivilegeShortInfo(info.balance(), info.status());
                },
                () -> new PrivilegeShortInfo(null, null));
        return new UserInfoResponse(tickets, privilege);
    }

    public TicketPurchaseResponse purchase(String username, TicketPurchaseRequest request) {
        FlightResponse flight = breakers.flight().execute(
                () -> flightClient.getFlight(request.flightNumber()),
                () -> {
                    throw new ServiceUnavailableException("Flight");
                });

        int price = flight.price();
        int paidByBonuses = 0;
        if (Boolean.TRUE.equals(request.paidFromBalance())) {
            PrivilegeInfoResponse privilege = breakers.bonus().execute(
                    () -> bonusClient.getPrivilege(username),
                    () -> {
                        throw new ServiceUnavailableException("Bonus");
                    });
            paidByBonuses = Math.min(privilege.balance(), price);
        }

        int paidByMoney = price - paidByBonuses;
        int balanceDiff;
        OperationType operationType;
        if (paidByBonuses > 0) {
            balanceDiff = -paidByBonuses;
            operationType = OperationType.DEBIT_THE_ACCOUNT;
        } else {
            balanceDiff = price * BONUS_PERCENT / 100;
            operationType = OperationType.FILLED_BY_MONEY;
        }

        TicketShortInfo ticket;
        try {
            ticket = ticketClient.createTicket(username, flight.flightNumber(), price);
        } catch (HttpClientErrorException e) {
            throw e;
        } catch (RestClientException e) {
            throw new ServiceUnavailableException("Ticket");
        }

        PrivilegeShortInfo newPrivilege;
        try {
            newPrivilege = bonusClient.applyOperation(username,
                    new PrivilegeOperationRequest(ticket.ticketUid(), balanceDiff, operationType));
        } catch (RestClientException e) {
            // Откат операции создания билета, раз бонусную операцию выполнить не удалось
            rollbackTicket(ticket.ticketUid(), username);
            throw new ServiceUnavailableException("Bonus");
        }

        return new TicketPurchaseResponse(
                ticket.ticketUid(),
                flight.flightNumber(),
                flight.fromAirport(),
                flight.toAirport(),
                flight.date(),
                price,
                paidByMoney,
                paidByBonuses,
                TicketStatus.PAID,
                newPrivilege
        );
    }

    public void refund(UUID ticketUid, String username) {
        // Шаг 1: отмена билета в Ticket Service (критичная операция)
        try {
            ticketClient.cancelTicket(ticketUid, username);
        } catch (HttpClientErrorException e) {
            throw e;
        } catch (RestClientException e) {
            throw new ServiceUnavailableException("Ticket");
        }

        // Шаг 2: откат бонусной операции. Если Bonus Service недоступен — ставим в очередь и
        // возвращаем пользователю успешный ответ, операция будет повторена в фоне.
        try {
            reverseBonus(username, ticketUid);
        } catch (RestClientException e) {
            publishRefund(username, ticketUid);
        }
    }

    /**
     * Выполняет откат бонусной операции по билету. Используется как в синхронном сценарии возврата,
     * так и слушателем очереди при повторном выполнении.
     */
    public void processRefund(String username, UUID ticketUid) {
        reverseBonus(username, ticketUid);
    }

    private void reverseBonus(String username, UUID ticketUid) {
        PrivilegeInfoResponse privilege = bonusClient.getPrivilege(username);
        int net = privilege.history().stream()
                .filter(history -> ticketUid.equals(history.ticketUid()))
                .mapToInt(history -> history.balanceDiff())
                .sum();
        if (net == 0) {
            // Операция уже отменена ранее — повторный откат не требуется (idempotency)
            return;
        }
        OperationType reverseType = net > 0 ? OperationType.DEBIT_THE_ACCOUNT : OperationType.FILL_IN_BALANCE;
        bonusClient.applyOperation(username,
                new PrivilegeOperationRequest(ticketUid, -net, reverseType));
    }

    private void publishRefund(String username, UUID ticketUid) {
        rabbitTemplate.convertAndSend(RabbitConfig.REFUND_QUEUE,
                new RefundMessage(username, ticketUid.toString()));
    }

    private void rollbackTicket(UUID ticketUid, String username) {
        try {
            ticketClient.cancelTicket(ticketUid, username);
        } catch (RestClientException ignored) {
            // Откат выполняется по возможности; если Ticket Service тоже недоступен — нечего делать
        }
    }

    private TicketResponse enrich(TicketShortInfo ticket, Map<String, FlightResponse> cache) {
        FlightResponse flight = cache.computeIfAbsent(ticket.flightNumber(), flightNumber ->
                breakers.flight().executeLenient(
                        () -> flightClient.getFlight(flightNumber),
                        // Flight Service некритичен для билетов: подставляем пустые поля
                        () -> new FlightResponse(flightNumber, null, null, null, null)));
        return new TicketResponse(
                ticket.ticketUid(),
                ticket.flightNumber(),
                flight.fromAirport(),
                flight.toAirport(),
                flight.date(),
                ticket.price(),
                ticket.status()
        );
    }
}
