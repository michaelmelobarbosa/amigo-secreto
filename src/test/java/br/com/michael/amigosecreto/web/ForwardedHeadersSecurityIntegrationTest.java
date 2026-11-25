package br.com.michael.amigosecreto.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Garante que {@code server.forward-headers-strategy=native} lê apenas
 * {@code X-Forwarded-For} e {@code X-Forwarded-Proto} de proxies internos e
 * ignora {@code Forwarded}, {@code X-Forwarded-Host} e {@code X-Forwarded-Prefix}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "app.rate-limit.enabled=true",
    "app.rate-limit.event-create-per-window=1"
})
class ForwardedHeadersSecurityIntegrationTest {

    private static final Pattern CSRF_PATTERN =
            Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");

    @LocalServerPort
    private int port;

    @Test
    void ignoresSpoofedForwardedHostAndPrefixHeaders() throws Exception {
        HttpClient client = newClient();
        String csrf = fetchCsrf(client);
        List<String> createdAdminTokens = new ArrayList<>();

        try {
            HttpResponse<String> first = postCreateEvent(
                    client,
                    csrf,
                    "Evento Seguranca",
                    "Org",
                    "X-Forwarded-For", "203.0.113.50",
                    "Forwarded", "for=198.51.100.1;host=evil.example;proto=http",
                    "X-Forwarded-Host", "evil.example",
                    "X-Forwarded-Prefix", "/evil");

            assertThat(first.statusCode()).isEqualTo(302);
            String location = first.headers().firstValue("Location").orElse("");
            assertThat(location)
                    .startsWith("http://localhost:" + port + "/admin/")
                    .doesNotContain("evil.example")
                    .doesNotContain("/evil");
            createdAdminTokens.add(extractAdminToken(location));

            // Mesmo X-Forwarded-For, mudando apenas o header Forwarded (RFC 7239): precisa continuar bloqueado.
            HttpResponse<String> second = postCreateEvent(
                    client,
                    csrf,
                    "Evento Seguranca 2",
                    "Org",
                    "X-Forwarded-For", "203.0.113.50",
                    "Forwarded", "for=198.51.100.2;host=evil.example;proto=http",
                    "X-Forwarded-Host", "evil.example",
                    "X-Forwarded-Prefix", "/evil");

            assertThat(second.statusCode()).isEqualTo(429);
        } finally {
            deleteEvents(client, csrf, createdAdminTokens);
        }
    }

    @Test
    void trustsXForwardedForAndProtoFromInternalProxy() throws Exception {
        HttpClient client = newClient();
        String csrf = fetchCsrf(client);
        List<String> createdAdminTokens = new ArrayList<>();

        try {
            HttpResponse<String> first = postCreateEvent(
                    client,
                    csrf,
                    "Evento Proxy 1",
                    "Org",
                    "X-Forwarded-For", "203.0.113.60",
                    "X-Forwarded-Proto", "https");

            assertThat(first.statusCode()).isEqualTo(302);
            String location = first.headers().firstValue("Location").orElse("");
            assertThat(location).startsWith("https://localhost");
            assertThat(location).contains("/admin/");
            createdAdminTokens.add(extractAdminToken(location));

            HttpResponse<String> blocked = postCreateEvent(
                    client,
                    csrf,
                    "Evento Proxy 2",
                    "Org",
                    "X-Forwarded-For", "203.0.113.60",
                    "X-Forwarded-Proto", "https");

            assertThat(blocked.statusCode()).isEqualTo(429);

            HttpResponse<String> otherIp = postCreateEvent(
                    client,
                    csrf,
                    "Evento Proxy 3",
                    "Org",
                    "X-Forwarded-For", "203.0.113.61",
                    "X-Forwarded-Proto", "https");

            assertThat(otherIp.statusCode()).isEqualTo(302);
            createdAdminTokens.add(extractAdminToken(otherIp.headers().firstValue("Location").orElse("")));
        } finally {
            deleteEvents(client, csrf, createdAdminTokens);
        }
    }

    @Test
    void matrixParamsAreRejectedAndEncodedPathDoesNotBypassRateLimitInTomcat() throws Exception {
        HttpClient client = newClient();
        String csrf = fetchCsrf(client);
        List<String> createdAdminTokens = new ArrayList<>();

        try {
            HttpResponse<String> first = postToPath(
                    client,
                    "/events",
                    csrf,
                    "Evento Path Norm",
                    "Org",
                    "X-Forwarded-For", "203.0.113.70");
            assertThat(first.statusCode()).isEqualTo(302);
            String adminToken = extractAdminToken(first.headers().firstValue("Location").orElse(""));
            createdAdminTokens.add(adminToken);

            HttpResponse<String> matrixPost = postToPath(
                    client,
                    "/events;x=1",
                    csrf,
                    "Evento Bypass Matrix",
                    "Org",
                    "X-Forwarded-For", "203.0.113.70");
            assertThat(matrixPost.statusCode()).isEqualTo(404);

            HttpResponse<String> freshIpMatrixPost = postToPath(
                    client,
                    "/events;x=1",
                    csrf,
                    "Evento Bypass Matrix Fresh IP",
                    "Org",
                    "X-Forwarded-For", "203.0.113.71");
            assertThat(freshIpMatrixPost.statusCode()).isEqualTo(404);

            HttpResponse<String> encodedPost = postToPath(
                    client,
                    "/%65vents",
                    csrf,
                    "Evento Bypass Encoded",
                    "Org",
                    "X-Forwarded-For", "203.0.113.70");
            assertThat(encodedPost.statusCode()).isEqualTo(429);

            HttpRequest adminGet = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/admin;x=1/" + adminToken))
                    .GET()
                    .build();
            HttpResponse<String> adminResponse = client.send(adminGet, HttpResponse.BodyHandlers.ofString());
            assertThat(adminResponse.statusCode()).isEqualTo(404);
            assertThat(adminResponse.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(adminResponse.headers().firstValue("Referrer-Policy")).contains("no-referrer");
            assertThat(adminResponse.headers().firstValue("X-Robots-Tag")).contains("noindex, nofollow");
            assertThat(adminResponse.body()).doesNotContain(adminToken);
        } finally {
            deleteEvents(client, csrf, createdAdminTokens);
        }
    }

    private HttpClient newClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(new CookieManager())
                .build();
    }

    private String fetchCsrf(HttpClient client) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        Matcher matcher = CSRF_PATTERN.matcher(response.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private HttpResponse<String> postCreateEvent(
            HttpClient client,
            String csrf,
            String name,
            String organizerName,
            String... headers)
            throws Exception {
        return postToPath(client, "/events", csrf, name, organizerName, headers);
    }

    private HttpResponse<String> postToPath(
            HttpClient client,
            String path,
            String csrf,
            String name,
            String organizerName,
            String... headers)
            throws Exception {
        String body = "_csrf=" + encode(csrf)
                + "&name=" + encode(name)
                + "&organizerName=" + encode(organizerName);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }

        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void deleteEvents(HttpClient client, String csrf, List<String> adminTokens) throws Exception {
        for (String adminToken : adminTokens) {
            if (adminToken == null || adminToken.isBlank()) {
                continue;
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/admin/" + adminToken + "/delete"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + encode(csrf)))
                    .build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
        }
    }

    private static String extractAdminToken(String location) {
        int index = location.indexOf("/admin/");
        assertThat(index).isGreaterThanOrEqualTo(0);
        return location.substring(index + "/admin/".length());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
