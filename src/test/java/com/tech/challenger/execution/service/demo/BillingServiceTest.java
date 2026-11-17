package com.tech.challenger.execution.service.demo;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class BillingServiceTest {
    private final BudgetRepository budgets = mock(BudgetRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final RemoteClients remote = mock(RemoteClients.class);
    private final ObjectMapper json = new ObjectMapper();
    private BillingService service;
    private Budget budget;

    @BeforeEach
    void setup() {
        service = new BillingService(budgets, outbox, remote);
        budget = new Budget(1L, "Reparo", new BigDecimal("100.00"));
        when(budgets.findLockedById(budget.getId())).thenReturn(Optional.of(budget));
    }

    @Test
    void rejectsCreationWhenOrderNotInDiagnosis() {
        when(remote.orderStatus(1L)).thenReturn("CANCELADA");
        assertThatThrownBy(() -> service.create(1L, "Reparo", BigDecimal.TEN)).isInstanceOf(ResponseStatusException.class);
        verify(budgets, never()).save(any());
    }

    @Test
    void decisionIsIdempotentButCannotBeChanged() {
        service.decide(budget.getId(), false);
        service.decide(budget.getId(), false);
        assertThatThrownBy(() -> service.decide(budget.getId(), true)).isInstanceOf(ResponseStatusException.class);
        verify(outbox, times(1)).save(any());
    }

    @Test
    void checkoutRequiresApproval() {
        assertThatThrownBy(() -> service.checkout(budget.getId())).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(remote);
    }

    @Test
    void paymentMustMatchAmountAndCurrency() throws Exception {
        service.decide(budget.getId(), true);
        var payment = json.readTree("{\"id\":123,\"external_reference\":\"" + budget.getId()
                + "\",\"status\":\"approved\",\"transaction_amount\":99.00,\"currency_id\":\"BRL\"}");
        assertThatThrownBy(() -> service.confirmPayment(payment)).isInstanceOf(ResponseStatusException.class);
        assertThat(budget.getStatus()).isEqualTo(Budget.Status.APPROVED);
    }

    @Test
    void pendingPaymentDoesNotChangeBudget() throws Exception {
        service.decide(budget.getId(), true);
        var payment = json.readTree("{\"id\":123,\"external_reference\":\"" + budget.getId()
                + "\",\"status\":\"pending\",\"transaction_amount\":100.00,\"currency_id\":\"BRL\"}");
        service.confirmPayment(payment);
        assertThat(budget.getStatus()).isEqualTo(Budget.Status.APPROVED);
        verify(outbox, times(1)).save(any());
    }
}
