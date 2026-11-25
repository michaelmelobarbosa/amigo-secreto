package br.com.michael.amigosecreto.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.UriUtils;

@Component
public class CsrfTokenInterceptor implements HandlerInterceptor {

    private static final String REQUEST_ATTRIBUTE = "_csrf";
    private static final String PARAMETER_NAME = "_csrf";
    private static final Set<String> FORM_VIEWS =
            Set.of("index", "admin/dashboard", "join/form", "participant/view");

    private final CsrfTokenManager csrfTokenManager;

    public CsrfTokenInterceptor(CsrfTokenManager csrfTokenManager) {
        this.csrfTokenManager = csrfTokenManager;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        if (isStateChangingMethod(method)) {
            if (hasCsrfInQueryString(request.getQueryString())
                    || !csrfTokenManager.isValid(
                            request.getSession(false), request.getParameter(PARAMETER_NAME))) {
                throw new CsrfTokenException();
            }
        }

        return true;
    }

    @Override
    public void postHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            ModelAndView modelAndView) {
        if (modelAndView == null || !FORM_VIEWS.contains(modelAndView.getViewName())) {
            return;
        }

        String method = request.getMethod();
        HttpSession session = "GET".equalsIgnoreCase(method)
                ? request.getSession(true)
                : request.getSession(false);
        if (session == null) {
            return;
        }

        String token = csrfTokenManager.token(session);
        request.setAttribute(REQUEST_ATTRIBUTE, token);
        modelAndView.addObject(REQUEST_ATTRIBUTE, token);
    }

    private boolean hasCsrfInQueryString(String queryString) {
        if (queryString == null || queryString.isEmpty()) {
            return false;
        }

        int start = 0;
        int length = queryString.length();
        while (start <= length) {
            int amp = queryString.indexOf('&', start);
            String pair = amp < 0 ? queryString.substring(start) : queryString.substring(start, amp);
            int eq = pair.indexOf('=');
            String rawName = eq < 0 ? pair : pair.substring(0, eq);
            String name = decodeParameterName(rawName);
            if (PARAMETER_NAME.equals(name)) {
                return true;
            }
            if (amp < 0) {
                break;
            }
            start = amp + 1;
        }
        return false;
    }

    private String decodeParameterName(String rawName) {
        if (rawName.indexOf('%') < 0) {
            return rawName;
        }
        try {
            return UriUtils.decode(rawName, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return rawName;
        }
    }

    private boolean isStateChangingMethod(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);
    }
}
