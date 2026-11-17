package com.tech.challenger.execution.service.demo;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/budgets")
class BillingController {
    record CreateBudget(@NotNull @Positive Long orderId, @NotBlank @Size(max = 255) String description,
                        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount) {}
    record Decision(@NotNull Boolean approved) {}

    private final BillingService service;
    BillingController(BillingService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Budget create(@Valid @RequestBody CreateBudget request) {
        return service.create(request.orderId(), request.description(), request.amount());
    }

    @GetMapping("/{id}")
    Budget get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping("/{id}/decision")
    Budget decide(@PathVariable UUID id, @Valid @RequestBody Decision request) {
        return service.decide(id, request.approved());
    }

    @PostMapping("/{id}/checkout")
    Budget checkout(@PathVariable UUID id) { return service.checkout(id); }
}

@RestController
@RequestMapping("/webhooks/mercado-pago")
class MercadoPagoWebhookController {
    private final WebhookVerifier verifier;
    private final RemoteClients remote;
    private final BillingService service;

    MercadoPagoWebhookController(WebhookVerifier verifier, RemoteClients remote, BillingService service) {
        this.verifier = verifier;
        this.remote = remote;
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    void payment(@RequestParam(name = "data.id", required = false) String dataId,
                 @RequestHeader(name = "x-request-id", required = false) String requestId,
                 @RequestHeader(name = "x-signature", required = false) String signature,
                 @RequestBody JsonNode body) {
        verifier.verify(dataId, requestId, signature);
        if (!"payment".equals(body.path("type").asText())) return;
        if (!dataId.equals(body.path("data").path("id").asText())) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Identificador divergente");
        }
        JsonNode payment = remote.payment(dataId);
        if (payment == null || !dataId.equals(payment.path("id").asText())) {
            throw new IllegalStateException("Pagamento retornado nao corresponde a notificacao");
        }
        service.confirmPayment(payment);
    }
}
