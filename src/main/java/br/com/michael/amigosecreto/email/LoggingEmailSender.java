package br.com.michael.amigosecreto.email;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fallback de desenvolvimento: registra o envio sem chamar o provedor e sem
 * escrever e-mail ou token no log.
 */
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public EmailSendResult send(EmailMessage message) {
        log.info(
                "E-mail simulado (app.email.enabled=false): subject='{}', idempotency={}",
                message.subject(),
                message.idempotencyKey());
        return EmailSendResult.accepted("log-" + UUID.randomUUID());
    }
}
