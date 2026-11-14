package br.com.michael.amigosecreto.common;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class TokenGenerator {

    private static final int TOKEN_BYTES = 16;

    private final SecureRandom random = new SecureRandom();

    public String adminToken() {
        return generate("adm_");
    }

    public String inviteCode() {
        return generate("inv_");
    }

    public String accessToken() {
        return generate("p_");
    }

    private String generate(String prefix) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
