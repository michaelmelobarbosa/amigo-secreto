package br.com.michael.amigosecreto.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * P11: com o cliente sem cookie, a sessão não pode ser reescrita na URL
 * ({@code ;jsessionid=...}). A configuração {@code server.servlet.session.tracking-modes=cookie}
 * desliga o URL rewriting; o cookie continua sendo emitido.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SessionTrackingIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void doesNotRewriteUrlsWithJsessionIdWhenClientHasNoCookie() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Set-Cookie")).isPresent();
        assertThat(response.body()).doesNotContain(";jsessionid");
    }
}
