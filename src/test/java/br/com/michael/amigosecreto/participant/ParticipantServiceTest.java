package br.com.michael.amigosecreto.participant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.michael.amigosecreto.common.NotFoundException;
import br.com.michael.amigosecreto.common.RuleViolationException;
import br.com.michael.amigosecreto.draw.Assignment;
import br.com.michael.amigosecreto.draw.AssignmentRepository;
import br.com.michael.amigosecreto.event.CreateEventCommand;
import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.event.EventRepository;
import br.com.michael.amigosecreto.event.EventService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regras de inscrição, remoção, wishlist e primeira visualização do
 * participante. Cada teste roda em transação própria e cria o evento de que
 * precisa.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ParticipantServiceTest {

    private final ParticipantService participantService;
    private final EventService eventService;
    private final ParticipantRepository participantRepository;
    private final EventRepository eventRepository;
    private final AssignmentRepository assignmentRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    ParticipantServiceTest(
            ParticipantService participantService,
            EventService eventService,
            ParticipantRepository participantRepository,
            EventRepository eventRepository,
            AssignmentRepository assignmentRepository) {
        this.participantService = participantService;
        this.eventService = eventService;
        this.participantRepository = participantRepository;
        this.eventRepository = eventRepository;
        this.assignmentRepository = assignmentRepository;
    }

    @Test
    void byAccessTokenFindsParticipantAndHidesUnknownToken() {
        Event event = createEvent();
        Participant participant = participantService.joinByInvite(event, "Bia", null, null);

        Participant found = participantService.byAccessToken(participant.getAccessToken());

        assertEquals(participant.getId(), found.getId());

        NotFoundException error = assertThrows(
                NotFoundException.class,
                () -> participantService.byAccessToken("p_inexistente"));
        assertEquals("Participante não encontrado.", error.getMessage());
        assertFalse(error.getMessage().contains("p_inexistente"));
    }

    @Test
    void addByOrganizerCreatesRegularParticipantWithGeneratedToken() {
        Event event = createEvent();

        Participant participant = participantService.addByOrganizer(
                event, "Ana", "  Ana@Example.COM  ", "Livro");

        assertNotNull(participant.getId());
        assertEquals("Ana", participant.getName());
        assertEquals("Livro", participant.getWishlist());
        assertEquals("ana@example.com", participant.getEmail());
        assertFalse(participant.isOrganizer());
        assertTrue(participant.getAccessToken().startsWith("p_"));
        assertEquals(event.getId(), participant.getEvent().getId());
        assertEquals(2, participantRepository.countByEvent_Id(event.getId()));
    }

    @Test
    void addByOrganizerStillWorksWhenPublicRegistrationIsClosed() {
        Event event = createEvent();
        eventService.setRegistrationByAdminToken(event.getAdminToken(), false);

        Participant participant = participantService.addByOrganizer(event, "Ana", null, null);

        assertNotNull(participant.getId());
        assertEquals(2, participantRepository.countByEvent_Id(event.getId()));
    }

    @Test
    void emailIsStoredNormalized() {
        Event event = createEvent();

        Participant participant = participantService.joinByInvite(
                event, "Bia", "  Bia.Silva@Example.COM  ", null);

        entityManager.flush();
        entityManager.clear();

        assertEquals(
                "bia.silva@example.com",
                participantRepository.findById(participant.getId()).orElseThrow().getEmail());
    }

    @Test
    void emailIsOptionalAndBlankBecomesNull() {
        Event event = createEvent();

        Participant withoutEmail = participantService.joinByInvite(event, "Bia", null, null);
        Participant blankEmail = participantService.addByOrganizer(event, "Carla", "   ", null);

        assertNull(withoutEmail.getEmail());
        assertNull(blankEmail.getEmail());

        entityManager.flush();
        entityManager.clear();

        assertNull(participantRepository.findById(withoutEmail.getId()).orElseThrow().getEmail());
        assertNull(participantRepository.findById(blankEmail.getId()).orElseThrow().getEmail());
    }

    @Test
    void emailLongerThanColumnIsRejectedWithFriendlyMessage() {
        Event event = createEvent();

        RuleViolationException error = assertThrows(
                RuleViolationException.class,
                () -> participantService.joinByInvite(
                        event, "Bia", "a".repeat(250) + "@x.com", null));

        assertEquals("O e-mail deve ter até 254 caracteres.", error.getMessage());
        assertEquals(1, participantRepository.countByEvent_Id(event.getId()));
    }

    @Test
    void duplicateEmailWithinSameEventIsRejectedAcrossJoinAndManualAdd() {
        Event event = createEvent();
        participantService.joinByInvite(event, "Ana", "Ana@Example.COM", null);

        RuleViolationException manualError = assertThrows(
                RuleViolationException.class,
                () -> participantService.addByOrganizer(event, "Ana 2", "  ana@example.com  ", null));
        assertEquals("Já existe um participante com este e-mail neste evento.", manualError.getMessage());

        RuleViolationException joinError = assertThrows(
                RuleViolationException.class,
                () -> participantService.joinByInvite(event, "Ana 3", "ANA@EXAMPLE.COM", null));
        assertEquals("Já existe um participante com este e-mail neste evento.", joinError.getMessage());
        assertEquals(2, participantRepository.countByEvent_Id(event.getId()));

        Event otherEvent = createEvent();
        Participant inOtherEvent = participantService.joinByInvite(
                otherEvent, "Ana", "ana@example.com", null);
        assertNotNull(inOtherEvent.getId());
    }

    @Test
    void addByOrganizerIsRejectedAfterDraw() {
        Event event = createEvent();
        event.markDrawn(Instant.now());
        eventRepository.saveAndFlush(event);

        RuleViolationException error = assertThrows(
                RuleViolationException.class,
                () -> participantService.addByOrganizer(event, "Ana", null, null));
        assertEquals(
                "Participantes só podem ser adicionados antes do sorteio.",
                error.getMessage());
    }

    @Test
    void joinByInviteAddsParticipantWhenRegistrationIsOpen() {
        Event event = createEvent();

        Participant participant = participantService.joinByInvite(
                event, "Bia", "Bia@Example.com", "Fone");

        assertNotNull(participant.getId());
        assertEquals("Bia", participant.getName());
        assertEquals("Fone", participant.getWishlist());
        assertEquals("bia@example.com", participant.getEmail());
        assertFalse(participant.isOrganizer());
        assertTrue(participant.getAccessToken().startsWith("p_"));
        assertEquals(2, participantRepository.countByEvent_Id(event.getId()));
    }

    @Test
    void joinByInviteIsRejectedWhenRegistrationIsClosed() {
        Event event = createEvent();
        eventService.setRegistrationByAdminToken(event.getAdminToken(), false);
        long before = participantRepository.countByEvent_Id(event.getId());

        RuleViolationException error = assertThrows(
                RuleViolationException.class,
                () -> participantService.joinByInvite(event, "Bia", null, null));

        assertEquals("As inscrições estão encerradas.", error.getMessage());
        assertEquals(before, participantRepository.countByEvent_Id(event.getId()));
    }

    @Test
    void joinByInviteIsRejectedAfterDraw() {
        Event event = createEvent();
        event.markDrawn(Instant.now());
        eventRepository.saveAndFlush(event);

        RuleViolationException error = assertThrows(
                RuleViolationException.class,
                () -> participantService.joinByInvite(event, "Bia", null, null));

        assertEquals("As inscrições estão encerradas.", error.getMessage());
    }

    @Test
    void participantLimitIsEnforcedAtOneHundred() {
        Event event = createEvent();
        // O organizador já ocupa uma das 100 vagas.
        for (int i = 0; i < 99; i++) {
            participantService.addByOrganizer(event, "Convidado " + i, null, null);
        }
        assertEquals(100, participantRepository.countByEvent_Id(event.getId()));

        RuleViolationException error = assertThrows(
                RuleViolationException.class,
                () -> participantService.joinByInvite(event, "Excedente", null, null));

        assertEquals(
                "O evento já atingiu o limite de 100 participantes.",
                error.getMessage());
        assertEquals(100, participantRepository.countByEvent_Id(event.getId()));
    }

    @Test
    void removeDeletesParticipantFromEvent() {
        Event event = createEvent();
        Participant participant = participantService.joinByInvite(event, "Bia", null, null);
        UUID participantId = participant.getId();

        participantService.remove(event, participantId);

        assertTrue(participantRepository.findById(participantId).isEmpty());
        assertEquals(1, participantRepository.countByEvent_Id(event.getId()));
    }

    @Test
    void removeRejectsOrganizer() {
        Event event = createEvent();
        Participant organizer = eventService.organizerOf(event);

        RuleViolationException error = assertThrows(
                RuleViolationException.class,
                () -> participantService.remove(event, organizer.getId()));

        assertEquals("O organizador não pode ser removido.", error.getMessage());
        assertTrue(participantRepository.findById(organizer.getId()).isPresent());
    }

    @Test
    void removeDoesNotTouchParticipantFromAnotherEvent() {
        Event event = createEvent();
        Event otherEvent = eventService.create(new CreateEventCommand(
                "Trabalho", LocalDate.of(2026, 12, 20), null, "Ana"));
        Participant fromOtherEvent =
                participantService.joinByInvite(otherEvent, "Bia", "bia@example.com", null);

        assertThrows(
                NotFoundException.class,
                () -> participantService.remove(event, fromOtherEvent.getId()));

        assertTrue(participantRepository.findById(fromOtherEvent.getId()).isPresent());
    }

    @Test
    void removeIsRejectedAfterDraw() {
        Event event = createEvent();
        Participant participant = participantService.joinByInvite(event, "Bia", null, null);
        event.markDrawn(Instant.now());
        eventRepository.saveAndFlush(event);

        RuleViolationException error = assertThrows(
                RuleViolationException.class,
                () -> participantService.remove(event, participant.getId()));

        assertEquals(
                "Participantes só podem ser removidos antes do sorteio.",
                error.getMessage());
        assertTrue(participantRepository.findById(participant.getId()).isPresent());
    }

    @Test
    void updateWishlistPersistsValueAndTurnsBlankIntoNull() {
        Event event = createEvent();
        Participant participant = participantService.joinByInvite(event, "Bia", null, null);

        participantService.updateWishlist(participant, "Fone de ouvido");
        assertEquals(
                "Fone de ouvido",
                participantRepository.findById(participant.getId()).orElseThrow().getWishlist());

        participantService.updateWishlist(participant, "   ");
        assertNull(
                participantRepository.findById(participant.getId()).orElseThrow().getWishlist());

        participantService.updateWishlist(participant, null);
        assertNull(participant.getWishlist());
    }

    @Test
    void updateWishlistIsAllowedAfterDraw() {
        Event event = createEvent();
        Participant participant = participantService.joinByInvite(event, "Bia", null, null);
        event.markDrawn(Instant.now());
        eventRepository.saveAndFlush(event);

        participantService.updateWishlist(participant, "Livro");

        assertEquals("Livro", participant.getWishlist());
    }

    @Test
    void updateWishlistOfMissingParticipantRaisesNotFound() {
        Event event = createEvent();
        Participant participant = participantService.joinByInvite(event, "Bia", null, null);
        participantService.remove(event, participant.getId());

        assertThrows(
                NotFoundException.class,
                () -> participantService.updateWishlist(participant, "Livro"));
    }

    @Test
    void markFirstViewedStoresTimestampOnceAndIsIdempotent() {
        Event event = createEvent();
        Participant participant = participantService.joinByInvite(event, "Bia", null, null);

        participantService.markFirstViewed(participant);
        assertNotNull(participant.getFirstViewedAt());

        entityManager.flush();
        entityManager.clear();
        Participant reloaded = participantRepository.findById(participant.getId()).orElseThrow();
        Instant first = reloaded.getFirstViewedAt();
        assertNotNull(first);

        participantService.markFirstViewed(reloaded);
        entityManager.flush();
        entityManager.clear();
        Participant afterSecond =
                participantRepository.findById(participant.getId()).orElseThrow();

        assertEquals(first, afterSecond.getFirstViewedAt());
    }

    @Test
    void resultForReturnsReceiverNameAndWishlist() {
        Event event = createEvent();
        Participant giver = eventService.organizerOf(event);
        Participant receiver = participantService.joinByInvite(event, "Bia", null, "Livro");
        assignmentRepository.save(new Assignment(event, giver, receiver));

        Optional<ParticipantResult> result = participantService.resultFor(giver);

        assertTrue(result.isPresent());
        assertEquals("Bia", result.orElseThrow().receiverName());
        assertEquals("Livro", result.orElseThrow().receiverWishlist());
    }

    @Test
    void resultForIsEmptyWithoutAssignment() {
        Event event = createEvent();
        Participant organizer = eventService.organizerOf(event);

        assertTrue(participantService.resultFor(organizer).isEmpty());
    }

    @Test
    void resultForKeepsNullWishlist() {
        Event event = createEvent();
        Participant giver = eventService.organizerOf(event);
        Participant receiver = participantService.joinByInvite(event, "Bia", null, null);
        assignmentRepository.save(new Assignment(event, giver, receiver));

        ParticipantResult result = participantService.resultFor(giver).orElseThrow();

        assertEquals("Bia", result.receiverName());
        assertNull(result.receiverWishlist());
    }

    private Event createEvent() {
        return eventService.create(new CreateEventCommand(
                "Família",
                LocalDate.of(2026, 12, 24),
                new BigDecimal("50.00"),
                "Michael"));
    }
}
