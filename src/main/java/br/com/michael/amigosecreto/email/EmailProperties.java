package br.com.michael.amigosecreto.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.email")
public record EmailProperties(
        boolean enabled,
        String fromEmail,
        String fromName,
        String replyTo,
        Brevo brevo) {

    public record Brevo(String apiKey, boolean sandbox) {
    }
}
