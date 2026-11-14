package br.com.michael.amigosecreto.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class CsrfTokenInterceptor implements HandlerInterceptor {

    private static final String REQUEST_ATTRIBUTE = "_csrf";
    private static final String PARAMETER_NAME = "_csrf";

    private final CsrfTokenManager csrfTokenManager;

    public CsrfTokenInterceptor(CsrfTokenManager csrfTokenManager) {
        this.csrfTokenManager = csrfTokenManager;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
            request.setAttribute(REQUEST_ATTRIBUTE, csrfTokenManager.token(request.getSession()));
            return true;
        }

        if (isStateChangingMethod(method)
                && !csrfTokenManager.isValid(request.getSession(false), request.getParameter(PARAMETER_NAME))) {
            throw new CsrfTokenException();
        }

        return true;
    }

    private boolean isStateChangingMethod(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);
    }
}
