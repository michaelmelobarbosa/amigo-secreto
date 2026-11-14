package br.com.michael.amigosecreto.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import br.com.michael.amigosecreto.draw.Assignment;
import br.com.michael.amigosecreto.draw.AssignmentRepository;
import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.event.EventRepository;
import br.com.michael.amigosecreto.event.EventStatus;
import br.com.michael.amigosecreto.exclusion.ExclusionGroup;
import br.com.michael.amigosecreto.exclusion.ExclusionGroupRepository;
import br.com.michael.amigosecreto.participant.Participant;
import br.com.michael.amigosecreto.participant.ParticipantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fluxo web do Bloco 4: grupos de exclusão, sorteio, refazer e reabrir pelo
 * painel, e a página do participante depois do sorteio.
 *
 * <p>Cada teste roda na própria transação e monta o cenário de que precisa. O
 * GET inicial da home grava o token CSRF na sessão e os POSTs reenviam o mesmo
 * valor em {@code _csrf}. As leituras de estado que precisam valer como banco
 * passam por flush e clear: refazer e reabrir zeram a primeira visualização com
 * update em massa, que não atualiza as entidades já gerenciadas.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class Block4WebIntegrationTest {

    private static final String CSRF_SESSION_ATTRIBUTE = "br.com.michael.amigosecreto.csrfToken";
    private static final String EVENT_NAME = "Família";
    private static final String ORGANIZER_NAME = "Michael";

    private final MockMvc mockMvc;
    private final EventRepository eventRepository;
    private final ParticipantRepository participantRepository;
    private final ExclusionGroupRepository exclusionGroupRepository;
    private final AssignmentRepository assignmentRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    Block4WebIntegrationTest(
            MockMvc mockMvc,
            EventRepository eventRepository,
            ParticipantRepository participantRepository,
            ExclusionGroupRepository exclusionGroupRepository,
            AssignmentRepository assignmentRepository) {
        this.mockMvc = mockMvc;
        this.eventRepository = eventRepository;
        this.participantRepository = participantRepository;
        this.exclusionGroupRepository = exclusionGroupRepository;
        this.assignmentRepository = assignmentRepository;
    }

    @Test
    void adminDrawsThroughDashboardAndPersistsCyclicAssignmentsRespectingTheCouple()
            throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        Event event = createEventThroughForm(session);
        Participant ana = addParticipant(session, event, "Ana");
        Participant bruno = addParticipant(session, event, "Bruno");
        addParticipant(session, event, "Carla");
        createExclusionGroup(session, event, "Casal", ana, bruno);

        String dashboardBeforeDraw = mockMvc.perform(
                        get("/admin/{adminToken}", event.getAdminToken()).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(dashboardBeforeDraw).contains("Casal", "Ana", "Bruno", "Carla");

        draw(session, event);

        String adminToken = event.getAdminToken();
        Event drawn = reloadEvent(adminToken);
        assertThat(drawn.getStatus()).isEqualTo(EventStatus.DRAWN);
        assertThat(drawn.getDrawCount()).isEqualTo(1);
        assertThat(drawn.isRegistrationOpen()).isFalse();
        assertThat(assignmentRepository.countByEvent_Id(drawn.getId())).isEqualTo(4);

        List<Participant> participants = participantRepository.findByEvent_Id(drawn.getId());
        assertThat(participants).hasSize(4);
        Map<UUID, UUID> assignments = assignmentsByGiver(drawn.getId(), participants);
        assertThat(assignments).hasSize(4);
        assertThat(new HashSet<>(assignments.values())).hasSize(4);
        assignments.forEach(
                (giver, receiver) -> assertThat(receiver).isNotEqualTo(giver));

        Map<String, UUID> idsByName = participants.stream()
                .collect(Collectors.toMap(Participant::getName, Participant::getId));
        assertThat(assignments.get(idsByName.get("Ana"))).isNotEqualTo(idsByName.get("Bruno"));
        assertThat(assignments.get(idsByName.get("Bruno"))).isNotEqualTo(idsByName.get("Ana"));
    }

    @Test
    void drawnDashboardShowsDrawActionsAndNeverExposesAssignmentData() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        Event event = createEventThroughForm(session);
        Participant ana = addParticipant(session, event, "Ana");
        Participant bruno = addParticipant(session, event, "Bruno");
        addParticipant(session, event, "Carla");
        createExclusionGroup(session, event, "Casal", ana, bruno);
        draw(session, event);

        String adminToken = event.getAdminToken();
        entityManager.flush();
        entityManager.clear();

        String dashboard = mockMvc.perform(get("/admin/{adminToken}", adminToken).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"))
                .andExpect(content().string(containsString("Sorteio realizado")))
                .andExpect(content().string(containsString("Refazer sorteio")))
                .andExpect(content().string(containsString("Reabrir e editar")))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(dashboard.toLowerCase(Locale.ROOT))
                .doesNotContain("você tirou")
                .doesNotContain("assignment")
                .doesNotContain("giver")
                .doesNotContain("receiver");
    }

    @Test
    void participantPageAfterDrawShowsOwnReceiverAndMarksFirstView() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        Event event = createEventThroughForm(session);
        Participant ana = addParticipant(session, event, "Ana");
        addParticipant(session, event, "Bruno");
        draw(session, event);

        UUID eventId = event.getId();
        String anaToken = ana.getAccessToken();
        Assignment anaAssignment = assignmentRepository
                .findByEvent_IdAndGiver_Id(eventId, ana.getId())
                .orElseThrow(() -> new AssertionError("Ana não recebeu um sorteio"));
        Participant receiver = anaAssignment.getReceiver();
        String notReceiverName = participantRepository.findByEvent_Id(eventId).stream()
                .filter(candidate -> !candidate.getId().equals(ana.getId()))
                .filter(candidate -> !candidate.getId().equals(receiver.getId()))
                .map(Participant::getName)
                .findFirst()
                .orElseThrow();

        mockMvc.perform(get("/p/{accessToken}", anaToken))
                .andExpect(status().isOk())
                .andExpect(view().name("participant/view"))
                .andExpect(content().string(containsString("Você tirou")))
                .andExpect(content().string(containsString(receiver.getName())))
                .andExpect(content().string(not(containsString(notReceiverName))))
                .andExpect(content().string(not(containsString("Aguarde o sorteio"))));

        entityManager.flush();
        entityManager.clear();
        Participant reloaded = participantRepository.findByAccessToken(anaToken).orElseThrow();
        assertThat(reloaded.getFirstViewedAt()).isNotNull();
    }

    @Test
    void redrawReplacesAssignmentsIncrementsDrawCountAndClearsFirstViews() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        Event event = createEventThroughForm(session);
        addParticipant(session, event, "Ana");
        addParticipant(session, event, "Bruno");
        addParticipant(session, event, "Carla");
        draw(session, event);

        String adminToken = event.getAdminToken();
        UUID eventId = event.getId();
        viewEveryParticipantPage(eventId);
        entityManager.flush();
        entityManager.clear();
        assertThat(participantRepository.findByEvent_Id(eventId))
                .allSatisfy(participant -> assertThat(participant.getFirstViewedAt()).isNotNull());

        redraw(session, event);

        entityManager.flush();
        entityManager.clear();
        Event redrawn = eventRepository.findByAdminToken(adminToken).orElseThrow();
        assertThat(redrawn.getStatus()).isEqualTo(EventStatus.DRAWN);
        assertThat(redrawn.getDrawCount()).isEqualTo(2);
        assertThat(assignmentRepository.countByEvent_Id(eventId)).isEqualTo(4);
        assertThat(participantRepository.findByEvent_Id(eventId))
                .allSatisfy(participant -> assertThat(participant.getFirstViewedAt()).isNull());

        mockMvc.perform(get("/admin/{adminToken}", adminToken).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"))
                .andExpect(content().string(containsString("Sorteio refeito")));
    }

    @Test
    void reopenDiscardsAssignmentsAndReopensRegistration() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        Event event = createEventThroughForm(session);
        addParticipant(session, event, "Ana");
        addParticipant(session, event, "Bruno");
        addParticipant(session, event, "Carla");
        draw(session, event);
        redraw(session, event);

        String adminToken = event.getAdminToken();
        UUID eventId = event.getId();
        viewEveryParticipantPage(eventId);
        entityManager.flush();
        entityManager.clear();
        assertThat(participantRepository.findByEvent_Id(eventId))
                .allSatisfy(participant -> assertThat(participant.getFirstViewedAt()).isNotNull());

        reopen(session, event);

        entityManager.flush();
        entityManager.clear();
        Event reopened = eventRepository.findByAdminToken(adminToken).orElseThrow();
        assertThat(reopened.getStatus()).isEqualTo(EventStatus.OPEN);
        assertThat(reopened.isRegistrationOpen()).isTrue();
        assertThat(reopened.getDrawCount()).isEqualTo(2);
        assertThat(reopened.getDrawnAt()).isNull();
        assertThat(assignmentRepository.countByEvent_Id(eventId)).isZero();
        assertThat(participantRepository.findByEvent_Id(eventId))
                .allSatisfy(participant -> assertThat(participant.getFirstViewedAt()).isNull());

        mockMvc.perform(get("/admin/{adminToken}", adminToken).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"))
                .andExpect(content().string(containsString("Inscrições abertas")))
                .andExpect(content().string(not(containsString("Refazer sorteio"))));

        String anaToken = participantRepository.findByEvent_Id(eventId).stream()
                .filter(participant -> participant.getName().equals("Ana"))
                .map(Participant::getAccessToken)
                .findFirst()
                .orElseThrow();
        mockMvc.perform(get("/p/{accessToken}", anaToken))
                .andExpect(status().isOk())
                .andExpect(view().name("participant/view"))
                .andExpect(content().string(containsString("Aguarde o sorteio")))
                .andExpect(content().string(not(containsString("Você tirou"))));
    }

    @Test
    void exclusionGroupWithFewerThanTwoMembersShowsFieldErrorAndCreatesNothing()
            throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        Event event = createEventThroughForm(session);
        Participant ana = addParticipant(session, event, "Ana");

        mockMvc.perform(post("/admin/{adminToken}/exclusions", event.getAdminToken())
                        .session(session)
                        .param("_csrf", csrfToken(session))
                        .param("label", "Casal")
                        .param("memberIds", ana.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"))
                .andExpect(model().attributeHasFieldErrors("exclusionGroupForm", "memberIds"))
                .andExpect(content().string(
                        containsString("Selecione pelo menos 2 participantes.")));

        assertThat(exclusionGroupRepository.findByEvent_Id(event.getId())).isEmpty();
    }

    @Test
    void adminRemovesExclusionGroupFromDashboard() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        Event event = createEventThroughForm(session);
        Participant ana = addParticipant(session, event, "Ana");
        Participant bruno = addParticipant(session, event, "Bruno");
        ExclusionGroup group = createExclusionGroup(session, event, "Casal", ana, bruno);

        assertThat(exclusionGroupRepository.findByEvent_Id(event.getId()))
                .containsExactly(group);
        mockMvc.perform(get("/admin/{adminToken}", event.getAdminToken()).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"))
                .andExpect(content().string(containsString("Casal")));

        mockMvc.perform(post(
                                "/admin/{adminToken}/exclusions/{id}/delete",
                                event.getAdminToken(),
                                group.getId())
                        .session(session)
                        .param("_csrf", csrfToken(session)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/" + event.getAdminToken()))
                .andExpect(flash().attribute("message", "Restrição removida."));

        entityManager.flush();
        entityManager.clear();
        assertThat(exclusionGroupRepository.findByEvent_Id(event.getId())).isEmpty();

        mockMvc.perform(get("/admin/{adminToken}", event.getAdminToken()).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"))
                .andExpect(content().string(not(containsString("Casal"))));
    }

    @Test
    void impossibleDrawRedirectsWithFriendlyErrorAndKeepsEventOpen() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        Event event = createEventThroughForm(session);
        Participant ana = addParticipant(session, event, "Ana");
        Participant bruno = addParticipant(session, event, "Bruno");
        createExclusionGroup(session, event, "Casal", ana, bruno);

        mockMvc.perform(post("/admin/{adminToken}/draw", event.getAdminToken())
                        .session(session)
                        .param("_csrf", csrfToken(session)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/" + event.getAdminToken()))
                .andExpect(flash().attribute(
                        "error",
                        allOf(
                                containsString("3 participantes"),
                                containsString("restrição"))));

        String adminToken = event.getAdminToken();
        entityManager.flush();
        entityManager.clear();
        Event reloaded = eventRepository.findByAdminToken(adminToken).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(EventStatus.OPEN);
        assertThat(reloaded.getDrawCount()).isZero();
        assertThat(assignmentRepository.countByEvent_Id(reloaded.getId())).isZero();
    }

    @Test
    void drawWithoutCsrfTokenIsForbiddenAndKeepsEventOpen() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        Event event = createEventThroughForm(session);
        addParticipant(session, event, "Ana");
        addParticipant(session, event, "Bruno");

        mockMvc.perform(post("/admin/{adminToken}/draw", event.getAdminToken()).session(session))
                .andExpect(status().isForbidden())
                .andExpect(view().name("error/403"));

        String adminToken = event.getAdminToken();
        entityManager.flush();
        entityManager.clear();
        Event reloaded = eventRepository.findByAdminToken(adminToken).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(EventStatus.OPEN);
        assertThat(reloaded.getDrawCount()).isZero();
        assertThat(assignmentRepository.countByEvent_Id(reloaded.getId())).isZero();
    }

    /**
     * Faz o GET inicial que cria o token CSRF na sessão e devolve a mesma
     * sessão para ser reaproveitada nos POSTs.
     */
    private MockHttpSession sessionWithCsrfToken() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/").session(session)).andExpect(status().isOk());
        assertThat(csrfToken(session)).isNotBlank();
        return session;
    }

    private String csrfToken(MockHttpSession session) {
        return (String) session.getAttribute(CSRF_SESSION_ATTRIBUTE);
    }

    /**
     * Cria o evento pelo formulário público e devolve o evento persistido,
     * localizado pelo {@code adminToken} que veio no redirect.
     */
    private Event createEventThroughForm(MockHttpSession session) throws Exception {
        String redirectUrl = mockMvc.perform(post("/events")
                        .session(session)
                        .param("_csrf", csrfToken(session))
                        .param("name", EVENT_NAME)
                        .param("organizerName", ORGANIZER_NAME)
                        .param("eventDate", "2026-12-24")
                        .param("suggestedValue", "50.00"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin/*"))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        String adminToken = redirectUrl.substring("/admin/".length());
        return eventRepository.findByAdminToken(adminToken)
                .orElseThrow(() -> new AssertionError("evento não foi persistido"));
    }

    private Participant addParticipant(MockHttpSession session, Event event, String name)
            throws Exception {
        mockMvc.perform(post("/admin/{adminToken}/participants", event.getAdminToken())
                        .session(session)
                        .param("_csrf", csrfToken(session))
                        .param("name", name)
                        .param("wishlist", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/" + event.getAdminToken()))
                .andExpect(flash().attribute("message", "Participante adicionado."));

        return participantRepository.findByEvent_Id(event.getId()).stream()
                .filter(participant -> participant.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "participante " + name + " não foi persistido"));
    }

    private ExclusionGroup createExclusionGroup(
            MockHttpSession session, Event event, String label, Participant... members)
            throws Exception {
        MockHttpServletRequestBuilder request = post(
                        "/admin/{adminToken}/exclusions", event.getAdminToken())
                .session(session)
                .param("_csrf", csrfToken(session))
                .param("label", label);
        for (Participant member : members) {
            request.param("memberIds", member.getId().toString());
        }

        mockMvc.perform(request)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/" + event.getAdminToken()))
                .andExpect(flash().attribute("message", "Restrição criada."));

        return exclusionGroupRepository.findByEvent_Id(event.getId()).stream()
                .filter(group -> label.equals(group.getLabel()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("grupo " + label + " não foi persistido"));
    }

    private void draw(MockHttpSession session, Event event) throws Exception {
        mockMvc.perform(post("/admin/{adminToken}/draw", event.getAdminToken())
                        .session(session)
                        .param("_csrf", csrfToken(session)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/" + event.getAdminToken()))
                .andExpect(flash().attribute("message", "Sorteio realizado."));
    }

    private void redraw(MockHttpSession session, Event event) throws Exception {
        mockMvc.perform(post("/admin/{adminToken}/redraw", event.getAdminToken())
                        .session(session)
                        .param("_csrf", csrfToken(session)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/" + event.getAdminToken()))
                .andExpect(flash().attribute("message", "Sorteio refeito."));
    }

    private void reopen(MockHttpSession session, Event event) throws Exception {
        mockMvc.perform(post("/admin/{adminToken}/reopen", event.getAdminToken())
                        .session(session)
                        .param("_csrf", csrfToken(session)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/" + event.getAdminToken()))
                .andExpect(flash().attribute("message", "Evento reaberto para edição."));
    }

    private Event reloadEvent(String adminToken) {
        entityManager.flush();
        entityManager.clear();
        return eventRepository.findByAdminToken(adminToken)
                .orElseThrow(() -> new AssertionError("evento não encontrado"));
    }

    private void viewEveryParticipantPage(UUID eventId) throws Exception {
        for (Participant participant : participantRepository.findByEvent_Id(eventId)) {
            mockMvc.perform(get("/p/{accessToken}", participant.getAccessToken()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Você tirou")));
        }
    }

    private Map<UUID, UUID> assignmentsByGiver(UUID eventId, List<Participant> participants) {
        Map<UUID, UUID> assignments = new LinkedHashMap<>();
        for (Participant participant : participants) {
            Assignment assignment = assignmentRepository
                    .findByEvent_IdAndGiver_Id(eventId, participant.getId())
                    .orElseThrow(() -> new AssertionError(
                            "participante sem sorteio: " + participant.getName()));
            assignments.put(participant.getId(), assignment.getReceiver().getId());
        }
        return assignments;
    }
}
