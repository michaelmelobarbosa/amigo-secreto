package br.com.michael.amigosecreto.notification;

import br.com.michael.amigosecreto.draw.DrawCompletedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O sorteio publica o evento apenas depois do commit. O listener abre uma
 * transação nova para gravar o outbox: a transação original já terminou e o
 * {@code EntityManager} dela não serve mais.
 */
@Component
public class EmailNotificationListener {

    private final NotificationService notificationService;
    private final TransactionTemplate newTransaction;

    public EmailNotificationListener(
            NotificationService notificationService,
            PlatformTransactionManager transactionManager) {
        this.notificationService = notificationService;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @EventListener
    public void onDrawCompleted(DrawCompletedEvent event) {
        newTransaction.executeWithoutResult(status -> notificationService.enqueueForDraw(event));
    }
}
