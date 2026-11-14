package br.com.michael.amigosecreto.email;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Adaptador da API transacional do Brevo.
 *
 * <p>O {@code messageId} devolvido é guardado na notificação para rastreio. O
 * sandbox fica atrás de configuração e nunca é ativado em produção.
 */
public class BrevoEmailSender implements EmailSender {

    private static final String BASE_URL = "https://api.brevo.com/v3";
    private static final Pattern MESSAGE_ID =
            Pattern.compile("\"messageId\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern CODE =
            Pattern.compile("\"code\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern MESSAGE =
            Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*)\"");

    private final EmailProperties properties;
    private final RestClient client;

    public BrevoEmailSender(EmailProperties properties) {
        this(properties, buildClient(properties));
    }

    BrevoEmailSender(EmailProperties properties, RestClient client) {
        this.properties = properties;
        this.client = client;
        validate(properties);
    }

    private static RestClient buildClient(EmailProperties properties) {
        validate(properties);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));

        return RestClient.builder()
                .baseUrl(BASE_URL)
                .requestFactory(requestFactory)
                .defaultHeader("api-key", properties.brevo().apiKey())
                .build();
    }

    @Override
    public EmailSendResult send(EmailMessage message) {
        Map<String, Object> payload = payload(message);

        return client.post()
                .uri("/smtp/email")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    String body = readBody(response.getBody());

                    if (status == 201 || status == 202) {
                        return EmailSendResult.accepted(extract(MESSAGE_ID, body));
                    }

                    String code = extract(CODE, body);
                    String error = extract(MESSAGE, body);
                    if (status == 400 && "duplicate_parameter".equals(code)) {
                        // A requisição anterior chegou; a idempotência evitou o duplicado.
                        return EmailSendResult.accepted(null);
                    }

                    boolean retryable = status == 429 || status >= 500;
                    return EmailSendResult.rejected(
                            code,
                            "Brevo HTTP " + status + (error == null ? "" : ": " + error),
                            retryable);
                });
    }

    private Map<String, Object> payload(EmailMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();

        Map<String, Object> sender = new LinkedHashMap<>();
        sender.put("email", properties.fromEmail());
        sender.put("name", properties.fromName());
        payload.put("sender", sender);

        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("email", message.toEmail());
        if (message.toName() != null && !message.toName().isBlank()) {
            recipient.put("name", message.toName());
        }
        recipient.put("removeTrackingPixel", true);
        payload.put("to", List.of(recipient));

        if (properties.replyTo() != null && !properties.replyTo().isBlank()) {
            payload.put("replyTo", Map.of("email", properties.replyTo()));
        }

        payload.put("subject", message.subject());
        payload.put("htmlContent", message.html());
        payload.put("textContent", message.text());
        payload.put("tags", message.tags());

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("idempotencyKey", message.idempotencyKey().toString());
        if (properties.brevo().sandbox()) {
            headers.put("X-Sib-Sandbox", "drop");
        }
        payload.put("headers", headers);
        return payload;
    }

    private static void validate(EmailProperties properties) {
        if (properties.fromEmail() == null || properties.fromEmail().isBlank()) {
            throw new IllegalStateException("app.email.from-email é obrigatório quando o e-mail está habilitado.");
        }
        if (properties.brevo() == null
                || properties.brevo().apiKey() == null
                || properties.brevo().apiKey().isBlank()) {
            throw new IllegalStateException("BREVO_API_KEY é obrigatória quando o e-mail está habilitado.");
        }
    }

    private static String readBody(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String extract(Pattern pattern, String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1)
                .replace("\\u003c", "<")
                .replace("\\u003e", ">")
                .replace("\\u002F", "/");
    }
}
