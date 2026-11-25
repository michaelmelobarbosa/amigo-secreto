package br.com.michael.amigosecreto.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import org.springframework.web.util.UriUtils;

/**
 * Normaliza caminhos HTTP antes de classificá-los nos filtros de segurança e
 * rate limiting, evitando contorno por parâmetros de matriz ({@code ;...}),
 * barras repetidas ({@code //}), segmentos {@code .}/{@code ..} ou
 * percent-encoding.
 */
final class RequestPaths {

    private RequestPaths() {
    }

    static String normalizedPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        if ((servletPath != null && !servletPath.isEmpty())
                || (pathInfo != null && !pathInfo.isEmpty())) {
            String combined = (servletPath != null ? servletPath : "")
                    + (pathInfo != null ? pathInfo : "");
            return normalize(combined, false);
        }

        String path = request.getRequestURI();
        if (path == null) {
            return "/";
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return normalize(path, true);
    }

    static String normalize(String rawPath, boolean decodePercent) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "/";
        }

        Deque<String> stack = new ArrayDeque<>();
        int start = 0;
        int length = rawPath.length();
        while (start <= length) {
            int slash = rawPath.indexOf('/', start);
            String segment = slash < 0 ? rawPath.substring(start) : rawPath.substring(start, slash);
            processSegment(segment, decodePercent, stack);
            if (slash < 0) {
                break;
            }
            start = slash + 1;
        }

        return stack.isEmpty() ? "/" : "/" + String.join("/", stack);
    }

    private static void processSegment(String rawSegment, boolean decodePercent, Deque<String> stack) {
        int semi = rawSegment.indexOf(';');
        String segment = semi >= 0 ? rawSegment.substring(0, semi) : rawSegment;

        if (decodePercent && segment.indexOf('%') >= 0) {
            try {
                segment = UriUtils.decode(segment, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                // Mantém o segmento original se houver escape malformado.
            }
        }

        if (segment.indexOf('/') >= 0) {
            int start = 0;
            int length = segment.length();
            while (start <= length) {
                int slash = segment.indexOf('/', start);
                String part = slash < 0 ? segment.substring(start) : segment.substring(start, slash);
                pushCleanSegment(part, stack);
                if (slash < 0) {
                    break;
                }
                start = slash + 1;
            }
            return;
        }

        pushCleanSegment(segment, stack);
    }

    private static void pushCleanSegment(String segment, Deque<String> stack) {
        if (segment.isEmpty() || ".".equals(segment)) {
            return;
        }
        if ("..".equals(segment)) {
            stack.pollLast();
            return;
        }
        stack.addLast(segment);
    }
}
