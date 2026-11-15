package br.com.michael.amigosecreto.common;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração do rate limiting em memória (ver {@code docs/RATE-LIMITING.md}).
 *
 * <p>Os limites são generosos de propósito: uma família atrás do mesmo IP
 * precisa criar um evento e várias pessoas entrarem em sequência. Categorias
 * têm contadores separados, e o limite de sorteio usa uma janela própria
 * (uma hora) porque o abuso relevante ali é diferente de inscrição.
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        int eventCreatePerWindow,
        int joinPerWindow,
        int drawPerWindow,
        int wishlistPerWindow,
        Duration window,
        Duration drawWindow,
        int maxKeys) {

    public RateLimitProperties {
        eventCreatePerWindow = positive(eventCreatePerWindow, 5);
        joinPerWindow = positive(joinPerWindow, 30);
        drawPerWindow = positive(drawPerWindow, 10);
        wishlistPerWindow = positive(wishlistPerWindow, 30);
        window = positive(window, Duration.ofMinutes(10));
        drawWindow = positive(drawWindow, Duration.ofHours(1));
        maxKeys = positive(maxKeys, 10_000);
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value != null && !value.isZero() && !value.isNegative() ? value : fallback;
    }
}
