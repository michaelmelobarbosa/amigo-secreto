package br.com.michael.amigosecreto.exclusion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.michael.amigosecreto.common.RuleViolationException;
import br.com.michael.amigosecreto.event.CreateEventCommand;
import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.event.EventRepository;
import br.com.michael.amigosecreto.event.EventService;
import br.com.michael.amigosecreto.exclusion.dto.ExclusionGroupView;
import br.com.michael.amigosecreto.participant.Participant;
import br.com.michael.amigosecreto.participant.ParticipantService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grupos de exclusão: criação, remoção e projeção da tela. Cada teste roda em
 * transação própria e monta o evento de que precisa.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ExclusionGroupServiceTest {

    private static final String TOO_FEW_MEMBERS =
            "Um grupo de exclusão precisa de pelo menos 2 participantes distintos.";
    private static final String FOREIGN_MEMBER =
            "Todos os membros do grupo precisam ser participantes deste evento.";

    private final ExclusionGroupService exclusionGroupService;
    private final EventService eventService;
    private final ParticipantService participantService;
    private final EventRepository eventRepository;

    @Autowired
    ExclusionGroupServiceTest(
            ExclusionGroupService exclusionGroupService,
            EventService eventService,
            ParticipantService participantService,
            EventRepository eventRepository) {
        this.exclusionGroupService = exclusionGroupService;
        this.eventService = eventService;
        this.participantService = participantService;
        this.eventRepository = eventRepository;
    }

    @Test
    void createsGroupWithTwoMembersFromTheEvent() {
        Event event = createEvent("Família", "Michael");
        Participant ana = participantService.addByOrganizer(event, "Ana", null);
        Participant bruno = participantService.addByOrganizer(event, "Bruno", null);

        ExclusionGroup group = exclusionGroupService.create(
                event, "Casal", Set.of(ana.getId(), bruno.getId()));

        assertNotNull(group.getId());
        assertEquals("Casal", group.getLabel());
        assertEquals(2, group.getMembers().size());

        List<ExclusionGroupView> views = exclusionGroupService.viewsFor(event);
        assertEquals(1, views.size());
        assertEquals(group.getId(), views.get(0).id());
        assertEquals(List.of("Ana", "Bruno"), views.get(0).memberNames());
    }

    @Test
    void blankLabelBecomesNull() {
        Event event = createEvent("Família", "Michael");
        Participant ana = participantService.addByOrganizer(event, "Ana", null);
        Participant bruno = participantService.addByOrganizer(event, "Bruno", null);

        ExclusionGroup group = exclusionGroupService.create(
                event, "   ", Set.of(ana.getId(), bruno.getId()));

        assertNull(group.getLabel());
        assertNull(exclusionGroupService.viewsFor(event).get(0).label());
    }

    @Test
    void rejectsGroupWithFewerThanTwoMembers() {
        Event event = createEvent("Família", "Michael");
        Participant ana = participantService.addByOrganizer(event, "Ana", null);

        RuleViolationException error = assertThrows(
                RuleViolationException.class,
                () -> exclusionGroupService.create(event, "Casal", Set.of(ana.getId())));

        assertEquals(TOO_FEW_MEMBERS, error.getMessage());
        assertTrue(exclusionGroupService.viewsFor(event).isEmpty());
    }

    @Test
    void rejectsMemberFromAnotherEvent() {
        Event event = createEvent("Família", "Michael");
        Event otherEvent = createEvent("Trabalho", "Ana");
        Participant fromEvent = participantService.addByOrganizer(event, "Bruno", null);
        Participant fromOtherEvent = participantService.addByOrganizer(otherEvent, "Carla", null);

        RuleViolationException error = assertThrows(
                RuleViolationException.class,
                () -> exclusionGroupService.create(
                        event,
                        "Casal",
                        Set.of(fromEvent.getId(), fromOtherEvent.getId())));

        assertEquals(FOREIGN_MEMBER, error.getMessage());
        assertTrue(exclusionGroupService.viewsFor(event).isEmpty());
        assertTrue(exclusionGroupService.viewsFor(otherEvent).isEmpty());
    }

    @Test
    void removesGroupFromTheEvent() {
        Event event = createEvent("Família", "Michael");
        Participant ana = participantService.addByOrganizer(event, "Ana", null);
        Participant bruno = participantService.addByOrganizer(event, "Bruno", null);
        ExclusionGroup group = exclusionGroupService.create(
                event, "Casal", Set.of(ana.getId(), bruno.getId()));

        exclusionGroupService.remove(event, group.getId());

        assertTrue(exclusionGroupService.viewsFor(event).isEmpty());
    }

    @Test
    void viewsForSortsMemberNames() {
        Event event = createEvent("Família", "Michael");
        Participant carla = participantService.addByOrganizer(event, "Carla", null);
        Participant ana = participantService.addByOrganizer(event, "Ana", null);
        Participant bruno = participantService.addByOrganizer(event, "Bruno", null);

        exclusionGroupService.create(
                event,
                null,
                Set.of(carla.getId(), ana.getId(), bruno.getId()));

        ExclusionGroupView view = exclusionGroupService.viewsFor(event).get(0);

        assertEquals(List.of("Ana", "Bruno", "Carla"), view.memberNames());
        assertNull(view.label());
    }

    @Test
    void createAndRemoveAreRejectedAfterDraw() {
        Event event = createEvent("Família", "Michael");
        Participant ana = participantService.addByOrganizer(event, "Ana", null);
        Participant bruno = participantService.addByOrganizer(event, "Bruno", null);
        ExclusionGroup group = exclusionGroupService.create(
                event, "Casal", Set.of(ana.getId(), bruno.getId()));

        event.markDrawn(Instant.now());
        eventRepository.saveAndFlush(event);

        RuleViolationException createError = assertThrows(
                RuleViolationException.class,
                () -> exclusionGroupService.create(
                        event, "Outro", Set.of(ana.getId(), bruno.getId())));
        assertEquals(
                "Grupos de exclusão só podem ser criados antes do sorteio.",
                createError.getMessage());

        RuleViolationException removeError = assertThrows(
                RuleViolationException.class,
                () -> exclusionGroupService.remove(event, group.getId()));
        assertEquals(
                "Grupos de exclusão só podem ser removidos antes do sorteio.",
                removeError.getMessage());

        List<ExclusionGroupView> views = exclusionGroupService.viewsFor(event);
        assertEquals(1, views.size());
        assertEquals(group.getId(), views.get(0).id());
    }

    @Test
    void rejectsCreatingMoreThanMaxGroupsPerEvent() {
        Event event = createEvent("Família", "Michael");
        Participant ana = participantService.addByOrganizer(event, "Ana", null);
        Participant bruno = participantService.addByOrganizer(event, "Bruno", null);
        Set<java.util.UUID> members = Set.of(ana.getId(), bruno.getId());

        for (int i = 0; i < ExclusionGroupService.MAX_GROUPS_PER_EVENT; i++) {
            exclusionGroupService.create(event, "Grupo " + i, members);
        }

        RuleViolationException error = assertThrows(
                RuleViolationException.class,
                () -> exclusionGroupService.create(event, "Excedente", members));
        assertEquals(
                "O evento já atingiu o limite de "
                        + ExclusionGroupService.MAX_GROUPS_PER_EVENT
                        + " grupos de exclusão.",
                error.getMessage());
    }

    private Event createEvent(String name, String organizerName) {
        return eventService.create(new CreateEventCommand(
                name,
                LocalDate.of(2026, 12, 24),
                new BigDecimal("50.00"),
                organizerName));
    }
}
