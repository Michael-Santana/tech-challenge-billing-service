package com.tech.challenger.execution.service.demo;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RemoteClientsTest {
    private HttpServer server;
    private RemoteClients remote;
    private final AtomicReference<String> status = new AtomicReference<>("FINALIZADA");
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> update = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String path = exchange.getRequestURI().getPath();
            String result;
            if (path.equals("/checkout/preferences")) {
                assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                        .contains("\"currency_id\":\"BRL\"", "\"external_reference\"");
                result = "{\"id\":\"pref\",\"init_point\":\"https://checkout\"}";
            } else if (path.equals("/v1/payments/123")) {
                result = "{\"id\":123,\"status\":\"approved\"}";
            } else if (path.endsWith("/status")) {
                update.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                result = "{}";
            } else {
                result = "{\"status\":\"" + status.get() + "\"}";
            }
            byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        remote = new RemoteClients(base, "private-token", "https://example.com/webhook", base);
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void callsMercadoPagoWithCredentialsAndCreatesCheckout() {
        Budget budget = new Budget(1L, "Reparo", new BigDecimal("100.00"));
        assertThat(remote.createPreference(budget).path("id").asText()).isEqualTo("pref");
        assertThat(authorization.get()).isEqualTo("Bearer private-token");
        assertThat(remote.payment("123").path("status").asText()).isEqualTo("approved");
        assertThatThrownBy(() -> remote.payment("../other")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deliversOnlyFinalizedOrdersAndTreatsDeliveredAsIdempotent() {
        remote.deliverOrder(1L);
        assertThat(update.get()).contains("\"status\":\"ENTREGUE\"");
        update.set(null);
        status.set("ENTREGUE");
        remote.deliverOrder(1L);
        assertThat(update.get()).isNull();
        status.set("CANCELADA");
        assertThatThrownBy(() -> remote.deliverOrder(1L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requiresConfiguredCredentialsAndHttpsWebhook() {
        Budget budget = new Budget(1L, "Reparo", BigDecimal.TEN);
        assertThatThrownBy(() -> new RemoteClients("http://localhost", "", "", "http://localhost")
                .payment("123")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RemoteClients("http://localhost", "token", "http://example.com", "http://localhost")
                .createPreference(budget)).isInstanceOf(IllegalStateException.class);
    }
}
