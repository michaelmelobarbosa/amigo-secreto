package br.com.michael.amigosecreto.notification;

import br.com.michael.amigosecreto.common.NotFoundException;
import br.com.michael.amigosecreto.common.RuleViolationException;
import br.com.michael.amigosecreto.draw.DrawCompletedEvent;
import br.com.michael.amigosecreto.email.EmailContent;
import br.com.michael.amigosecreto.email.EmailContentBuilder;
import br.com.michael.amigosecreto.email.EmailMessage;
import br.com.michael.amigosecreto.email.EmailSendResult;
import br.com.michael.amigosecreto.email.EmailSender;
import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.event.EventRepository;
import br.com.michael.amigosecreto.event.EventStatus;
import br.com.michael.amigosecreto.participant.Participant;
import br.com.michael.amigosecreto.participant.ParticipantRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orquestra o outbox de e-mail: enfileira após o sorteio, envia pelo adaptador
 * e mantém o status para o painel.
 */
@Service
public class NotificationService {

    private static final String EVENT_NOT_FOUND = "Evento não encontrado.";
    private static final String PARTICIPANT_NOT_FOUND = "Participante não encontrado.";

    private final NotificationRepository notificationRepository;
    private final ParticipantRepository participantRepository;
    private final EventRepository eventRepository;
    private final EmailSender emailSender;
    private final EmailContentBuilder contentBuilder;
    private final EmailQuotaGuard emailQuotaGuard;
    private final TransactionTemplate transactionTemplate;

    public NotificationService(
            NotificationRepository notificationRepository,
            ParticipantRepository participantRepository,
            EventRepository eventRepository,
            EmailSender emailSender,
            EmailContentBuilder contentBuilder,
            EmailQuotaGuard emailQuotaGuard,
            PlatformTransactionManager transactionManager) {
        this.notificationRepository = notificationRepository;
        this.participantRepository = participantRepository;
        this.eventRepository = eventRepository;
        this.emailSender = emailSender;
        this.contentBuilder = contentBuilder;
        this.emailQuotaGuard = emailQuotaGuard;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Chamado pelo listener depois do commit do sorteio. Cria uma linha por
     * participante com e-mail, ignorando quem já tem aviso naquele sorteio.
     */
    @Transactional
    public void enqueueForDraw(DrawCompletedEvent completed) {
        Event event = eventRepository.findById(completed.eventId())
                .orElseThrow(() -> new NotFoundException(EVENT_NOT_FOUND));
        if (event.getStatus() != EventStatus.DRAWN
                || event.getDrawCount() != completed.drawCount()) {
            return;
        }

        List<Participant> participants = participantRepository.findByEvent_Id(event.getId());
        Set<String> seenEmails = new HashSet<>();
        for (Participant participant : participants) {
            if (!hasEmail(participant.getEmail())) {
                continue;
            }
            String normalizedEmail = participant.getEmail().trim().toLowerCase(Locale.ROOT);
            if (!seenEmails.add(normalizedEmail)) {
                continue;
            }
            if (notificationRepository.existsByParticipant_IdAndDrawCountAndChannel(
                    participant.getId(), completed.drawCount(), NotificationChannel.EMAIL)) {
                continue;
            }
            notificationRepository.save(new Notification(
                    participant, completed.drawCount(), NotificationChannel.EMAIL));
        }
    }

    /**
     * Reenvio manual pelo painel: devolve a notificação do sorteio atual para a
     * fila com uma chave de idempotência nova.
     */
    @Transactional
    public void resendForParticipant(Event event, UUID participantId) {
        Event locked = eventRepository.findByIdForUpdate(event.getId())
                .orElseThrow(() -> new NotFoundException(EVENT_NOT_FOUND));
        if (locked.getStatus() != EventStatus.DRAWN || locked.getDrawCount() <= 0) {
            throw new RuleViolationException("O sorteio ainda não foi realizado.");
        }

        Participant participant = participantRepository
                .findByIdAndEvent_Id(participantId, locked.getId())
                .orElseThrow(() -> new NotFoundException(PARTICIPANT_NOT_FOUND));
        if (!hasEmail(participant.getEmail())) {
            throw new RuleViolationException("Participante sem e-mail cadastrado.");
        }

        Notification notification = notificationRepository
                .findByParticipant_IdAndDrawCountAndChannel(
                        participant.getId(), locked.getDrawCount(), NotificationChannel.EMAIL)
                .orElseGet(() -> new Notification(
                        participant, locked.getDrawCount(), NotificationChannel.EMAIL));
        notification.resetForRetry();
        notificationRepository.save(notification);
    }

    @Transactional(readOnly = true)
    public Map<UUID, NotificationStatus> statusesFor(Event event, int drawCount) {
        if (drawCount <= 0) {
            return Map.of();
        }
        return notificationRepository
                .findByParticipant_Event_IdAndDrawCountAndChannel(
                        event.getId(), drawCount, NotificationChannel.EMAIL)
                .stream()
                .collect(Collectors.toMap(
                        notification -> notification.getParticipant().getId(),
                        Notification::getStatus));
    }

    @Transactional(readOnly = true)
    public List<UUID> pendingIds() {
        return notificationRepository
                .findTop20ByStatusInAndAttemptsLessThanOrderByCreatedAtAsc(
                        List.of(NotificationStatus.PENDING, NotificationStatus.FAILED),
                        Notification.MAX_ATTEMPTS)
                .stream()
                .map(Notification::getId)
                .toList();
    }

    /**
     * Envia uma notificação mantendo a chamada HTTP ao provedor fora da
     * transação de banco. Nunca lança por falha de provedor: o resultado vira
     * estado do outbox e o sorteio já está commitado.
     */
    public void send(UUID notificationId) {
        EmailMessage message = prepareForSend(notificationId);
        if (message == null) {
            return;
        }

        EmailSendResult result;
        try {
            result = emailSender.send(message);
        } catch (RuntimeException exception) {
            recordFailure(
                    notificationId,
                    "Falha inesperada ao enviar: " + exception.getClass().getSimpleName(),
                    true);
            return;
        }

        recordOutcome(notificationId, result);
    }

    private EmailMessage prepareForSend(UUID notificationId) {
        return transactionTemplate.execute(status -> {
            Notification notification = notificationRepository.findById(notificationId).orElse(null);
            if (notification == null
                    || notification.getStatus() == NotificationStatus.SENT
                    || notification.getStatus() == NotificationStatus.SKIPPED
                    || notification.getAttempts() >= Notification.MAX_ATTEMPTS) {
                return null;
            }

            Participant participant = notification.getParticipant();
            Event event = participant.getEvent();

            if (!hasEmail(participant.getEmail())) {
                notification.markSkipped("Participante sem e-mail cadastrado.");
                return null;
            }
            if (event.getStatus() != EventStatus.DRAWN
                    || event.getDrawCount() != notification.getDrawCount()) {
                notification.markSkipped("O sorteio não corresponde mais a este aviso.");
                return null;
            }
            if (!emailQuotaGuard.allowSend(event.getId())) {
                notification.markFailed("Limite diário de envio de e-mails atingido.", false);
                return null;
            }

            EmailContent content =
                    contentBuilder.build(event, participant, notification.getDrawCount());
            notification.markAttempt();
            notificationRepository.save(notification);

            return new EmailMessage(
                    participant.getEmail(),
                    participant.getName(),
                    content.subject(),
                    content.html(),
                    content.text(),
                    notification.getIdempotencyKey(),
                    content.tags());
        });
    }

    private void recordOutcome(UUID notificationId, EmailSendResult result) {
        transactionTemplate.executeWithoutResult(status -> {
            Notification notification = notificationRepository.findById(notificationId).orElse(null);
            if (notification == null) {
                return;
            }
            if (result.accepted()) {
                notification.markSent(result.providerMessageId());
            } else {
                notification.markFailed(result.errorMessage(), result.retryable());
            }
        });
    }

    private void recordFailure(UUID notificationId, String error, boolean retryable) {
        transactionTemplate.executeWithoutResult(status -> {
            Notification notification = notificationRepository.findById(notificationId).orElse(null);
            if (notification != null) {
                notification.markFailed(error, retryable);
            }
        });
    }

    private static boolean hasEmail(String email) {
        return email != null && !email.isBlank();
    }
}
