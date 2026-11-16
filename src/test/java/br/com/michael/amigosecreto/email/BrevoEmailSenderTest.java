package br.com.michael.amigosecreto.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Contrato do adaptador da API do Brevo, sem rede: URL, header de autenticação,
 * corpo idempotente e tradução dos status de resposta.
 *
 * <p>O cliente é montado como em produção (base URL e header {@code api-key}) e
 * o {@link MockRestServiceServer} intercepta a requisição. O construtor
 * package-private evita que o teste dependa de um servidor real.
 */
class BrevoEmailSenderTest {

    private static final String BASE_URL = "https://api.brevo.com/v3";
    private static final String API_KEY = "xkeysib-test-key";
    private static final UUID IDEMPOTENCY_KEY =
            UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void acceptedResponseKeepsProviderMessageId() {
        RestClient.Builder builder = restClientBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BrevoEmailSender sender = new BrevoEmailSender(properties(true), builder.build());

        server.expect(requestTo(BASE_URL + "/smtp/email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", API_KEY))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"messageId\":\"<abc@smtp-relay.mailin.fr>\"}"));

        EmailSendResult result = sender.send(message());

        assertThat(result.accepted()).isTrue();
        assertThat(result.providerMessageId()).contains("abc");
        assertThat(result.errorCode()).isNull();
        assertThat(result.errorMessage()).isNull();
        server.verify();
    }

    @Test
    void payloadCarriesSenderRecipientContentTagsAndIdempotencyHeaderInBody() {
        RestClient.Builder builder = restClientBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BrevoEmailSender sender = new BrevoEmailSender(properties(true), builder.build());

        server.expect(requestTo(BASE_URL + "/smtp/email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.sender.email").value("no-reply@mail.example.com"))
                .andExpect(jsonPath("$.sender.name").value("Amigo Secreto"))
                .andExpect(jsonPath("$.to[0].email").value("maria@example.com"))
                .andExpect(jsonPath("$.to[0].name").value("Maria"))
                .andExpect(jsonPath("$.to[0].removeTrackingPixel").value(true))
                .andExpect(jsonPath("$.replyTo.email").value("contato@example.com"))
                .andExpect(jsonPath("$.subject").value("Seu resultado do amigo secreto"))
                .andExpect(jsonPath("$.htmlContent").value("<p>Olá, Maria.</p>"))
                .andExpect(jsonPath("$.textContent").value("Olá, Maria."))
                .andExpect(jsonPath("$.tags[0]").value("amigo-secreto"))
                .andExpect(jsonPath("$.tags[1]").value("draw-1"))
                .andExpect(jsonPath("$['headers']['idempotencyKey']")
                        .value(IDEMPOTENCY_KEY.toString()))
                .andExpect(jsonPath("$['headers']['X-Sib-Sandbox']").value("drop"))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"messageId\":\"<payload@smtp-relay.mailin.fr>\"}"));

        EmailSendResult result = sender.send(message());

        assertThat(result.accepted()).isTrue();
        server.verify();
    }

    @Test
    void sandboxHeaderIsAbsentWhenSandboxIsDisabled() {
        RestClient.Builder builder = restClientBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BrevoEmailSender sender = new BrevoEmailSender(properties(false), builder.build());

        server.expect(requestTo(BASE_URL + "/smtp/email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$['headers']['idempotencyKey']")
                        .value(IDEMPOTENCY_KEY.toString()))
                .andExpect(jsonPath("$['headers']['X-Sib-Sandbox']").doesNotExist())
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"messageId\":\"<prod@smtp-relay.mailin.fr>\"}"));

        EmailSendResult result = sender.send(message());

        assertThat(result.accepted()).isTrue();
        server.verify();
    }

    @Test
    void duplicateParameterIsTreatedAsAlreadyAccepted() {
        RestClient.Builder builder = restClientBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BrevoEmailSender sender = new BrevoEmailSender(properties(true), builder.build());

        server.expect(requestTo(BASE_URL + "/smtp/email"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"duplicate_parameter\","
                                + "\"message\":\"duplicate idempotency key\"}"));

        EmailSendResult result = sender.send(message());

        assertThat(result.accepted()).isTrue();
        assertThat(result.providerMessageId()).isNull();
        server.verify();
    }

    @Test
    void tooManyRequestsIsRejectedAsRetryable() {
        RestClient.Builder builder = restClientBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BrevoEmailSender sender = new BrevoEmailSender(properties(true), builder.build());

        server.expect(requestTo(BASE_URL + "/smtp/email"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"too_many_requests\",\"message\":\"Rate limit\"}"));

        EmailSendResult result = sender.send(message());

        assertThat(result.accepted()).isFalse();
        assertThat(result.retryable()).isTrue();
        assertThat(result.errorCode()).isEqualTo("too_many_requests");
        assertThat(result.errorMessage()).contains("Brevo HTTP 429");
        server.verify();
    }

    @Test
    void unauthorizedIsRejectedWithoutRetry() {
        RestClient.Builder builder = restClientBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BrevoEmailSender sender = new BrevoEmailSender(properties(true), builder.build());

        server.expect(requestTo(BASE_URL + "/smtp/email"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"unauthorized\",\"message\":\"Key not found\"}"));

        EmailSendResult result = sender.send(message());

        assertThat(result.accepted()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.errorCode()).isEqualTo("unauthorized");
        assertThat(result.errorMessage()).contains("Brevo HTTP 401");
        server.verify();
    }

    @Test
    void missingApiKeyFailsFastWhenEmailIsEnabled() {
        EmailProperties properties = new EmailProperties(
                true,
                "no-reply@mail.example.com",
                "Amigo Secreto",
                null,
                new EmailProperties.Brevo("", false));

        assertThatThrownBy(() -> new BrevoEmailSender(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BREVO_API_KEY");
    }

    @Test
    void missingFromEmailFailsFastWhenEmailIsEnabled() {
        EmailProperties properties = new EmailProperties(
                true,
                "",
                "Amigo Secreto",
                null,
                new EmailProperties.Brevo(API_KEY, false));

        assertThatThrownBy(() -> new BrevoEmailSender(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("from-email");
    }

    private static RestClient.Builder restClientBuilder() {
        return RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("api-key", API_KEY);
    }

    private static EmailProperties properties(boolean sandbox) {
        return new EmailProperties(
                true,
                "no-reply@mail.example.com",
                "Amigo Secreto",
                "contato@example.com",
                new EmailProperties.Brevo(API_KEY, sandbox));
    }

    private static EmailMessage message() {
        return new EmailMessage(
                "maria@example.com",
                "Maria",
                "Seu resultado do amigo secreto",
                "<p>Olá, Maria.</p>",
                "Olá, Maria.",
                IDEMPOTENCY_KEY,
                List.of("amigo-secreto", "draw-1"));
    }
}
