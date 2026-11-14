package br.com.michael.amigosecreto.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.michael.amigosecreto.common.NotFoundException;
import br.com.michael.amigosecreto.common.RuleViolationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operações administrativas do evento: abrir/fechar inscrições e regenerar o
 * código de convite. Cada teste roda em transação própria.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EventServiceAdminTest {

    private final EventService eventService;
    private final EventRepository eventRepository;

    @Autowired
    EventServiceAdminTest(EventService eventService, EventRepository eventRepository) {
        this.eventService = eventService;
        this.eventRepository = eventRepository;
    }

    @Test
    void togglesRegistrationOpenBeforeDraw() {
        Event event = createEvent();
        assertTrue(event.isRegistrationOpen());

        Event closed =
                eventService.setRegistrationByAdminToken(event.getAdminToken(), false);

        assertFalse(closed.isRegistrationOpen());
        assertFalse(eventRepository.findByAdminToken(event.getAdminToken())
                .orElseThrow()
                .isRegistrationOpen());

        Event reopened =
                eventService.setRegistrationByAdminToken(event.getAdminToken(), true);

        assertTrue(reopened.isRegistrationOpen());
        assertTrue(eventRepository.findByAdminToken(event.getAdminToken())
                .orElseThrow()
                .isRegistrationOpen());
    }

    @Test
    void regenerateInviteCodeInvalidatesTheOldCode() {
        Event event = createEvent();
        String oldCode = event.getInviteCode();

        Event updated = eventService.regenerateInviteCodeByAdminToken(event.getAdminToken());
        String newCode = updated.getInviteCode();

        assertNotEquals(oldCode, newCode);
        assertTrue(newCode.startsWith("inv_"));
        assertEquals(newCode, eventRepository.findByAdminToken(event.getAdminToken())
                .orElseThrow()
                .getInviteCode());
        assertTrue(eventRepository.findByInviteCode(oldCode).isEmpty());
        assertTrue(eventRepository.findByInviteCode(newCode).isPresent());
        assertThrows(NotFoundException.class, () -> eventService.byInviteCode(oldCode));
    }

    @Test
    void setRegistrationIsRejectedAfterDraw() {
        Event event = createEvent();
        event.markDrawn(Instant.now());
        eventRepository.saveAndFlush(event);
        assertFalse(event.isRegistrationOpen());

        RuleViolationException error = assertThrows(
                RuleViolationException.class,
                () -> eventService.setRegistrationByAdminToken(event.getAdminToken(), true));

        assertEquals(
                "As inscrições só podem ser alteradas antes do sorteio.",
                error.getMessage());
        assertFalse(eventRepository.findByAdminToken(event.getAdminToken())
                .orElseThrow()
                .isRegistrationOpen());
    }

    @Test
    void unknownAdminTokenRaisesNotFoundWithoutEchoingTheToken() {
        NotFoundException error = assertThrows(
                NotFoundException.class,
                () -> eventService.setRegistrationByAdminToken("adm_inexistente", false));

        assertEquals("Evento não encontrado.", error.getMessage());
        assertFalse(error.getMessage().contains("adm_inexistente"));
    }

    private Event createEvent() {
        return eventService.create(new CreateEventCommand(
                "Família",
                LocalDate.of(2026, 12, 24),
                new BigDecimal("50.00"),
                "Michael"));
    }
}
