package br.com.michael.amigosecreto.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.michael.amigosecreto.common.NotFoundException;
import br.com.michael.amigosecreto.participant.Participant;
import br.com.michael.amigosecreto.participant.ParticipantRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EventServiceTest {

    private final EventService eventService;
    private final EventRepository eventRepository;
    private final ParticipantRepository participantRepository;

    @Autowired
    EventServiceTest(
            EventService eventService,
            EventRepository eventRepository,
            ParticipantRepository participantRepository) {
        this.eventService = eventService;
        this.eventRepository = eventRepository;
        this.participantRepository = participantRepository;
    }

    @Test
    void createPersistsOpenEventWithOrganizerAndPrefixedTokens() {
        CreateEventCommand command = new CreateEventCommand(
                "Família",
                LocalDate.of(2026, 12, 24),
                new BigDecimal("50.00"),
                "Michael");

        Event event = eventService.create(command);

        assertNotNull(event.getId());
        assertEquals("Família", event.getName());
        assertEquals(LocalDate.of(2026, 12, 24), event.getEventDate());
        assertEquals(new BigDecimal("50.00"), event.getSuggestedValue());
        assertEquals(EventStatus.OPEN, event.getStatus());
        assertTrue(event.isRegistrationOpen());
        assertEquals(0, event.getDrawCount());
        assertNotNull(event.getCreatedAt());

        Event persisted = eventRepository.findById(event.getId()).orElseThrow();
        assertTrue(persisted.getAdminToken().startsWith("adm_"));
        assertTrue(persisted.getInviteCode().startsWith("inv_"));
        assertNotEquals(persisted.getAdminToken(), persisted.getInviteCode());

        Participant organizer = participantRepository
                .findByEvent_IdAndOrganizerTrue(event.getId())
                .orElseThrow();
        assertEquals("Michael", organizer.getName());
        assertTrue(organizer.isOrganizer());
        assertTrue(organizer.getAccessToken().startsWith("p_"));
        assertEquals(event.getId(), organizer.getEvent().getId());
        assertNotEquals(persisted.getAdminToken(), organizer.getAccessToken());
        assertNotEquals(persisted.getInviteCode(), organizer.getAccessToken());
    }

    @Test
    void findsEventByAdminTokenAndInviteCode() {
        Event event = eventService.create(defaultCommand());

        Event byAdminToken = eventService.byAdminToken(event.getAdminToken());
        Event byInviteCode = eventService.byInviteCode(event.getInviteCode());

        assertEquals(event.getId(), byAdminToken.getId());
        assertEquals(event.getId(), byInviteCode.getId());
    }

    @Test
    void unknownTokensRaiseNotFoundWithoutEchoingTheValue() {
        NotFoundException adminError = assertThrows(
                NotFoundException.class,
                () -> eventService.byAdminToken("adm_inexistente"));
        assertEquals("Evento não encontrado.", adminError.getMessage());
        assertFalse(adminError.getMessage().contains("adm_inexistente"));

        NotFoundException inviteError = assertThrows(
                NotFoundException.class,
                () -> eventService.byInviteCode("inv_inexistente"));
        assertEquals("Evento não encontrado.", inviteError.getMessage());
        assertFalse(inviteError.getMessage().contains("inv_inexistente"));
    }

    private static CreateEventCommand defaultCommand() {
        return new CreateEventCommand(
                "Trabalho",
                LocalDate.of(2026, 12, 20),
                null,
                "Ana");
    }
}
