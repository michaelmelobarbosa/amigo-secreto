package br.com.michael.amigosecreto.email;

import java.util.List;

public record EmailContent(
        String subject,
        String html,
        String text,
        List<String> tags) {
}
