package br.com.michael.amigosecreto.notification;

import br.com.michael.amigosecreto.email.EmailProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Teto diário interno de envio de e-mails (global e por evento), cobrindo
 * tanto o outbox quanto o disparo avulso de links do organizador antes de
 * atingir a cota do provedor.
 */
@Component
public class EmailQuotaGuard {

    private final EmailProperties properties;
    private final NotificationRepository notificationRepository;
    private final Clock clock;

    private LocalDate currentDay;
    private int directDailyCount;
    private final Map<UUID, Integer> directEventCounts = new HashMap<>();

    @Autowired
    public EmailQuotaGuard(
            EmailProperties properties, NotificationRepository notificationRepository) {
        this(properties, notificationRepository, Clock.systemUTC());
    }

    EmailQuotaGuard(
            EmailProperties properties,
            NotificationRepository notificationRepository,
            Clock clock) {
        this.properties = properties;
        this.notificationRepository = notificationRepository;
        this.clock = clock;
        this.currentDay = LocalDate.now(clock);
    }

    /**
     * Verifica se o envio está dentro dos tetos diários global e do evento.
     */
    public synchronized boolean allowSend(UUID eventId) {
        LocalDate today = rolloverIfNewDay();
        Instant startOfDay = today.atStartOfDay(ZoneOffset.UTC).toInstant();

        long outboxTotal = notificationRepository.countByStatusAndSentAtGreaterThanEqual(
                NotificationStatus.SENT, startOfDay);
        if (outboxTotal + directDailyCount >= properties.dailyLimit()) {
            return false;
        }

        if (eventId != null) {
            long outboxEvent =
                    notificationRepository
                            .countByParticipant_Event_IdAndStatusAndSentAtGreaterThanEqual(
                                    eventId, NotificationStatus.SENT, startOfDay);
            int directEvent = directEventCounts.getOrDefault(eventId, 0);
            if (outboxEvent + directEvent >= properties.eventDailyLimit()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Registra um envio avulso aceito pelo provedor (fora do outbox).
     */
    public synchronized void recordDirectSend(UUID eventId) {
        rolloverIfNewDay();
        directDailyCount++;
        if (eventId != null) {
            directEventCounts.merge(eventId, 1, Integer::sum);
        }
    }

    private LocalDate rolloverIfNewDay() {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(currentDay)) {
            currentDay = today;
            directDailyCount = 0;
            directEventCounts.clear();
        }
        return today;
    }
}
