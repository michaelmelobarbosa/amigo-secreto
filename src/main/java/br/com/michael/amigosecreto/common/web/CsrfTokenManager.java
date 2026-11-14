package br.com.michael.amigosecreto.common.web;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

@Component
public class CsrfTokenManager {

    private static final String SESSION_ATTRIBUTE = "br.com.michael.amigosecreto.csrfToken";
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    public String token(HttpSession session) {
        Objects.requireNonNull(session, "session não pode ser nula");

        synchronized (session) {
            Object storedToken = session.getAttribute(SESSION_ATTRIBUTE);
            if (storedToken instanceof String token) {
                return token;
            }

            byte[] bytes = new byte[TOKEN_BYTES];
            secureRandom.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            session.setAttribute(SESSION_ATTRIBUTE, token);
            return token;
        }
    }

    public boolean isValid(HttpSession session, String submittedToken) {
        if (session == null || submittedToken == null) {
            return false;
        }

        Object storedToken = session.getAttribute(SESSION_ATTRIBUTE);
        if (!(storedToken instanceof String token)) {
            return false;
        }

        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                submittedToken.getBytes(StandardCharsets.UTF_8));
    }
}
