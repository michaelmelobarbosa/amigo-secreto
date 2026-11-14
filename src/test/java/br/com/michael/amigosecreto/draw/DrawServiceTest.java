package br.com.michael.amigosecreto.draw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.michael.amigosecreto.common.RuleViolationException;
import br.com.michael.amigosecreto.event.CreateEventCommand;
import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.event.EventRepository;
import br.com.michael.amigosecreto.event.EventService;
import br.com.michael.amigosecreto.event.EventStatus;
import br.com.michael.amigosecreto.exclusion.ExclusionGroupService;
import br.com.michael.amigosecreto.participant.Participant;
import br.com.michael.amigosecreto.participant.ParticipantRepository;
import br.com.michael.amigosecreto.participant.ParticipantService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sorteio integrado: primeiro sorteio, refazer e reabrir.
 *
 * <p>A aleatoriedade é real ({@link java.security.SecureRandom}), então os
 * testes validam invariantes — ninguém tira a si mesmo, casal não se tira,
 * todos dão e recebem uma vez — e não um mapeamento fixo.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DrawServiceTest {

    private final DrawService drawService;
    private final EventService eventService;
    private final ParticipantService participantService;
    private final ExclusionGroupService exclusionGroupService;
    private final EventRepository eventRepository;
    private final ParticipantRepository participantRepository;
    private final AssignmentRepository assignmentRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    DrawServiceTest(
            DrawService drawService,
            EventService eventService,
            ParticipantService participantService,
            ExclusionGroupService exclusionGroupService,
            EventRepository eventRepository,
            ParticipantRepository participantRepository,
            AssignmentRepository assignmentRepository) {
        this.drawService = drawService;
        this.eventService = eventService;
        this.participantService = participantService;
        this.exclusionGroupService = exclusionGroupService;
        this.eventRepository = eventRepository;
        this.participantRepository = participantRepository;
        this.assignmentRepository = assignmentRepository;
    }

    @Test
    void drawWithFourParticipantsKeepsTheCoupleApart() {
        Event event = createEvent();
        Participant ana = participantService.addByOrganizer(event, "Ana", null);
        Participant bruno = participantService.addByOrganizer(event, "Bruno", null);
        participantService.addByOrganizer(event, "Carla", null);
        exclusionGroupService.create(event, "Casal", Set.of(ana.getId(), bruno.getId()));

        drawService.drawByAdminToken(event.getAdminToken());

        assertEquals(EventStatus.DRAWN, event.getStatus());
        assertFalse(event.isRegistrationOpen());
        assertEquals(1, event.getDrawCount());
        assertNotNull(event.getDrawnAt());
        assertEquals(4, assignmentRepository.countByEvent_Id(event.getId()));

        List<Participant> participants = participantsOf(event);
        assertEquals(4, participants.size());
        Map<UUID, UUID> result = resultOf(event, participants);
        assertCoherent(result, Set.of(ana.getId(), bruno.getId()));
    }

    @Test
    void drawWithThreeParticipantsAndOneCoupleIsImpossibleAndPersistsNothing() {
        Event event = createEvent();
        Participant ana = participantService.addByOrganizer(event, "Ana", null);
        Participant bruno = participantService.addByOrganizer(event, "Bruno", null);
        exclusionGroupService.create(event, "Casal", Set.of(ana.getId(), bruno.getId()));

        DrawImpossibleException error = assertThrows(
                DrawImpossibleException.class,
                () -> drawService.drawByAdminToken(event.getAdminToken()));

        assertEquals(
                DrawImpossibleException.Reason.FORBIDDEN_PAIR_WITH_THREE_PARTICIPANTS,
                error.getReason());
        assertEquals(0, assignmentRepository.countByEvent_Id(event.getId()));
        assertEquals(EventStatus.OPEN, event.getStatus());
        assertEquals(0, event.getDrawCount());
        assertNull(event.getDrawnAt());
        assertTrue(event.isRegistrationOpen());
    }

    @Test
    void drawWithThreeParticipantsWithoutGroupsWorks() {
        Event event = createThreeParticipantEvent();

        drawService.drawByAdminToken(event.getAdminToken());

        assertEquals(EventStatus.DRAWN, event.getStatus());
        assertEquals(1, event.getDrawCount());
        assertEquals(3, assignmentRepository.countByEvent_Id(event.getId()));

        List<Participant> participants = participantsOf(event);
        assertCoherent(resultOf(event, participants), Set.of());
    }

    @Test
    void redrawReplacesAssignmentsAndResetsFirstViewedAt() {
        Event event = createEvent();
        participantService.addByOrganizer(event, "Ana", null);
        participantService.addByOrganizer(event, "Bruno", null);
        participantService.addByOrganizer(event, "Carla", null);
        drawService.drawByAdminToken(event.getAdminToken());

        Participant firstViewer = participantsOf(event).get(0);
        participantService.markFirstViewed(firstViewer);
        assertNotNull(firstViewer.getFirstViewedAt());

        drawService.redrawByAdminToken(event.getAdminToken());

        assertEquals(EventStatus.DRAWN, event.getStatus());
        assertEquals(2, event.getDrawCount());
        assertEquals(4, assignmentRepository.countByEvent_Id(event.getId()));

        entityManager.flush();
        entityManager.clear();

        Event reloaded = eventRepository.findByAdminToken(event.getAdminToken()).orElseThrow();
        assertEquals(EventStatus.DRAWN, reloaded.getStatus());
        assertEquals(2, reloaded.getDrawCount());
        List<Participant> participants = participantRepository.findByEvent_Id(reloaded.getId());
        assertEquals(4, participants.size());
        participants.forEach(participant -> assertNull(
                participant.getFirstViewedAt(),
                "refazer o sorteio zera a primeira visualização de todos"));
        assertCoherent(resultOf(reloaded, participants), Set.of());
    }

    @Test
    void reopenDeletesAssignmentsAndOpensRegistrationAgain() {
        Event event = createThreeParticipantEvent();
        drawService.drawByAdminToken(event.getAdminToken());
        assertEquals(3, assignmentRepository.countByEvent_Id(event.getId()));

        Participant firstViewer = participantsOf(event).get(0);
        participantService.markFirstViewed(firstViewer);
        assertNotNull(firstViewer.getFirstViewedAt());

        Event reopened = eventService.reopenByAdminToken(event.getAdminToken());

        assertEquals(EventStatus.OPEN, reopened.getStatus());
        assertTrue(reopened.isRegistrationOpen());
        assertNull(reopened.getDrawnAt());
        assertEquals(1, reopened.getDrawCount());
        assertEquals(0, assignmentRepository.countByEvent_Id(event.getId()));

        entityManager.flush();
        entityManager.clear();

        Event reloaded = eventRepository.findByAdminToken(event.getAdminToken()).orElseThrow();
        assertEquals(EventStatus.OPEN, reloaded.getStatus());
        assertTrue(reloaded.isRegistrationOpen());
        assertNull(reloaded.getDrawnAt());
        assertEquals(1, reloaded.getDrawCount());
        participantRepository.findByEvent_Id(reloaded.getId())
                .forEach(participant -> assertNull(participant.getFirstViewedAt()));
    }

    @Test
    void drawIsRejectedWhenTheEventIsAlreadyDrawn() {
        Event event = createThreeParticipantEvent();
        drawService.drawByAdminToken(event.getAdminToken());
        int assignments = (int) assignmentRepository.countByEvent_Id(event.getId());

        RuleViolationException error = assertThrows(
                RuleViolationException.class,
                () -> drawService.drawByAdminToken(event.getAdminToken()));

        assertEquals(
                "O sorteio já foi realizado. Use refazer ou reabrir.",
                error.getMessage());
        assertEquals(assignments, assignmentRepository.countByEvent_Id(event.getId()));
        assertEquals(1, event.getDrawCount());
    }

    @Test
    void redrawIsRejectedBeforeTheFirstDraw() {
        Event event = createThreeParticipantEvent();

        RuleViolationException error = assertThrows(
                RuleViolationException.class,
                () -> drawService.redrawByAdminToken(event.getAdminToken()));

        assertEquals("O sorteio ainda não foi realizado.", error.getMessage());
        assertEquals(EventStatus.OPEN, event.getStatus());
        assertEquals(0, assignmentRepository.countByEvent_Id(event.getId()));
    }

    private Event createEvent() {
        return eventService.create(new CreateEventCommand(
                "Família",
                LocalDate.of(2026, 12, 24),
                new BigDecimal("50.00"),
                "Michael"));
    }

    private Event createThreeParticipantEvent() {
        Event event = createEvent();
        participantService.addByOrganizer(event, "Ana", null);
        participantService.addByOrganizer(event, "Bruno", null);
        return event;
    }

    private List<Participant> participantsOf(Event event) {
        return participantRepository.findByEvent_Id(event.getId());
    }

    private Map<UUID, UUID> resultOf(Event event, List<Participant> participants) {
        Map<UUID, UUID> result = new LinkedHashMap<>();
        for (Participant participant : participants) {
            Assignment assignment = assignmentRepository
                    .findByEvent_IdAndGiver_Id(event.getId(), participant.getId())
                    .orElseThrow();
            result.put(participant.getId(), assignment.getReceiver().getId());
        }
        return result;
    }

    private static void assertCoherent(Map<UUID, UUID> result, Set<UUID> couple) {
        assertEquals(
                result.size(),
                new HashSet<>(result.values()).size(),
                "cada pessoa recebe exatamente uma vez");
        for (Map.Entry<UUID, UUID> entry : result.entrySet()) {
            assertNotEquals(entry.getKey(), entry.getValue(), "ninguém tira a si mesmo");
            assertFalse(
                    couple.contains(entry.getKey()) && couple.contains(entry.getValue()),
                    "o casal não pode se tirar");
        }
    }
}
