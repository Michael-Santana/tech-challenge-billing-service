package com.tech.challenger.execution.service.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:billing;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.api-key=test-api-key",
        "app.mercado-pago.webhook-secret=test-secret"
})
@AutoConfigureMockMvc
class BillingFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OutboxRepository outbox;
    @MockBean RemoteClients remote;
    @MockBean OutboxDispatcher dispatcher;

    @Test
    void approvedBudgetAndAuthenticatedPaymentProduceDurableEvent() throws Exception {
        when(remote.orderStatus(91L)).thenReturn("EM_DIAGNOSTICO");
        String created = mvc.perform(post("/budgets").header("X-API-Key", "test-api-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":91,\"description\":\"Reparo\",\"amount\":150.00}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).get("id").asText();
        mvc.perform(post("/budgets/{id}/decision", id).header("X-API-Key", "test-api-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        when(remote.createPreference(any())).thenReturn(json.readTree("{\"id\":\"pref-1\",\"init_point\":\"https://checkout.example\"}"));
        mvc.perform(post("/budgets/{id}/checkout", id).header("X-API-Key", "test-api-key")).andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutUrl").value("https://checkout.example"));
        when(remote.payment("123")).thenReturn(json.readTree("{\"id\":123,\"external_reference\":\"" + id
                + "\",\"status\":\"approved\",\"transaction_amount\":150.00,\"currency_id\":\"BRL\"}"));
        String manifest = "id:123;request-id:req-1;ts:123456;";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = "ts=123456,v1=" + HexFormat.of().formatHex(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/webhooks/mercado-pago?data.id=123").header("x-request-id", "req-1")
                            .header("x-signature", signature).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"payment\",\"data\":{\"id\":\"123\"}}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/budgets/{id}", id).header("X-API-Key", "test-api-key")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID")).andExpect(jsonPath("$.paymentId").value("123"));
        assertThat(outbox.findAll().stream().filter(e -> e.getEventType().equals("PAYMENT_APPROVED"))).hasSize(1);
        verify(remote, times(1)).createPreference(any());
    }

    @Test
    void invalidWebhookSignatureIsRejectedBeforePaymentLookup() throws Exception {
        mvc.perform(post("/webhooks/mercado-pago?data.id=999").header("x-request-id", "bad")
                        .header("x-signature", "ts=1,v1=" + "0".repeat(64)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"payment\",\"data\":{\"id\":\"999\"}}"))
                .andExpect(status().isUnauthorized());
        verify(remote, never()).payment("999");
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        mvc.perform(post("/budgets").header("X-API-Key", "test-api-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":0,\"description\":\"\",\"amount\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void budgetApiRequiresAuthentication() throws Exception {
        mvc.perform(get("/budgets/00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isUnauthorized());
    }
}
