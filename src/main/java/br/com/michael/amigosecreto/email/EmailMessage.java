package br.com.michael.amigosecreto.email;

import java.util.List;
import java.util.UUID;

public record EmailMessage(
        String toEmail,
        String toName,
        String subject,
        String html,
        String text,
        UUID idempotencyKey,
        List<String> tags) {
}
