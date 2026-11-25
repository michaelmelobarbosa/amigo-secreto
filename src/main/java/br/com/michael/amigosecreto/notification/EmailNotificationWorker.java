package br.com.michael.amigosecreto.notification;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker do outbox. Cada envio roda na própria transação do serviço; uma falha
 * de provedor não desfaz o sorteio nem interrompe os demais participantes.
 */
@Component
@ConditionalOnProperty(prefix = "app.email", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class EmailNotificationWorker {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationWorker.class);

    private final NotificationService notificationService;

    public EmailNotificationWorker(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Scheduled(fixedDelayString = "${app.email.worker-delay:30000}")
    public void processPending() {
        for (UUID notificationId : notificationService.pendingIds()) {
            try {
                notificationService.send(notificationId);
            } catch (RuntimeException exception) {
                // Sem e-mail/token no log; o id e a classe bastam para diagnóstico.
                log.warn(
                        "Falha ao processar notificação {}: {}",
                        notificationId,
                        exception.getClass().getSimpleName());
            }
        }
    }
}
