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
import org.springframework.transaction.annotation.Transactional;

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

    public NotificationService(
            NotificationRepository notificationRepository,
            ParticipantRepository participantRepository,
            EventRepository eventRepository,
            EmailSender emailSender,
            EmailContentBuilder contentBuilder,
            EmailQuotaGuard emailQuotaGuard) {
        this.notificationRepository = notificationRepository;
        this.participantRepository = participantRepository;
        this.eventRepository = eventRepository;
        this.emailSender = emailSender;
        this.contentBuilder = contentBuilder;
        this.emailQuotaGuard = emailQuotaGuard;
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
        if (event.getStatus() != EventStatus.DRAWN || event.getDrawCount() <= 0) {
            throw new RuleViolationException("O sorteio ainda não foi realizado.");
        }

        Participant participant = participantRepository
                .findByIdAndEvent_Id(participantId, event.getId())
                .orElseThrow(() -> new NotFoundException(PARTICIPANT_NOT_FOUND));
        if (!hasEmail(participant.getEmail())) {
            throw new RuleViolationException("Participante sem e-mail cadastrado.");
        }

        Notification notification = notificationRepository
                .findByParticipant_IdAndDrawCountAndChannel(
                        participant.getId(), event.getDrawCount(), NotificationChannel.EMAIL)
                .orElseGet(() -> new Notification(
                        participant, event.getDrawCount(), NotificationChannel.EMAIL));
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
     * Envia uma notificação. Nunca lança por falha de provedor: o resultado vira
     * estado do outbox e o sorteio já está commitado.
     */
    @Transactional
    public void send(UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId).orElse(null);
        if (notification == null
                || notification.getStatus() == NotificationStatus.SENT
                || notification.getStatus() == NotificationStatus.SKIPPED
                || notification.getAttempts() >= Notification.MAX_ATTEMPTS) {
            return;
        }

        Participant participant = notification.getParticipant();
        Event event = participant.getEvent();

        if (!hasEmail(participant.getEmail())) {
            notification.markSkipped("Participante sem e-mail cadastrado.");
            return;
        }
        if (event.getStatus() != EventStatus.DRAWN
                || event.getDrawCount() != notification.getDrawCount()) {
            notification.markSkipped("O sorteio não corresponde mais a este aviso.");
            return;
        }
        if (!emailQuotaGuard.allowSend(event.getId())) {
            notification.markFailed("Limite diário de envio de e-mails atingido.", false);
            return;
        }

        EmailContent content = contentBuilder.build(event, participant, notification.getDrawCount());
        EmailMessage message = new EmailMessage(
                participant.getEmail(),
                participant.getName(),
                content.subject(),
                content.html(),
                content.text(),
                notification.getIdempotencyKey(),
                content.tags());

        notification.markAttempt();
        try {
            EmailSendResult result = emailSender.send(message);
            if (result.accepted()) {
                notification.markSent(result.providerMessageId());
            } else {
                notification.markFailed(result.errorMessage(), result.retryable());
            }
        } catch (RuntimeException exception) {
            notification.markFailed("Falha inesperada ao enviar: " + exception.getMessage(), true);
        }
    }

    private static boolean hasEmail(String email) {
        return email != null && !email.isBlank();
    }
}
