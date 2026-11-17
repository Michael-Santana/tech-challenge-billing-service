package com.tech.challenger.execution.service.demo;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class RemoteClients {
    private final RestClient mercadoPago;
    private final RestClient os;
    private final String accessToken;
    private final String notificationUrl;

    RemoteClients(@Value("${app.mercado-pago.base-url}") String mercadoPagoUrl,
                  @Value("${app.mercado-pago.access-token}") String accessToken,
                  @Value("${app.mercado-pago.notification-url}") String notificationUrl,
                  @Value("${app.os-url}") String osUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.mercadoPago = RestClient.builder().baseUrl(mercadoPagoUrl).requestFactory(factory).build();
        this.os = RestClient.builder().baseUrl(osUrl).requestFactory(factory).build();
        this.accessToken = accessToken;
        this.notificationUrl = notificationUrl;
    }

    JsonNode createPreference(Budget budget) {
        requireCredentials();
        if (notificationUrl.isBlank() || !notificationUrl.startsWith("https://")) {
            throw new IllegalStateException("MERCADO_PAGO_NOTIFICATION_URL deve ser HTTPS");
        }
        Map<String, Object> body = Map.of(
                "external_reference", budget.getId().toString(),
                "notification_url", notificationUrl,
                "items", List.of(Map.of("title", budget.getDescription(), "quantity", 1,
                        "currency_id", "BRL", "unit_price", budget.getAmount())));
        return mercadoPago.post().uri("/checkout/preferences")
                .headers(h -> h.setBearerAuth(accessToken)).body(body).retrieve().body(JsonNode.class);
    }

    JsonNode payment(String paymentId) {
        requireCredentials();
        if (!paymentId.matches("[0-9]+")) {
            throw new IllegalArgumentException("Identificador de pagamento invalido");
        }
        return mercadoPago.get().uri("/v1/payments/{id}", paymentId)
                .headers(h -> h.setBearerAuth(accessToken)).retrieve().body(JsonNode.class);
    }

    String orderStatus(Long orderId) {
        JsonNode response = os.get().uri("/orders/{id}", orderId).retrieve().body(JsonNode.class);
        if (response == null || !response.hasNonNull("status")) {
            throw new IllegalStateException("Resposta invalida do OS Service");
        }
        return response.get("status").asText();
    }

    void deliverOrder(Long orderId) {
        String current = orderStatus(orderId);
        if ("ENTREGUE".equals(current)) return;
        if (!"FINALIZADA".equals(current)) {
            throw new IllegalStateException("OS " + orderId + " ainda nao esta FINALIZADA: " + current);
        }
        os.put().uri("/orders/{id}/status", orderId)
                .body(Map.of("status", "ENTREGUE", "description", "Pagamento confirmado pelo Billing Service"))
                .retrieve().toBodilessEntity();
    }

    private void requireCredentials() {
        if (accessToken.isBlank()) throw new IllegalStateException("MERCADO_PAGO_ACCESS_TOKEN nao configurado");
    }
}
