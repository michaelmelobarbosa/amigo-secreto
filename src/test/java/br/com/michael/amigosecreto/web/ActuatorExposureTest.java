package br.com.michael.amigosecreto.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Garante no Tomcat real que o Actuator responde apenas na porta de management
 * e que rotas com parâmetros de matriz ({@code ;...}) retornam 404 na porta
 * principal da aplicação.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ActuatorExposureTest {

    @LocalServerPort
    private int appPort;

    @LocalManagementPort
    private int managementPort;

    @Test
    void actuatorIsNotExposedOnApplicationPortEvenWithMatrixParameters() throws Exception {
        assertThat(managementPort).isPositive().isNotEqualTo(appPort);

        HttpClient client = newClient();
        for (String path : new String[] {
                "/actuator",
                "/actuator/health",
                "/actuator;x",
                "/actuator;x/health",
                "/admin;x/adm_inexistente"
        }) {
            HttpResponse<String> response = get(client, appPort, path);
            assertThat(response.statusCode())
                    .as("GET %s na porta da aplicação", path)
                    .isEqualTo(404);
        }
    }

    @Test
    void healthEndpointRespondsOnlyOnManagementPort() throws Exception {
        assertThat(managementPort).isPositive().isNotEqualTo(appPort);

        HttpClient client = newClient();
        HttpResponse<String> response = get(client, managementPort, "/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("UP")
                .doesNotContain("components")
                .doesNotContain("details");
    }

    @Test
    void normalApplicationRoutesRemainAccessible() throws Exception {
        HttpClient client = newClient();

        HttpResponse<String> home = get(client, appPort, "/");
        assertThat(home.statusCode()).isEqualTo(200);

        HttpResponse<String> robots = get(client, appPort, "/robots.txt");
        assertThat(robots.statusCode()).isEqualTo(200);
        assertThat(robots.body()).contains("Disallow: /");
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static HttpResponse<String> get(HttpClient client, int port, String path)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
