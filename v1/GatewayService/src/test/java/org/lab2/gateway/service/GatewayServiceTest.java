package org.lab2.gateway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lab2.dto.FlightResponse;
import org.lab2.dto.PrivilegeInfoResponse;
import org.lab2.dto.PrivilegeOperationRequest;
import org.lab2.dto.PrivilegeShortInfo;
import org.lab2.dto.TicketPurchaseRequest;
import org.lab2.dto.TicketPurchaseResponse;
import org.lab2.dto.TicketResponse;
import org.lab2.dto.TicketShortInfo;
import org.lab2.dto.UserInfoResponse;
import org.lab2.enums.OperationType;
import org.lab2.enums.PrivilegeStatus;
import org.lab2.enums.TicketStatus;
import org.lab2.gateway.client.BonusClient;
import org.lab2.gateway.client.FlightClient;
import org.lab2.gateway.client.TicketClient;
import org.lab2.gateway.config.AppProperties;
import org.lab2.gateway.queue.RabbitConfig;
import org.lab2.gateway.queue.RefundMessage;
import org.lab2.gateway.resilience.CircuitBreakerRegistry;
import org.lab2.gateway.resilience.ServiceUnavailableException;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GatewayServiceTest {

    @Mock
    private FlightClient flightClient;

    @Mock
    private TicketClient ticketClient;

    @Mock
    private BonusClient bonusClient;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private GatewayService gatewayService;

    @BeforeEach
    void setUp() {
        gatewayService = new GatewayService(
                flightClient,
                ticketClient,
                bonusClient,
                new CircuitBreakerRegistry(new AppProperties()),
                rabbitTemplate
        );
    }

    @Test
    void purchase_withMoneyAccruesTenPercentBonuses() {
        UUID ticketUid = UUID.randomUUID();
        when(flightClient.getFlight("AFL031")).thenReturn(flight());
        when(ticketClient.createTicket("Test Max", "AFL031", 1500))
                .thenReturn(new TicketShortInfo(ticketUid, "AFL031", 1500, TicketStatus.PAID));
        when(bonusClient.applyOperation(eq("Test Max"), any())).thenAnswer(invocation -> {
            PrivilegeOperationRequest request = invocation.getArgument(1);
            return new PrivilegeShortInfo(request.balanceDiff(), PrivilegeStatus.BRONZE);
        });

        TicketPurchaseResponse response = gatewayService.purchase("Test Max",
                new TicketPurchaseRequest("AFL031", 1500, false));

        assertThat(response.paidByMoney()).isEqualTo(1500);
        assertThat(response.paidByBonuses()).isZero();
        assertThat(response.status()).isEqualTo(TicketStatus.PAID);
        assertThat(response.privilege().balance()).isEqualTo(150);

        ArgumentCaptor<PrivilegeOperationRequest> captor = ArgumentCaptor.forClass(PrivilegeOperationRequest.class);
        verify(bonusClient).applyOperation(eq("Test Max"), captor.capture());
        assertThat(captor.getValue().operationType()).isEqualTo(OperationType.FILLED_BY_MONEY);
        assertThat(captor.getValue().balanceDiff()).isEqualTo(150);
    }

    @Test
    void purchase_fromBalanceDebitsBonuses() {
        UUID ticketUid = UUID.randomUUID();
        when(flightClient.getFlight("AFL031")).thenReturn(flight());
        when(bonusClient.getPrivilege("Test Max"))
                .thenReturn(new PrivilegeInfoResponse(500, PrivilegeStatus.BRONZE, List.of()));
        when(ticketClient.createTicket("Test Max", "AFL031", 1500))
                .thenReturn(new TicketShortInfo(ticketUid, "AFL031", 1500, TicketStatus.PAID));
        when(bonusClient.applyOperation(eq("Test Max"), any()))
                .thenReturn(new PrivilegeShortInfo(0, PrivilegeStatus.BRONZE));

        TicketPurchaseResponse response = gatewayService.purchase("Test Max",
                new TicketPurchaseRequest("AFL031", 1500, true));

        assertThat(response.paidByBonuses()).isEqualTo(500);
        assertThat(response.paidByMoney()).isEqualTo(1000);
    }

    @Test
    void purchase_rollsBackTicketWhenBonusUnavailable() {
        UUID ticketUid = UUID.randomUUID();
        when(flightClient.getFlight("AFL031")).thenReturn(flight());
        when(ticketClient.createTicket("Test Max", "AFL031", 1500))
                .thenReturn(new TicketShortInfo(ticketUid, "AFL031", 1500, TicketStatus.PAID));
        when(bonusClient.applyOperation(eq("Test Max"), any()))
                .thenThrow(new ResourceAccessException("down"));

        assertThatThrownBy(() -> gatewayService.purchase("Test Max",
                new TicketPurchaseRequest("AFL031", 1500, false)))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("Bonus Service unavailable");

        verify(ticketClient).cancelTicket(ticketUid, "Test Max");
    }

    @Test
    void getTickets_enrichesWithFlightInfo() {
        when(ticketClient.getTickets("Test Max"))
                .thenReturn(List.of(new TicketShortInfo(UUID.randomUUID(), "AFL031", 1500, TicketStatus.PAID)));
        when(flightClient.getFlight("AFL031")).thenReturn(flight());

        List<TicketResponse> tickets = gatewayService.getTickets("Test Max");

        assertThat(tickets).hasSize(1);
        assertThat(tickets.get(0).fromAirport()).isEqualTo("Санкт-Петербург Пулково");
        assertThat(tickets.get(0).toAirport()).isEqualTo("Москва Шереметьево");
        assertThat(tickets.get(0).date()).isEqualTo("2021-10-08 20:00");
    }

    @Test
    void getTickets_fallsBackWhenFlightUnavailable() {
        when(ticketClient.getTickets("Test Max"))
                .thenReturn(List.of(new TicketShortInfo(UUID.randomUUID(), "AFL031", 1500, TicketStatus.PAID)));
        when(flightClient.getFlight("AFL031")).thenThrow(new ResourceAccessException("down"));

        List<TicketResponse> tickets = gatewayService.getTickets("Test Max");

        assertThat(tickets).hasSize(1);
        assertThat(tickets.get(0).fromAirport()).isNull();
        assertThat(tickets.get(0).toAirport()).isNull();
        assertThat(tickets.get(0).date()).isNull();
    }

    @Test
    void getMe_fallsBackWhenBonusUnavailable() {
        when(ticketClient.getTickets("Test Max")).thenReturn(List.of());
        when(bonusClient.getPrivilege("Test Max")).thenThrow(new ResourceAccessException("down"));

        UserInfoResponse me = gatewayService.getMe("Test Max");

        assertThat(me.tickets()).isEmpty();
        assertThat(me.privilege().balance()).isNull();
        assertThat(me.privilege().status()).isNull();
    }

    @Test
    void refund_queuesWhenBonusUnavailable() {
        UUID ticketUid = UUID.randomUUID();
        when(bonusClient.getPrivilege("Test Max")).thenThrow(new ResourceAccessException("down"));

        gatewayService.refund(ticketUid, "Test Max");

        verify(ticketClient).cancelTicket(ticketUid, "Test Max");
        ArgumentCaptor<RefundMessage> captor = ArgumentCaptor.forClass(RefundMessage.class);
        verify(rabbitTemplate).convertAndSend(eq(RabbitConfig.REFUND_QUEUE), captor.capture());
        assertThat(captor.getValue().ticketUid()).isEqualTo(ticketUid.toString());
    }

    private FlightResponse flight() {
        return new FlightResponse("AFL031", "Санкт-Петербург Пулково", "Москва Шереметьево",
                "2021-10-08 20:00", 1500);
    }
}
