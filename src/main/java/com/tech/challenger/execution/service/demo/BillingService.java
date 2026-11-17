package com.tech.challenger.execution.service.demo;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class BillingService {
    private final BudgetRepository budgets;
    private final OutboxRepository outbox;
    private final RemoteClients remote;

    BillingService(BudgetRepository budgets, OutboxRepository outbox, RemoteClients remote) {
        this.budgets = budgets;
        this.outbox = outbox;
        this.remote = remote;
    }

    @Transactional
    Budget create(Long orderId, String description, BigDecimal amount) {
        String status = remote.orderStatus(orderId);
        if (!"EM_DIAGNOSTICO".equals(status) && !"AGUARDANDO_APROVACAO".equals(status)) {
            throw conflict("OS deve estar em diagnostico ou aguardando aprovacao");
        }
        if (budgets.findByOrderId(orderId).isPresent()) throw conflict("OS ja possui orcamento");
        Budget budget = budgets.save(new Budget(orderId, description, amount));
        outbox.save(new OutboxEvent(budget, "BUDGET_CREATED"));
        return budget;
    }

    @Transactional(readOnly = true)
    Budget get(UUID id) {
        return budgets.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Orcamento nao encontrado"));
    }

    @Transactional
    Budget decide(UUID id, boolean approved) {
        Budget budget = locked(id);
        Budget.Status target = approved ? Budget.Status.APPROVED : Budget.Status.REJECTED;
        if (budget.getStatus() == target) return budget;
        if (budget.getStatus() != Budget.Status.PENDING_APPROVAL) throw conflict("Orcamento ja decidido");
        budget.setStatus(target);
        outbox.save(new OutboxEvent(budget, approved ? "BUDGET_APPROVED" : "BUDGET_REJECTED"));
        return budget;
    }

    @Transactional
    Budget checkout(UUID id) {
        Budget budget = locked(id);
        if (budget.getStatus() != Budget.Status.APPROVED) throw conflict("Orcamento nao aprovado ou ja pago");
        if (budget.getCheckoutUrl() != null) return budget;
        JsonNode preference = remote.createPreference(budget);
        if (preference == null || !preference.hasNonNull("id") || !preference.hasNonNull("init_point")) {
            throw new IllegalStateException("Resposta invalida ao criar preferencia Mercado Pago");
        }
        budget.setPreference(preference.get("id").asText(), preference.get("init_point").asText());
        return budget;
    }

    @Transactional
    void confirmPayment(JsonNode payment) {
        if (payment == null || !payment.hasNonNull("external_reference") || !payment.hasNonNull("id")
                || !payment.hasNonNull("status") || !payment.hasNonNull("transaction_amount")
                || !payment.hasNonNull("currency_id")) {
            throw new IllegalArgumentException("Pagamento retornado sem dados obrigatorios");
        }
        UUID id;
        try {
            id = UUID.fromString(payment.get("external_reference").asText());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Referencia do pagamento invalida", ex);
        }
        Budget budget = locked(id);
        String paymentId = payment.get("id").asText();
        if (!"approved".equals(payment.get("status").asText())) {
            if (paymentId.equals(budget.getPaymentId())) {
                throw conflict("Pagamento confirmado mudou de estado; conciliacao manual necessaria");
            }
            return;
        }
        if (!"BRL".equals(payment.get("currency_id").asText())
                || !payment.get("transaction_amount").isNumber()
                || budget.getAmount().compareTo(payment.get("transaction_amount").decimalValue()) != 0) {
            throw conflict("Valor ou moeda do pagamento nao correspondem ao orcamento");
        }
        if (budget.getStatus() == Budget.Status.PAID && paymentId.equals(budget.getPaymentId())) return;
        if (budget.getStatus() != Budget.Status.APPROVED) throw conflict("Pagamento nao corresponde a um orcamento aprovado");
        if (budget.getPreferenceId() == null) throw conflict("Orcamento nao possui checkout");
        budget.setPaymentId(paymentId);
        budget.setStatus(Budget.Status.PAID);
        outbox.save(new OutboxEvent(budget, "PAYMENT_APPROVED"));
    }

    private Budget locked(UUID id) {
        return budgets.findLockedById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Orcamento nao encontrado"));
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
