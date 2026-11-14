package br.com.michael.amigosecreto.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.event.EventRepository;
import br.com.michael.amigosecreto.participant.Participant;
import br.com.michael.amigosecreto.participant.ParticipantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Garante o outbox no banco: leitura por sorteio, unicidade por pessoa/canal e
 * cascade na remoção do participante.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NotificationRepositoryTest {

    private final NotificationRepository notificationRepository;
    private final ParticipantRepository participantRepository;
    private final EventRepository eventRepository;

    @Autowired
    NotificationRepositoryTest(
            NotificationRepository notificationRepository,
            ParticipantRepository participantRepository,
            EventRepository eventRepository) {
        this.notificationRepository = notificationRepository;
        this.participantRepository = participantRepository;
        this.eventRepository = eventRepository;
    }

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void persistsPendingNotificationAndFindsItByParticipantAndStatus() {
        Event event = createEvent("adm_notif_a", "inv_notif_a");
        Participant ana = createParticipant(event, "Ana", "p_notif_a");

        Notification notification = notificationRepository.saveAndFlush(
                new Notification(ana, 1, NotificationChannel.EMAIL));

        assertNotNull(notification.getId());
        assertEquals(NotificationStatus.PENDING, notification.getStatus());
        assertEquals(0, notification.getAttempts());
        assertNotNull(notification.getIdempotencyKey());
        assertNotNull(notification.getCreatedAt());

        assertTrue(notificationRepository.existsByParticipant_IdAndDrawCountAndChannel(
                ana.getId(), 1, NotificationChannel.EMAIL));
        assertEquals(
                notification.getId(),
                notificationRepository
                        .findByParticipant_IdAndDrawCountAndChannel(
                                ana.getId(), 1, NotificationChannel.EMAIL)
                        .orElseThrow()
                        .getId());
        assertEquals(
                1,
                notificationRepository
                        .findByParticipant_Event_IdAndDrawCountAndChannel(
                                event.getId(), 1, NotificationChannel.EMAIL)
                        .size());
        assertEquals(
                1,
                notificationRepository
                        .findTop20ByStatusInAndAttemptsLessThanOrderByCreatedAtAsc(
                                List.of(NotificationStatus.PENDING, NotificationStatus.FAILED),
                                3)
                        .size());
    }

    @Test
    void samePersonCannotHaveTwoNotificationsForTheSameDrawAndChannel() {
        Event event = createEvent("adm_notif_b", "inv_notif_b");
        Participant ana = createParticipant(event, "Ana", "p_notif_b");

        notificationRepository.saveAndFlush(new Notification(ana, 1, NotificationChannel.EMAIL));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> notificationRepository.saveAndFlush(
                        new Notification(ana, 1, NotificationChannel.EMAIL)));
    }

    @Test
    void deletingParticipantCascadesToNotifications() {
        Event event = createEvent("adm_notif_c", "inv_notif_c");
        Participant ana = createParticipant(event, "Ana", "p_notif_c");
        notificationRepository.saveAndFlush(new Notification(ana, 1, NotificationChannel.EMAIL));

        entityManager.clear();
        participantRepository.deleteById(ana.getId());
        participantRepository.flush();

        assertEquals(0, notificationRepository.count());
    }

    private Event createEvent(String adminToken, String inviteCode) {
        return eventRepository.saveAndFlush(new Event(
                "Família",
                LocalDate.of(2026, 12, 24),
                null,
                adminToken,
                inviteCode,
                Instant.now()));
    }

    private Participant createParticipant(Event event, String name, String accessToken) {
        return participantRepository.saveAndFlush(
                new Participant(event, name, accessToken, false));
    }
}
