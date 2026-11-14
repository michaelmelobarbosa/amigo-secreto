package br.com.michael.amigosecreto.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TokenGeneratorTest {

    private final TokenGenerator generator = new TokenGenerator();

    @Test
    void generatesPrefixedUrlSafeTokens() {
        String adminToken = generator.adminToken();
        String inviteCode = generator.inviteCode();
        String accessToken = generator.accessToken();

        assertTrue(adminToken.startsWith("adm_"));
        assertTrue(inviteCode.startsWith("inv_"));
        assertTrue(accessToken.startsWith("p_"));

        assertEquals(26, adminToken.length());
        assertEquals(26, inviteCode.length());
        assertEquals(24, accessToken.length());

        assertTrue(adminToken.substring(4).matches("[A-Za-z0-9_-]+"));
    }

    @Test
    void generatesUniqueTokens() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            tokens.add(generator.accessToken());
        }

        assertEquals(2_000, tokens.size());
    }
}
