package br.com.michael.amigosecreto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.michael.amigosecreto.draw.Assignment;
import br.com.michael.amigosecreto.draw.AssignmentRepository;
import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.event.EventRepository;
import br.com.michael.amigosecreto.exclusion.ExclusionGroup;
import br.com.michael.amigosecreto.exclusion.ExclusionGroupRepository;
import br.com.michael.amigosecreto.participant.Participant;
import br.com.michael.amigosecreto.participant.ParticipantRepository;
import br.com.michael.amigosecreto.participant.dto.ParticipantRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PersistenceMappingTest {

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private ParticipantRepository participantRepository;

    @Autowired
    private ExclusionGroupRepository exclusionGroupRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Test
    void persistsDomainGraphAndValidatesSchema() {
        Event event = new Event(
                "Família",
                LocalDate.of(2026, 12, 24),
                new BigDecimal("50.00"),
                "adm_test",
                "inv_test",
                Instant.now());
        eventRepository.saveAndFlush(event);

        Participant ana = new Participant(event, "Ana", "p_ana", true);
        Participant bruno = new Participant(event, "Bruno", "p_bruno", false);
        participantRepository.saveAllAndFlush(List.of(ana, bruno));

        ExclusionGroup group = new ExclusionGroup(event, "Casal");
        group.addMember(ana);
        group.addMember(bruno);
        exclusionGroupRepository.saveAndFlush(group);

        Assignment assignment = new Assignment(event, ana, bruno);
        assignmentRepository.saveAndFlush(assignment);

        assertTrue(eventRepository.findByAdminToken("adm_test").isPresent());
        assertTrue(eventRepository.findByInviteCode("inv_test").isPresent());
        assertEquals(2, participantRepository.countByEvent_Id(event.getId()));
        assertEquals(1, exclusionGroupRepository.findByEventIdWithMembers(event.getId()).size());
        assertEquals(1, assignmentRepository.countByEvent_Id(event.getId()));
        assertTrue(assignmentRepository
                .findByEvent_IdAndGiver_Id(event.getId(), ana.getId())
                .isPresent());

        List<ParticipantRow> rows = participantRepository.findRowsByEventId(event.getId());
        assertEquals(2, rows.size());
        assertEquals("Ana", rows.get(0).name());
    }

    @Test
    void deletingEventCascadesToParticipantsGroupsAndAssignments() {
        Event event = new Event(
                "Trabalho",
                LocalDate.of(2026, 12, 20),
                null,
                "adm_cascade",
                "inv_cascade",
                Instant.now());
        eventRepository.saveAndFlush(event);

        Participant ana = new Participant(event, "Ana", "p_cascade_ana", true);
        Participant bruno = new Participant(event, "Bruno", "p_cascade_bruno", false);
        participantRepository.saveAllAndFlush(List.of(ana, bruno));

        ExclusionGroup group = new ExclusionGroup(event, "Casal");
        group.addMember(ana);
        group.addMember(bruno);
        exclusionGroupRepository.saveAndFlush(group);

        assignmentRepository.saveAndFlush(new Assignment(event, ana, bruno));

        eventRepository.deleteByIdBulk(event.getId());
        eventRepository.flush();

        assertEquals(0, participantRepository.count());
        assertEquals(0, exclusionGroupRepository.count());
        assertEquals(0, assignmentRepository.count());
    }
}
