package br.com.michael.amigosecreto.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.michael.amigosecreto.common.RateLimitProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-15T12:00:00Z"));

    @Test
    void allowsUpToLimitAndBlocksNextRequest() throws Exception {
        RateLimitFilter filter = filter(properties(true, 5, 30, 10, 30));

        for (int i = 0; i < 5; i++) {
            assertThat(call(filter, "POST", "/events", "203.0.113.10").passed()).isTrue();
        }

        Result blocked = call(filter, "POST", "/events", "203.0.113.10");

        assertThat(blocked.passed()).isFalse();
        assertThat(blocked.status()).isEqualTo(429);
        assertThat(blocked.retryAfter()).isEqualTo("600");
    }

    @Test
    void keepsSeparateBucketsPerIp() throws Exception {
        RateLimitFilter filter = filter(properties(true, 1, 30, 10, 30));

        assertThat(call(filter, "POST", "/events", "203.0.113.10").passed()).isTrue();
        assertThat(call(filter, "POST", "/events", "203.0.113.10").passed()).isFalse();
        assertThat(call(filter, "POST", "/events", "203.0.113.11").passed()).isTrue();
    }

    @Test
    void doesNotConsumeQuotaOnGetOrAssets() throws Exception {
        RateLimitFilter filter = filter(properties(true, 1, 30, 10, 30));

        for (int i = 0; i < 20; i++) {
            assertThat(call(filter, "GET", "/events", "203.0.113.10").passed()).isTrue();
            assertThat(call(filter, "GET", "/", "203.0.113.10").passed()).isTrue();
            assertThat(call(filter, "GET", "/css/app.css", "203.0.113.10").passed()).isTrue();
        }

        assertThat(call(filter, "POST", "/events", "203.0.113.10").passed()).isTrue();
    }

    @Test
    void keepsCategoriesIndependent() throws Exception {
        RateLimitFilter filter = filter(properties(true, 1, 1, 1, 1));

        assertThat(call(filter, "POST", "/events", "203.0.113.10").passed()).isTrue();
        assertThat(call(filter, "POST", "/events", "203.0.113.10").passed()).isFalse();
        assertThat(call(filter, "POST", "/join/inv_abc", "203.0.113.10").passed()).isTrue();
        assertThat(call(filter, "POST", "/join/inv_abc", "203.0.113.10").passed()).isFalse();
        assertThat(call(filter, "POST", "/admin/adm_abc/draw", "203.0.113.10").passed()).isTrue();
        assertThat(call(filter, "POST", "/admin/adm_abc/redraw", "203.0.113.10").passed()).isFalse();
        assertThat(call(filter, "POST", "/p/p_abc/wishlist", "203.0.113.10").passed()).isTrue();

        // Rotas fora do escopo não consomem cota.
        assertThat(call(filter, "POST", "/admin/adm_abc/participants", "203.0.113.10").passed()).isTrue();
    }

    @Test
    void usesDrawWindowForDrawAndRedraw() throws Exception {
        RateLimitFilter filter = filter(properties(true, 5, 30, 1, 30));

        assertThat(call(filter, "POST", "/admin/adm_abc/draw", "203.0.113.10").passed()).isTrue();

        // Passou da janela curta (10 min), mas o sorteio usa a janela de 1 hora.
        clock.advance(Duration.ofMinutes(11));
        assertThat(call(filter, "POST", "/admin/adm_abc/redraw", "203.0.113.10").passed()).isFalse();

        clock.advance(Duration.ofMinutes(50));
        assertThat(call(filter, "POST", "/admin/adm_abc/draw", "203.0.113.10").passed()).isTrue();
    }

    @Test
    void resetsAfterWindowExpires() throws Exception {
        RateLimitFilter filter = filter(properties(true, 1, 30, 10, 30));

        assertThat(call(filter, "POST", "/events", "203.0.113.10").passed()).isTrue();

        clock.advance(Duration.ofMinutes(10).minusMillis(1));
        assertThat(call(filter, "POST", "/events", "203.0.113.10").passed()).isFalse();

        clock.advance(Duration.ofMillis(2));
        assertThat(call(filter, "POST", "/events", "203.0.113.10").passed()).isTrue();
    }

    @Test
    void reportsRemainingTimeInRetryAfter() throws Exception {
        RateLimitFilter filter = filter(properties(true, 1, 30, 10, 30));

        assertThat(call(filter, "POST", "/events", "203.0.113.10").passed()).isTrue();
        assertThat(call(filter, "POST", "/events", "203.0.113.10").retryAfter()).isEqualTo("600");

        clock.advance(Duration.ofMinutes(4));
        assertThat(call(filter, "POST", "/events", "203.0.113.10").retryAfter()).isEqualTo("360");
    }

    @Test
    void disabledFilterLetsEverythingThrough() throws Exception {
        RateLimitFilter filter = filter(properties(false, 1, 1, 1, 1));

        for (int i = 0; i < 50; i++) {
            assertThat(call(filter, "POST", "/events", "203.0.113.10").passed()).isTrue();
        }
    }

    @Test
    void normalizesIpv4MappedAndIpv6ToSameBucket() throws Exception {
        RateLimitFilter filter = filter(properties(true, 1, 30, 10, 30));

        assertThat(call(filter, "POST", "/events", "::ffff:203.0.113.10").passed()).isTrue();
        assertThat(call(filter, "POST", "/events", "203.0.113.10").passed()).isFalse();

        assertThat(call(filter, "POST", "/events", "2001:db8:aaaa:bbbb:1::1").passed()).isTrue();
        assertThat(call(filter, "POST", "/events", "2001:db8:aaaa:bbbb:2::2").passed()).isFalse();
        assertThat(call(filter, "POST", "/events", "2001:db8:aaaa:cccc::3").passed()).isTrue();
    }

    @Test
    void nonIpLiteralsWithColonDoNotFailOrShareBucket() throws Exception {
        RateLimitFilter filter = filter(properties(true, 1, 30, 10, 30));

        assertThat(RateLimitFilter.normalizeIp("evil.invalid:80")).isEqualTo("evil.invalid:80");
        assertThat(RateLimitFilter.normalizeIp("[nao-e-ip]")).isEqualTo("nao-e-ip");
        assertThat(call(filter, "POST", "/events", "evil.invalid:80").passed()).isTrue();
        assertThat(call(filter, "POST", "/events", "evil.invalid:80").passed()).isFalse();
        assertThat(call(filter, "POST", "/events", "other.invalid:80").passed()).isTrue();
    }

    @Test
    void keepsMapBoundedByEvictingLeastRecentlyUsedEntry() throws Exception {
        RateLimitFilter filter = filter(properties(true, 1, 30, 10, 30, 2));

        assertThat(call(filter, "POST", "/events", "203.0.113.1").passed()).isTrue();
        assertThat(call(filter, "POST", "/events", "203.0.113.2").passed()).isTrue();
        // A terceira chave evicta a primeira; ela volta a ter balde novo.
        assertThat(call(filter, "POST", "/events", "203.0.113.3").passed()).isTrue();
        assertThat(call(filter, "POST", "/events", "203.0.113.1").passed()).isTrue();
    }

    @Test
    void limitsEmailLinksAndResendSeparately() throws Exception {
        RateLimitFilter filter = filter(emailProperties(1, 1, Duration.ofHours(1)));

        assertThat(call(filter, "POST", "/admin/adm_abc/links/email", "203.0.113.10").passed()).isTrue();
        Result blockedLinks = call(filter, "POST", "/admin/adm_abc/links/email", "203.0.113.10");
        assertThat(blockedLinks.passed()).isFalse();
        assertThat(blockedLinks.status()).isEqualTo(429);
        assertThat(blockedLinks.retryAfter()).isEqualTo("3600");

        assertThat(call(filter, "POST", "/admin/adm_abc/participants/p_abc/email/resend", "203.0.113.10")
                .passed()).isTrue();
        assertThat(call(filter, "POST", "/admin/adm_abc/participants/p_abc/email/resend", "203.0.113.10")
                .passed()).isFalse();
    }

    @Test
    void emailEndpointsUseTheirOwnHourWindow() throws Exception {
        RateLimitFilter filter = filter(emailProperties(1, 1, Duration.ofHours(1)));

        assertThat(call(filter, "POST", "/admin/adm_abc/links/email", "203.0.113.10").passed()).isTrue();

        // Passou da janela curta (10 min), mas o e-mail usa a janela de 1 hora.
        clock.advance(Duration.ofMinutes(11));
        assertThat(call(filter, "POST", "/admin/adm_abc/links/email", "203.0.113.10").passed()).isFalse();

        clock.advance(Duration.ofMinutes(50));
        assertThat(call(filter, "POST", "/admin/adm_abc/links/email", "203.0.113.10").passed()).isTrue();
    }

    @Test
    void blockedResponseDoesNotLeakIpOrToken() throws Exception {
        RateLimitFilter filter = filter(properties(true, 30, 1, 10, 30));

        assertThat(call(filter, "POST", "/join/inv_segredo", "203.0.113.10").passed()).isTrue();
        Result blocked = call(filter, "POST", "/join/inv_segredo", "203.0.113.10");

        assertThat(blocked.passed()).isFalse();
        assertThat(blocked.body())
                .contains("Muitas tentativas")
                .doesNotContain("203.0.113.10")
                .doesNotContain("inv_segredo")
                .doesNotContain("_csrf")
                .doesNotContain("<script");
    }

    private RateLimitFilter filter(RateLimitProperties properties) {
        return new RateLimitFilter(properties, clock);
    }

    private static RateLimitProperties properties(
            boolean enabled, int eventCreate, int join, int draw, int wishlist) {
        return properties(enabled, eventCreate, join, draw, wishlist, 10_000);
    }

    private static RateLimitProperties properties(
            boolean enabled, int eventCreate, int join, int draw, int wishlist, int maxKeys) {
        return new RateLimitProperties(
                enabled,
                eventCreate,
                join,
                draw,
                wishlist,
                10,
                20,
                Duration.ofMinutes(10),
                Duration.ofHours(1),
                Duration.ofHours(1),
                maxKeys);
    }

    private static RateLimitProperties emailProperties(
            int links, int resend, Duration emailWindow) {
        return new RateLimitProperties(
                true,
                5,
                30,
                10,
                30,
                links,
                resend,
                Duration.ofMinutes(10),
                Duration.ofHours(1),
                emailWindow,
                10_000);
    }

    private static Result call(RateLimitFilter filter, String method, String path, String ip)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        return new Result(
                chain.getRequest() != null,
                response.getStatus(),
                response.getHeader("Retry-After"),
                response.getContentAsString());
    }

    private record Result(boolean passed, int status, String retryAfter, String body) {
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
