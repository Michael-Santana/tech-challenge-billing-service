package com.tech.challenger.execution.service.demo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class WebhookVerifier {
    private final String secret;

    WebhookVerifier(@Value("${app.mercado-pago.webhook-secret}") String secret) {
        this.secret = secret;
    }

    void verify(String dataId, String requestId, String signature) {
        if (secret.isBlank()) throw new IllegalStateException("MERCADO_PAGO_WEBHOOK_SECRET nao configurado");
        if (dataId == null || requestId == null || signature == null) throw unauthorized();
        String timestamp = null;
        String hash = null;
        for (String part : signature.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && "ts".equals(pair[0])) timestamp = pair[1];
            if (pair.length == 2 && "v1".equals(pair[0])) hash = pair[1];
        }
        if (timestamp == null || !timestamp.matches("[0-9]+") || hash == null || !hash.matches("[0-9a-fA-F]{64}")) throw unauthorized();
        String manifest = "id:" + dataId.toLowerCase(Locale.ROOT) + ";request-id:" + requestId + ";ts:" + timestamp + ";";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(hash))) throw unauthorized();
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Falha ao validar assinatura Mercado Pago", ex);
        }
    }

    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Assinatura invalida");
    }
}
