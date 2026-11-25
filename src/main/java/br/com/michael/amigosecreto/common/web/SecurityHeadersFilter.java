package br.com.michael.amigosecreto.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final String PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=()";

    private static final String CONTENT_SECURITY_POLICY = """
            default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; \
            font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; \
            form-action 'self'; frame-ancestors 'none'
            """.replace("\\", " ").replaceAll("\\s+", " ").trim();

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String path = RequestPaths.normalizedPath(request);
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Permissions-Policy", PERMISSIONS_POLICY);

        if (!isStaticAsset(path)) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
            response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
            if (isSensitive(path)) {
                response.setHeader("X-Robots-Tag", "noindex, nofollow");
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isStaticAsset(String path) {
        return path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/images/")
                || path.startsWith("/fonts/")
                || path.startsWith("/webjars/")
                || path.equals("/favicon.ico")
                || path.equals("/robots.txt")
                || path.startsWith("/actuator/");
    }

    private boolean isSensitive(String path) {
        return path.startsWith("/admin/")
                || path.startsWith("/p/")
                || path.startsWith("/join/");
    }
}
