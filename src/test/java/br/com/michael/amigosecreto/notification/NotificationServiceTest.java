package br.com.michael.amigosecreto.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.verify;

import br.com.michael.amigosecreto.common.RuleViolationException;
import br.com.michael.amigosecreto.draw.DrawCompletedEvent;
import br.com.michael.amigosecreto.draw.DrawService;
import br.com.michael.amigosecreto.email.EmailMessage;
import br.com.michael.amigosecreto.email.EmailProperties;
import br.com.michael.amigosecreto.email.EmailSendResult;
import br.com.michael.amigosecreto.email.EmailSender;
import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.event.EventRepository;
import br.com.michael.amigosecreto.event.EventStatus;
import br.com.michael.amigosecreto.participant.Participant;
import br.com.michael.amigosecreto.participant.ParticipantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox de e-mail: enfileiramento após o sorteio, envio com os três desfechos
 * (aceito, falha temporária, falha definitiva), reenvio manual e status do
 * painel.
 *
 * <p>O {@link EmailSender} real é substituído por um mock para que nenhuma
 * requisição saia e o resultado do provedor seja controlado pelo teste. Tudo
 * roda na transação do teste e é desfeito ao final — exceto o teste do
 * after-commit do sorteio, que precisa de commits reais para provar que o
 * outbox foi gravado; esse limpa o evento no {@code finally}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NotificationServiceTest {

    private final NotificationService notificationService;
    private final NotificationRepository notificationRepository;
    private final ParticipantRepository participantRepository;
    private final EventRepository eventRepository;
    private final DrawService drawService;

    @MockitoBean
    private EmailSender emailSender;

    @PersistenceContext
    private EntityManager entityManager;

    private final PlatformTransactionManager transactionManager;

    @Autowired
    NotificationServiceTest(
            NotificationService notificationService,
            NotificationRepository notificationRepository,
            ParticipantRepository participantRepository,
            EventRepository eventRepository,
            DrawService drawService,
            PlatformTransactionManager transactionManager) {
        this.notificationService = notificationService;
        this.notificationRepository = notificationRepository;
        this.participantRepository = participantRepository;
        this.eventRepository = eventRepository;
        this.drawService = drawService;
        this.transactionManager = transactionManager;
    }

    // ---------- enqueue ----------

    @Test
    void enqueueForDrawCreatesOnePendingNotificationPerParticipantWithEmailAndDoesNotDuplicate() {
        Event event = createOpenEvent("notif_enqueue");
        try {
            Participant ana = addParticipant(event, "Ana", "ana@example.com", "notif_enqueue_ana");
            Participant bruno =
                    addParticipant(event, "Bruno", "bruno@example.com", "notif_enqueue_bruno");
            markDrawn(event, 1);

            notificationService.enqueueForDraw(
                    new DrawCompletedEvent(event.getId(), 1, Instant.now()));
            notificationService.enqueueForDraw(
                    new DrawCompletedEvent(event.getId(), 1, Instant.now()));

            List<Notification> notifications = notificationRepository
                    .findByParticipant_Event_IdAndDrawCountAndChannel(
                            event.getId(), 1, NotificationChannel.EMAIL);
            assertThat(notifications).hasSize(2);
            assertThat(notifications).allMatch(n -> n.getStatus() == NotificationStatus.PENDING);
            assertThat(notifications)
                    .extracting(n -> n.getParticipant().getId())
                    .containsExactlyInAnyOrder(ana.getId(), bruno.getId());
        } finally {
            eventRepository.deleteByIdBulk(event.getId());
        }
    }

    @Test
    void participantWithoutEmailDoesNotGetANotification() {
        Event event = createOpenEvent("notif_no_email");
        try {
            Participant withEmail =
                    addParticipant(event, "Ana", "ana@example.com", "notif_no_email_ana");
            addParticipant(event, "Sem e-mail", null, "notif_no_email_sem");
            markDrawn(event, 1);

            notificationService.enqueueForDraw(
                    new DrawCompletedEvent(event.getId(), 1, Instant.now()));

            List<Notification> notifications = notificationRepository
                    .findByParticipant_Event_IdAndDrawCountAndChannel(
                            event.getId(), 1, NotificationChannel.EMAIL);
            assertThat(notifications).hasSize(1);
            assertThat(notifications.getFirst().getParticipant().getId())
                    .isEqualTo(withEmail.getId());
        } finally {
            eventRepository.deleteByIdBulk(event.getId());
        }
    }

    /**
     * Sem a transação do teste: o {@code DrawService} publica o evento no
     * {@code afterCommit}, e só um commit real prova se o listener consegue
     * gravar o outbox. O evento é apagado no {@code finally} para não vazar
     * estado para as outras classes.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void drawCommitsNotificationsAfterTheDrawTransaction() {
        Event event = createOpenEvent("notif_draw_commit");
        addParticipant(event, "Ana", "ana@example.com", "notif_draw_commit_ana");
        addParticipant(event, "Bruno", "bruno@example.com", "notif_draw_commit_bruno");
        addParticipant(event, "Carla", null, "notif_draw_commit_carla");

        List<Notification> notifications;
        try {
            drawService.drawByAdminToken(event.getAdminToken());
            Event drawn = eventRepository.findById(event.getId()).orElseThrow();
            assertThat(drawn.getStatus()).isEqualTo(EventStatus.DRAWN);
            assertThat(drawn.getDrawCount()).isEqualTo(1);
            notifications = notificationRepository
                    .findByParticipant_Event_IdAndDrawCountAndChannel(
                            event.getId(), 1, NotificationChannel.EMAIL);

            assertThat(notifications).hasSize(2);
            assertThat(notifications)
                    .allMatch(notification -> notification.getStatus() == NotificationStatus.PENDING);
            List<UUID> participantIds = notifications.stream()
                    .map(notification -> notification.getParticipant().getId())
                    .toList();
            assertThat(participantRepository.findAllById(participantIds))
                    .extracting(Participant::getEmail)
                    .containsExactlyInAnyOrder("ana@example.com", "bruno@example.com");
        } finally {
            deleteEventInNewTransaction(event.getId());
        }
    }

    // ---------- send ----------

    @Test
    void acceptedSendMarksNotificationSentAndStoresProviderMessageId() {
        Event event = createDrawnEvent("notif_sent", 1);
        Participant ana = addParticipant(event, "Ana", "ana@example.com", "notif_sent_ana");
        Notification notification = addNotification(ana, 1);
        given(emailSender.send(any(EmailMessage.class)))
                .willReturn(EmailSendResult.accepted("brevo-message-42"));

        notificationService.send(notification.getId());

        Notification sent = reload(notification.getId());
        assertThat(sent.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(sent.getProviderMessageId()).isEqualTo("brevo-message-42");
        assertThat(sent.getAttempts()).isEqualTo(1);
        assertThat(sent.getSentAt()).isNotNull();
        assertThat(sent.getLastError()).isNull();

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(captor.capture());
        assertThat(captor.getValue().toEmail()).isEqualTo("ana@example.com");
        assertThat(captor.getValue().idempotencyKey())
                .isEqualTo(sent.getIdempotencyKey());
    }

    @Test
    void retryableRejectionKeepsNotificationFailedAndAvailableToTheWorker() {
        Event event = createDrawnEvent("notif_retry", 1);
        Participant ana = addParticipant(event, "Ana", "ana@example.com", "notif_retry_ana");
        Notification notification = addNotification(ana, 1);
        given(emailSender.send(any(EmailMessage.class)))
                .willReturn(EmailSendResult.rejected(
                        "too_many_requests", "Brevo HTTP 429: Rate limit", true));

        notificationService.send(notification.getId());
        notificationRepository.flush();

        Notification failed = reload(notification.getId());
        assertThat(failed.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getLastError()).contains("Brevo HTTP 429");
        assertThat(notificationService.pendingIds()).contains(notification.getId());
    }

    @Test
    void nonRetryableRejectionExhaustsAttemptsAndLeavesTheWorkerQueue() {
        Event event = createDrawnEvent("notif_dead", 1);
        Participant ana = addParticipant(event, "Ana", "ana@example.com", "notif_dead_ana");
        Notification notification = addNotification(ana, 1);
        given(emailSender.send(any(EmailMessage.class)))
                .willReturn(EmailSendResult.rejected(
                        "unauthorized", "Brevo HTTP 401: Key not found", false));

        notificationService.send(notification.getId());
        notificationRepository.flush();

        Notification failed = reload(notification.getId());
        assertThat(failed.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(Notification.MAX_ATTEMPTS);
        assertThat(notificationService.pendingIds()).doesNotContain(notification.getId());
    }

    @Test
    void sendOfNotificationFromAnOlderDrawMarksItSkippedWithoutCallingTheProvider() {
        Event event = createDrawnEvent("notif_old", 2);
        Participant ana = addParticipant(event, "Ana", "ana@example.com", "notif_old_ana");
        Notification notification = addNotification(ana, 1);

        notificationService.send(notification.getId());

        Notification skipped = reload(notification.getId());
        assertThat(skipped.getStatus()).isEqualTo(NotificationStatus.SKIPPED);
        assertThat(skipped.getLastError())
                .isEqualTo("O sorteio não corresponde mais a este aviso.");
        verify(emailSender, never()).send(any(EmailMessage.class));
    }

    @Test
    void sendMarksNotificationFailedWithoutCallingProviderWhenEventDailyQuotaIsReached() {
        Event event = createDrawnEvent("notif_quota", EmailProperties.DEFAULT_EVENT_DAILY_LIMIT + 1);
        Participant ana = addParticipant(event, "Ana", "ana@example.com", "notif_quota_ana");
        for (int i = 1; i <= EmailProperties.DEFAULT_EVENT_DAILY_LIMIT; i++) {
            Notification sentBefore = addNotification(ana, i);
            sentBefore.markAttempt();
            sentBefore.markSent("brevo-prev-" + i);
            notificationRepository.save(sentBefore);
        }
        Notification current = addNotification(ana, EmailProperties.DEFAULT_EVENT_DAILY_LIMIT + 1);
        notificationRepository.flush();

        notificationService.send(current.getId());

        Notification blocked = reload(current.getId());
        assertThat(blocked.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(blocked.getAttempts()).isEqualTo(Notification.MAX_ATTEMPTS);
        assertThat(blocked.getLastError()).isEqualTo("Limite diário de envio de e-mails atingido.");
        verify(emailSender, never()).send(any(EmailMessage.class));
    }

    // ---------- resend ----------

    @Test
    void resendCreatesAPendingNotificationWhenNoneExists() {
        Event event = createDrawnEvent("notif_resend_new", 1);
        Participant ana = addParticipant(event, "Ana", "ana@example.com", "notif_resend_new_ana");

        notificationService.resendForParticipant(event, ana.getId());

        Notification notification = notificationRepository
                .findByParticipant_IdAndDrawCountAndChannel(
                        ana.getId(), 1, NotificationChannel.EMAIL)
                .orElseThrow();
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getAttempts()).isZero();
        assertThat(notification.getIdempotencyKey()).isNotNull();
    }

    @Test
    void resendResetsTheCurrentDrawNotificationWithANewIdempotencyKey() {
        Event event = createDrawnEvent("notif_resend_old", 1);
        Participant ana = addParticipant(event, "Ana", "ana@example.com", "notif_resend_old_ana");
        Notification notification = addNotification(ana, 1);
        notification.markAttempt();
        notification.markSent("brevo-old-message");
        notificationRepository.saveAndFlush(notification);
        UUID oldKey = notification.getIdempotencyKey();

        notificationService.resendForParticipant(event, ana.getId());

        Notification reset = reload(notification.getId());
        assertThat(reset.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(reset.getAttempts()).isZero();
        assertThat(reset.getProviderMessageId()).isNull();
        assertThat(reset.getSentAt()).isNull();
        assertThat(reset.getLastError()).isNull();
        assertThat(reset.getIdempotencyKey()).isNotEqualTo(oldKey);
    }

    @Test
    void resendWithoutEmailIsRejectedWithAFriendlyRuleViolation() {
        Event event = createDrawnEvent("notif_resend_no_email", 1);
        Participant ana = addParticipant(event, "Sem e-mail", null, "notif_resend_no_email_ana");

        assertThatThrownBy(() -> notificationService.resendForParticipant(event, ana.getId()))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("Participante sem e-mail cadastrado.");

        assertThat(notificationRepository.findByParticipant_IdAndDrawCountAndChannel(
                        ana.getId(), 1, NotificationChannel.EMAIL))
                .isEmpty();
    }

    @Test
    void resendReloadsEventStateFromDatabaseAndRejectsReopenedEvent() {
        Event staleDrawnEvent = createDrawnEvent("notif_resend_stale", 1);
        Participant ana = addParticipant(
                staleDrawnEvent, "Ana", "ana@example.com", "notif_resend_stale_ana");
        entityManager.flush();
        entityManager.detach(staleDrawnEvent);

        Event reopened = eventRepository.findById(staleDrawnEvent.getId()).orElseThrow();
        reopened.reopen();
        eventRepository.saveAndFlush(reopened);

        assertThatThrownBy(() -> notificationService.resendForParticipant(staleDrawnEvent, ana.getId()))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("O sorteio ainda não foi realizado.");
    }

    // ---------- statuses ----------

    @Test
    void statusesForReturnsOnlyTheCurrentDrawAndMapsByParticipant() {
        Event event = createDrawnEvent("notif_statuses", 2);
        Participant ana = addParticipant(event, "Ana", "ana@example.com", "notif_statuses_ana");
        Participant bruno =
                addParticipant(event, "Bruno", "bruno@example.com", "notif_statuses_bruno");

        Notification anaCurrent = addNotification(ana, 2);
        anaCurrent.markSent("brevo-ana");
        notificationRepository.saveAndFlush(anaCurrent);
        addNotification(bruno, 2);
        Notification anaPrevious = addNotification(ana, 1);
        anaPrevious.markFailed("falha antiga", true);
        notificationRepository.saveAndFlush(anaPrevious);

        Map<UUID, NotificationStatus> statuses = notificationService.statusesFor(event, 2);

        assertThat(statuses)
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        ana.getId(), NotificationStatus.SENT,
                        bruno.getId(), NotificationStatus.PENDING));
    }

    @Test
    void statusesForEmptyDrawReturnsEmptyMap() {
        Event event = createOpenEvent("notif_statuses_empty");

        assertThat(notificationService.statusesFor(event, 0)).isEmpty();
    }

    // ---------- helpers ----------

    private Event createOpenEvent(String tokenSuffix) {
        return eventRepository.saveAndFlush(new Event(
                "Família",
                LocalDate.of(2026, 12, 24),
                null,
                "adm_" + tokenSuffix,
                "inv_" + tokenSuffix,
                Instant.now()));
    }

    private Event createDrawnEvent(String tokenSuffix, int drawCount) {
        Event event = createOpenEvent(tokenSuffix);
        markDrawn(event, drawCount);
        return event;
    }

    private void markDrawn(Event event, int drawCount) {
        for (int i = 0; i < drawCount; i++) {
            event.markDrawn(Instant.now());
        }
        eventRepository.saveAndFlush(event);
    }

    private Participant addParticipant(
            Event event, String name, String email, String tokenSuffix) {
        Participant participant =
                new Participant(event, name, "p_" + tokenSuffix, false);
        participant.setEmail(email);
        return participantRepository.saveAndFlush(participant);
    }

    private Notification addNotification(Participant participant, int drawCount) {
        return notificationRepository.saveAndFlush(
                new Notification(participant, drawCount, NotificationChannel.EMAIL));
    }

    private Notification reload(UUID id) {
        notificationRepository.flush();
        return notificationRepository.findById(id).orElseThrow();
    }

    private void deleteEventInNewTransaction(UUID eventId) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> eventRepository.deleteByIdBulk(eventId));
    }
}
