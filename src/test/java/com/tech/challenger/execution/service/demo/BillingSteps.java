package com.tech.challenger.execution.service.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.cucumber.spring.CucumberContextConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@CucumberContextConfiguration
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:bdd;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "app.api-key=bdd-key", "app.mercado-pago.webhook-secret=bdd-secret"
})
@AutoConfigureMockMvc
public class BillingSteps {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OutboxRepository outbox;
    @MockBean RemoteClients remote;
    @MockBean OutboxDispatcher dispatcher;
    private String budgetId;

    @Given("uma OS em diagnostico")
    public void orderInDiagnosis() {
        when(remote.orderStatus(101L)).thenReturn("EM_DIAGNOSTICO");
    }

    @When("um orcamento e criado e aprovado")
    public void createAndApprove() throws Exception {
        String response = mvc.perform(post("/budgets").header("X-API-Key", "bdd-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":101,\"description\":\"Reparo\",\"amount\":100.00}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        budgetId = json.readTree(response).path("id").asText();
        mvc.perform(post("/budgets/{id}/decision", budgetId).header("X-API-Key", "bdd-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(status().isOk());
        when(remote.createPreference(any())).thenReturn(json.readTree("{\"id\":\"pref-bdd\",\"init_point\":\"https://checkout.example\"}"));
        mvc.perform(post("/budgets/{id}/checkout", budgetId).header("X-API-Key", "bdd-key"))
                .andExpect(status().isOk());
    }

    @When("o Mercado Pago confirma o pagamento")
    public void confirmPayment() throws Exception {
        when(remote.payment("1001")).thenReturn(json.readTree("{\"id\":1001,\"external_reference\":\""
                + budgetId + "\",\"status\":\"approved\",\"transaction_amount\":100.00,\"currency_id\":\"BRL\"}"));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("bdd-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal("id:1001;request-id:bdd;ts:1;".getBytes(StandardCharsets.UTF_8)));
        mvc.perform(post("/webhooks/mercado-pago?data.id=1001").header("x-request-id", "bdd")
                        .header("x-signature", "ts=1,v1=" + signature).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"payment\",\"data\":{\"id\":\"1001\"}}"))
                .andExpect(status().isOk());
    }

    @Then("a cobranca fica paga e um evento de pagamento e registrado")
    public void paymentRecorded() throws Exception {
        mvc.perform(get("/budgets/{id}", budgetId).header("X-API-Key", "bdd-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        assertThat(outbox.findAll().stream().filter(e -> e.getEventType().equals("PAYMENT_APPROVED"))).hasSize(1);
    }
}
