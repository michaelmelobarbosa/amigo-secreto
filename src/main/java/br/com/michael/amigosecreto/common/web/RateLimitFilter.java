package br.com.michael.amigosecreto.common.web;

import br.com.michael.amigosecreto.common.RateLimitProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate limiting em memória, por IP e categoria de rota (ver
 * {@code docs/SECURITY.md}).
 *
 * <p>Roda depois do {@code RemoteIpValve} do Tomcat ({@code server.forward-headers-strategy=native}),
 * então {@link HttpServletRequest#getRemoteAddr()} já contém o IP real do cliente
 * que o Caddy encaminhou em {@code X-Forwarded-For} (ignorando {@code Forwarded},
 * {@code X-Forwarded-Host} e {@code X-Forwarded-Prefix}). O IP nunca é persistido
 * nem registrado em log: existe apenas como chave do mapa em memória.
 *
 * <p>Janela fixa com contador por chave; o mapa é um LRU limitado por
 * {@code max-keys}. Não é um mecanismo contra ataque distribuído — o objetivo é
 * elevar o custo do abuso casual sem atrapalhar NAT compartilhado.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Pattern JOIN_PATH = Pattern.compile("^/join/[^/]+$");
    private static final Pattern DRAW_PATH = Pattern.compile("^/admin/[^/]+/(?:draw|redraw)$");
    private static final Pattern EMAIL_LINKS_PATH = Pattern.compile("^/admin/[^/]+/links/email$");
    private static final Pattern EMAIL_RESEND_PATH =
            Pattern.compile("^/admin/[^/]+/participants/[^/]+/email/resend$");
    private static final Pattern WISHLIST_PATH = Pattern.compile("^/p/[^/]+/wishlist$");

    private static final String TOO_MANY_REQUESTS_BODY = """
            <!DOCTYPE html>
            <html lang="pt-BR">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <meta name="color-scheme" content="light dark">
              <meta name="theme-color" content="#f4f6f8">
              <title>Muitas tentativas · Amigo Secreto</title>
              <link rel="icon" type="image/svg+xml" href="/images/gift.svg">
              <link rel="stylesheet" href="/css/app.css">
            </head>
            <body>
              <header class="site-header">
                <div class="container header-content">
                  <a class="brand" href="/">
                    <img class="brand-icon" src="/images/gift.svg" alt="" width="32" height="32">
                    <span>Amigo Secreto</span>
                  </a>
                  <nav aria-label="Navegação principal">
                    <a href="/">Início</a>
                  </nav>
                </div>
              </header>
              <main class="container content" id="conteudo">
                <section class="notice error-card" aria-labelledby="page-title">
                  <span class="badge badge-warning">Limite temporário · 429</span>
                  <h1 id="page-title">Muitas tentativas em pouco tempo</h1>
                  <p class="muted">Aguarde alguns minutos antes de enviar esta ação novamente.</p>
                  <a class="button" href="/">Voltar ao início</a>
                </section>
              </main>
              <footer class="site-footer">
                <div class="container footer-content">
                  <p>Organize seu amigo secreto sem cadastro e com sigilo.</p>
                </div>
              </footer>
            </body>
            </html>
            """;

    private final RateLimitProperties properties;
    private final Clock clock;
    private final Map<String, Window> windows;

    @Autowired
    public RateLimitFilter(RateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    RateLimitFilter(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.windows = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Window> eldest) {
                return size() > properties.maxKeys();
            }
        };
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if (!properties.enabled() || !isLimitedMethod(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        Category category = categoryOf(pathOf(request));
        if (category == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Decision decision = register(category, normalizeIp(request.getRemoteAddr()));
        if (decision.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }

        tooManyRequests(response, decision.retryAfterSeconds());
    }

    private static boolean isLimitedMethod(String method) {
        return "POST".equalsIgnoreCase(method);
    }

    private static String pathOf(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path;
    }

    private static Category categoryOf(String path) {
        if ("/events".equals(path)) {
            return Category.EVENT_CREATE;
        }
        if (JOIN_PATH.matcher(path).matches()) {
            return Category.JOIN;
        }
        if (DRAW_PATH.matcher(path).matches()) {
            return Category.DRAW;
        }
        if (EMAIL_LINKS_PATH.matcher(path).matches()) {
            return Category.EMAIL_LINKS;
        }
        if (EMAIL_RESEND_PATH.matcher(path).matches()) {
            return Category.EMAIL_RESEND;
        }
        if (WISHLIST_PATH.matcher(path).matches()) {
            return Category.WISHLIST;
        }
        return null;
    }

    private Decision register(Category category, String ip) {
        if (ip == null) {
            return Decision.permit();
        }

        long now = clock.millis();
        long windowMillis = Math.max(1L, category.window(properties).toMillis());
        int limit = category.limit(properties);
        String key = category + "|" + ip;

        synchronized (windows) {
            Window window = windows.get(key);
            if (window == null || now >= window.resetAt()) {
                windows.put(key, new Window(1, now + windowMillis));
                return Decision.permit();
            }

            window.increment();
            if (window.count() <= limit) {
                return Decision.permit();
            }

            long remainingMillis = Math.max(1L, window.resetAt() - now);
            return Decision.deny((remainingMillis + 999L) / 1000L);
        }
    }

    /**
     * Normaliza o IP para a chave do balde. IPv4 usa o endereço completo; IPv6
     * é reduzido ao prefixo /64 (primeiros quatro grupos), como decidido no
     * plano, para reduzir a troca de endereço dentro do mesmo prefixo.
     */
    static String normalizeIp(String raw) {
        if (raw == null) {
            return "unknown";
        }

        String value = raw.trim();
        if (value.isEmpty()) {
            return "unknown";
        }

        if (value.charAt(0) == '[') {
            int end = value.indexOf(']');
            if (end > 0) {
                value = value.substring(1, end);
            }
        }

        if (value.indexOf(':') >= 0) {
            int zone = value.indexOf('%');
            if (zone >= 0) {
                value = value.substring(0, zone);
            }
        }

        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("::ffff:") && lower.indexOf('.') > 0) {
            return lower.substring("::ffff:".length());
        }
        if (lower.indexOf(':') < 0) {
            return lower;
        }

        try {
            byte[] address = InetAddress.ofLiteral(lower).getAddress();
            if (address.length == 4) {
                return dotted(address);
            }
            if (address.length == 16) {
                StringBuilder prefix = new StringBuilder(19);
                for (int i = 0; i < 8; i += 2) {
                    if (i > 0) {
                        prefix.append(':');
                    }
                    prefix.append(hex(address[i])).append(hex(address[i + 1]));
                }
                return prefix.toString();
            }
        } catch (IllegalArgumentException ignored) {
            // Valor que não é um literal de IP: usa como veio sem consultar DNS.
        }

        return lower;
    }

    private static String dotted(byte[] address) {
        return (address[0] & 0xff) + "."
                + (address[1] & 0xff) + "."
                + (address[2] & 0xff) + "."
                + (address[3] & 0xff);
    }

    private static String hex(byte value) {
        return Character.toString(Character.forDigit((value >> 4) & 0xf, 16))
                + Character.toString(Character.forDigit(value & 0xf, 16));
    }

    private static void tooManyRequests(HttpServletResponse response, long retryAfterSeconds)
            throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write(TOO_MANY_REQUESTS_BODY);
        response.getWriter().flush();
    }

    private enum Category {
        EVENT_CREATE,
        JOIN,
        DRAW,
        EMAIL_LINKS,
        EMAIL_RESEND,
        WISHLIST;

        int limit(RateLimitProperties properties) {
            return switch (this) {
                case EVENT_CREATE -> properties.eventCreatePerWindow();
                case JOIN -> properties.joinPerWindow();
                case DRAW -> properties.drawPerWindow();
                case EMAIL_LINKS -> properties.emailLinksPerWindow();
                case EMAIL_RESEND -> properties.emailResendPerWindow();
                case WISHLIST -> properties.wishlistPerWindow();
            };
        }

        Duration window(RateLimitProperties properties) {
            return switch (this) {
                case DRAW -> properties.drawWindow();
                case EMAIL_LINKS, EMAIL_RESEND -> properties.emailWindow();
                default -> properties.window();
            };
        }
    }

    private static final class Window {

        private int count;
        private final long resetAt;

        private Window(int count, long resetAt) {
            this.count = count;
            this.resetAt = resetAt;
        }

        private int count() {
            return count;
        }

        private long resetAt() {
            return resetAt;
        }

        private void increment() {
            if (count < Integer.MAX_VALUE) {
                count++;
            }
        }
    }

    private record Decision(boolean allowed, long retryAfterSeconds) {

        private static Decision permit() {
            return new Decision(true, 0);
        }

        private static Decision deny(long retryAfterSeconds) {
            return new Decision(false, retryAfterSeconds);
        }
    }
}
