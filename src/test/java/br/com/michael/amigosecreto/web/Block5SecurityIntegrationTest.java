package br.com.michael.amigosecreto.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * Auditoria de segurança do Bloco 5: CSRF em todo POST, headers de privacidade,
 * escopo por token (anti-IDOR), escape de HTML, 404 sem eco de token, prévia de
 * link que não consome a primeira visualização e limpeza de grupo degenerado.
 *
 * <p>Cada teste roda na própria transação e monta o cenário de que precisa. O
 * GET inicial da home grava o token CSRF na sessão e os POSTs reenviam o mesmo
 * valor em {@code _csrf}. As leituras que precisam refletir o banco passam por
 * flush/clear: os updates em massa de primeira visualização não atualizam as
 * entidades já gerenciadas.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class Block5SecurityIntegrationTest {

    private static final String CSRF_SESSION_ATTRIBUTE = "br.com.michael.amigosecreto.csrfToken";
    private static final String EVENT_NAME = "Família";
    private static final String ORGANIZER_NAME = "Michael";
    private static final String SCRIPT_PAYLOAD = "<script>alert(1)</script>";
    private static final String IMG_PAYLOAD = "<img src=x onerror=alert(1)>";

    private final MockMvc mockMvc;
    private final EventRepository eventRepository;
    private final ParticipantRepository participantRepository;
    private final ExclusionGroupRepository exclusionGroupRepository;
    private final AssignmentRepository assignmentRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    Block5SecurityIntegrationTest(
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

    // ---------- CSRF ----------

    @Test
    void postEventsWithoutCsrfTokenIsForbiddenAndCreatesNothing() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        long eventsBefore = eventRepository.count();

        mockMvc.perform(post("/events")
                        .session(session)
                        .param("name", EVENT_NAME)
                        .param("organizerName", ORGANIZER_NAME))
                .andExpect(status().isForbidden())
                .andExpect(view().name("error/403"));

        assertThat(eventRepository.count()).isEqualTo(eventsBefore);
    }

    @Test
    void postDeleteEventWithoutCsrfTokenIsForbiddenAndKeepsEvent() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        String adminToken = createEventThroughForm(session, EVENT_NAME, ORGANIZER_NAME);

        mockMvc.perform(post("/admin/{adminToken}/delete", adminToken).session(session))
                .andExpect(status().isForbidden())
                .andExpect(view().name("error/403"));

        entityManager.flush();
        entityManager.clear();
        assertThat(eventRepository.findByAdminToken(adminToken)).isPresent();
    }

    @Test
    void postJoinWithoutCsrfTokenIsForbiddenAndCreatesNoParticipant() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        String adminToken = createEventThroughForm(session, EVENT_NAME, ORGANIZER_NAME);
        Event event = eventRepository.findByAdminToken(adminToken).orElseThrow();
        long participantsBefore = participantRepository.countByEvent_Id(event.getId());

        mockMvc.perform(post("/join/{inviteCode}", event.getInviteCode())
                        .session(session)
                        .param("name", "Ana")
                        .param("wishlist", "Livro"))
                .andExpect(status().isForbidden())
                .andExpect(view().name("error/403"));

        entityManager.flush();
        entityManager.clear();
        assertThat(participantRepository.countByEvent_Id(event.getId()))
                .isEqualTo(participantsBefore);
    }

    @Test
    void postWishlistWithoutCsrfTokenIsForbiddenAndKeepsWishlist() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        String adminToken = createEventThroughForm(session, EVENT_NAME, ORGANIZER_NAME);
        Event event = eventRepository.findByAdminToken(adminToken).orElseThrow();
        Participant organizer = participantRepository
                .findByEvent_IdAndOrganizerTrue(event.getId())
                .orElseThrow();
        organizer.setWishlist("Antes do ataque");
        participantRepository.save(organizer);
        String accessToken = organizer.getAccessToken();
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post("/p/{accessToken}/wishlist", accessToken)
                        .session(session)
                        .param("wishlist", "Injetada sem csrf"))
                .andExpect(status().isForbidden())
                .andExpect(view().name("error/403"));

        entityManager.flush();
        entityManager.clear();
        assertThat(participantRepository.findByAccessToken(accessToken).orElseThrow().getWishlist())
                .isEqualTo("Antes do ataque");
    }

    @Test
    void postWithForgedCsrfTokenIsForbidden() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        long eventsBefore = eventRepository.count();

        mockMvc.perform(post("/events")
                        .session(session)
                        .param("_csrf", "token-forjado")
                        .param("name", EVENT_NAME)
                        .param("organizerName", ORGANIZER_NAME))
                .andExpect(status().isForbidden())
                .andExpect(view().name("error/403"));

        assertThat(eventRepository.count()).isEqualTo(eventsBefore);
    }

    // ---------- Headers ----------

    @Test
    void adminDashboardSendsPrivacyHeaders() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        String adminToken = createEventThroughForm(session, EVENT_NAME, ORGANIZER_NAME);

        assertPrivacyHeaders(mockMvc.perform(get("/admin/{adminToken}", adminToken))
                .andExpect(status().isOk()));
    }

    @Test
    void participantPageSendsPrivacyHeaders() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        String adminToken = createEventThroughForm(session, EVENT_NAME, ORGANIZER_NAME);
        Event event = eventRepository.findByAdminToken(adminToken).orElseThrow();
        Participant organizer = participantRepository
                .findByEvent_IdAndOrganizerTrue(event.getId())
                .orElseThrow();

        assertPrivacyHeaders(mockMvc.perform(
                        get("/p/{accessToken}", organizer.getAccessToken()))
                .andExpect(status().isOk()));
    }

    @Test
    void joinPageSendsPrivacyHeaders() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        String adminToken = createEventThroughForm(session, EVENT_NAME, ORGANIZER_NAME);
        Event event = eventRepository.findByAdminToken(adminToken).orElseThrow();

        assertPrivacyHeaders(mockMvc.perform(
                        get("/join/{inviteCode}", event.getInviteCode()))
                .andExpect(status().isOk()));
    }

    @Test
    void creatingEventRedirectsToAdminWithPrivacyHeaders() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();

        mockMvc.perform(post("/events")
                        .session(session)
                        .param("_csrf", csrfToken(session))
                        .param("name", EVENT_NAME)
                        .param("organizerName", ORGANIZER_NAME))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("/admin/")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void robotsTxtDisallowsCrawling() throws Exception {
        mockMvc.perform(get("/robots.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Disallow: /")));
    }

    // ---------- IDOR ----------

    @Test
    void adminCannotDeleteParticipantOfAnotherEvent() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        String adminTokenA = createEventThroughForm(session, "Evento A", ORGANIZER_NAME);
        String adminTokenB = createEventThroughForm(session, "Evento B", ORGANIZER_NAME);
        Event eventB = eventRepository.findByAdminToken(adminTokenB).orElseThrow();
        UUID eventBId = eventB.getId();
        Participant victim = addParticipant(session, eventB, "Ana", "Livro");
        UUID victimId = victim.getId();

        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post(
                                "/admin/{adminToken}/participants/{id}/delete",
                                adminTokenA,
                                victimId)
                        .session(session)
                        .param("_csrf", csrfToken(session)))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));

        entityManager.clear();
        assertThat(participantRepository.findById(victimId)).isPresent();
        assertThat(participantRepository.findByEvent_Id(eventBId)).hasSize(2);
    }

    @Test
    void adminCannotDeleteExclusionGroupOfAnotherEvent() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        String adminTokenA = createEventThroughForm(session, "Evento A", ORGANIZER_NAME);
        String adminTokenB = createEventThroughForm(session, "Evento B", ORGANIZER_NAME);
        Event eventB = eventRepository.findByAdminToken(adminTokenB).orElseThrow();
        UUID eventBId = eventB.getId();
        Participant ana = addParticipant(session, eventB, "Ana", "Livro");
        Participant bruno = addParticipant(session, eventB, "Bruno", "Jogo");
        ExclusionGroup group = createExclusionGroup(session, eventB, "Casal", ana, bruno);
        UUID groupId = group.getId();

        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post(
                                "/admin/{adminToken}/exclusions/{id}/delete",
                                adminTokenA,
                                groupId)
                        .session(session)
                        .param("_csrf", csrfToken(session)))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));

        entityManager.clear();
        assertThat(exclusionGroupRepository.findById(groupId)).isPresent();
        assertThat(exclusionGroupRepository.findByEvent_Id(eventBId)).hasSize(1);
    }

    // ---------- XSS ----------

    @Test
    void dashboardEscapesScriptFromEventName() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        String adminToken = createEventThroughForm(session, SCRIPT_PAYLOAD, ORGANIZER_NAME);

        mockMvc.perform(get("/admin/{adminToken}", adminToken).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"))
                .andExpect(content().string(containsString(
                        "&lt;script&gt;alert(1)&lt;/script&gt;")))
                .andExpect(content().string(not(containsString(SCRIPT_PAYLOAD))));
    }

    @Test
    void participantPageEscapesHtmlFromWishlist() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        String adminToken = createEventThroughForm(session, EVENT_NAME, ORGANIZER_NAME);
        Event event = eventRepository.findByAdminToken(adminToken).orElseThrow();
        Participant ana = addParticipant(session, event, "Ana", IMG_PAYLOAD);

        mockMvc.perform(get("/p/{accessToken}", ana.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(view().name("participant/view"))
                .andExpect(content().string(containsString("&lt;img src=x")))
                .andExpect(content().string(not(containsString(IMG_PAYLOAD))))
                .andExpect(content().string(not(containsString("<img src=x"))));
    }

    // ---------- Token em erro ----------

    @Test
    void unknownAdminTokenReturns404WithoutEchoingTheToken() throws Exception {
        String unknownToken = "adm_token_inexistente";

        mockMvc.perform(get("/admin/{adminToken}", unknownToken))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"))
                .andExpect(content().string(not(containsString(unknownToken))));
    }

    @Test
    void unknownParticipantTokenReturns404WithoutEchoingTheToken() throws Exception {
        String unknownToken = "p_token_inexistente";

        mockMvc.perform(get("/p/{accessToken}", unknownToken))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"))
                .andExpect(content().string(not(containsString(unknownToken))));
    }

    // ---------- Prévia de link ----------

    @Test
    void whatsAppPreviewRequestShowsResultWithoutMarkingFirstView() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        DrawnEvent drawn = drawEventWithCouple(session);

        mockMvc.perform(get("/p/{accessToken}", drawn.participantToken())
                        .header("User-Agent", "WhatsApp/2.24"))
                .andExpect(status().isOk())
                .andExpect(view().name("participant/view"))
                .andExpect(content().string(containsString("Você tirou")))
                .andExpect(content().string(not(containsString("Aguarde o sorteio"))));

        assertThat(reloadParticipant(drawn.participantToken()).getFirstViewedAt()).isNull();
    }

    @Test
    void browserRequestMarksFirstView() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        DrawnEvent drawn = drawEventWithCouple(session);

        mockMvc.perform(get("/p/{accessToken}", drawn.participantToken())
                        .header("User-Agent", "Mozilla/5.0"))
                .andExpect(status().isOk())
                .andExpect(view().name("participant/view"))
                .andExpect(content().string(containsString("Você tirou")));

        assertThat(reloadParticipant(drawn.participantToken()).getFirstViewedAt()).isNotNull();
    }

    // ---------- Grupo degenerado ----------

    @Test
    void removingParticipantDeletesDegenerateGroupAndUnblocksDrawWithThreeParticipants()
            throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        String adminToken = createEventThroughForm(session, EVENT_NAME, ORGANIZER_NAME);
        Event event = eventRepository.findByAdminToken(adminToken).orElseThrow();
        UUID eventId = event.getId();
        Participant ana = addParticipant(session, event, "Ana", "");
        Participant bruno = addParticipant(session, event, "Bruno", "");
        addParticipant(session, event, "Carla", "");
        ExclusionGroup group = createExclusionGroup(session, event, "Casal", ana, bruno);
        UUID groupId = group.getId();
        UUID brunoId = bruno.getId();

        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post(
                                "/admin/{adminToken}/participants/{id}/delete",
                                adminToken,
                                brunoId)
                        .session(session)
                        .param("_csrf", csrfToken(session)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/" + adminToken))
                .andExpect(flash().attribute("message", "Participante removido."));

        entityManager.flush();
        entityManager.clear();
        assertThat(participantRepository.findById(brunoId)).isEmpty();
        assertThat(exclusionGroupRepository.findById(groupId)).isEmpty();

        mockMvc.perform(get("/admin/{adminToken}", adminToken).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"))
                .andExpect(content().string(not(containsString(
                        "Com restrições, são necessários pelo menos 4 participantes."))))
                .andExpect(content().string(containsString(
                        "action=\"/admin/" + adminToken + "/draw\"")));

        mockMvc.perform(post("/admin/{adminToken}/draw", adminToken)
                        .session(session)
                        .param("_csrf", csrfToken(session)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/" + adminToken))
                .andExpect(flash().attribute("message", "Sorteio realizado."));

        entityManager.flush();
        entityManager.clear();
        assertThat(assignmentRepository.countByEvent_Id(eventId)).isEqualTo(3);
        assertThat(eventRepository.findByAdminToken(adminToken).orElseThrow().getStatus())
                .isEqualTo(EventStatus.DRAWN);
    }

    // ---------- Helpers ----------

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
     * Cria o evento pelo formulário público e devolve o {@code adminToken} que
     * veio no redirect, exatamente como o navegador receberia.
     */
    private String createEventThroughForm(
            MockHttpSession session, String name, String organizerName) throws Exception {
        String redirectUrl = mockMvc.perform(post("/events")
                        .session(session)
                        .param("_csrf", csrfToken(session))
                        .param("name", name)
                        .param("organizerName", organizerName)
                        .param("eventDate", "2026-12-24")
                        .param("suggestedValue", "50.00"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin/*"))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        return redirectUrl.substring("/admin/".length());
    }

    private Participant addParticipant(
            MockHttpSession session, Event event, String name, String wishlist)
            throws Exception {
        mockMvc.perform(post("/admin/{adminToken}/participants", event.getAdminToken())
                        .session(session)
                        .param("_csrf", csrfToken(session))
                        .param("name", name)
                        .param("wishlist", wishlist == null ? "" : wishlist))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/" + event.getAdminToken()))
                .andExpect(flash().attribute("message", "Participante adicionado."));

        return participantRepository.findByEvent_Id(event.getId()).stream()
                .filter(participant -> !participant.isOrganizer())
                .filter(participant -> participant.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "participante " + name + " não foi persistido"));
    }

    private ExclusionGroup createExclusionGroup(
            MockHttpSession session, Event event, String label, Participant... members)
            throws Exception {
        var request = post("/admin/{adminToken}/exclusions", event.getAdminToken())
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
                .orElseThrow(() -> new AssertionError(
                        "grupo " + label + " não foi persistido"));
    }

    private void draw(MockHttpSession session, String adminToken) throws Exception {
        mockMvc.perform(post("/admin/{adminToken}/draw", adminToken)
                        .session(session)
                        .param("_csrf", csrfToken(session)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/" + adminToken))
                .andExpect(flash().attribute("message", "Sorteio realizado."));
    }

    /**
     * Cria evento com organizador, Ana, Bruno e Carla (4 participantes), um
     * grupo Ana+Bruno e realiza o sorteio para que exista {@code Assignment}.
     */
    private DrawnEvent drawEventWithCouple(MockHttpSession session) throws Exception {
        String adminToken = createEventThroughForm(session, EVENT_NAME, ORGANIZER_NAME);
        Event event = eventRepository.findByAdminToken(adminToken).orElseThrow();
        Participant ana = addParticipant(session, event, "Ana", "Livro");
        Participant bruno = addParticipant(session, event, "Bruno", "Jogo");
        addParticipant(session, event, "Carla", "Caneca");
        createExclusionGroup(session, event, "Casal", ana, bruno);
        draw(session, adminToken);

        UUID eventId = event.getId();
        String participantToken = ana.getAccessToken();
        entityManager.flush();
        entityManager.clear();
        return new DrawnEvent(eventId, participantToken);
    }

    private Participant reloadParticipant(String accessToken) {
        entityManager.flush();
        entityManager.clear();
        return participantRepository.findByAccessToken(accessToken)
                .orElseThrow(() -> new AssertionError("participante não encontrado"));
    }

    private void assertPrivacyHeaders(ResultActions actions) throws Exception {
        actions.andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", allOf(
                        containsString("default-src 'self'"),
                        containsString("frame-ancestors 'none'"))))
                .andExpect(header().string("X-Robots-Tag", containsString("noindex")));
    }

    private record DrawnEvent(UUID eventId, String participantToken) {
    }
}
