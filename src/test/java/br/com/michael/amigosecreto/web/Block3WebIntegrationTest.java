package br.com.michael.amigosecreto.web;

import static org.assertj.core.api.Assertions.assertThat;
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
import br.com.michael.amigosecreto.participant.Participant;
import br.com.michael.amigosecreto.participant.ParticipantRepository;
import br.com.michael.amigosecreto.participant.dto.ParticipantRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fluxo web do Bloco 3: participantes pelo painel e pelo convite, abrir e
 * fechar inscrições, regeneração do código de convite, wishlist e página do
 * participante.
 *
 * <p>Cada teste roda na própria transação e cria o evento de que precisa. A
 * sessão é a mesma do navegador: o GET inicial grava o token CSRF e os POSTs o
 * reenviam como {@code _csrf}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class Block3WebIntegrationTest {

    private static final String CSRF_SESSION_ATTRIBUTE = "br.com.michael.amigosecreto.csrfToken";
    private static final String EVENT_NAME = "Família";
    private static final String ORGANIZER_NAME = "Michael";

    private final MockMvc mockMvc;
    private final EventRepository eventRepository;
    private final ParticipantRepository participantRepository;
    private final AssignmentRepository assignmentRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    Block3WebIntegrationTest(
            MockMvc mockMvc,
            EventRepository eventRepository,
            ParticipantRepository participantRepository,
            AssignmentRepository assignmentRepository) {
        this.mockMvc = mockMvc;
        this.eventRepository = eventRepository;
        this.participantRepository = participantRepository;
        this.assignmentRepository = assignmentRepository;
    }

    @Test
    void adminAddsParticipantAndDashboardShowsTheName() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        Event event = createEventThroughForm(session);

        mockMvc.perform(post("/admin/{adminToken}/participants", event.getAdminToken())
                        .session(session)
                        .param("_csrf", csrfToken(session))
                        .param("name", "Ana")
                        .param("wishlist", "Livro"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/" + event.getAdminToken()))
                .andExpect(flash().attribute("message", "Participante adicionado."));

        Participant ana = participantByName(event, "Ana");
        assertThat(ana.isOrganizer()).isFalse();
        assertThat(ana.getAccessToken()).startsWith("p_");
        assertThat(ana.getWishlist()).isEqualTo("Livro");
        assertThat(ana.getEvent().getId()).isEqualTo(event.getId());

        mockMvc.perform(get("/admin/{adminToken}", event.getAdminToken()).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"))
                .andExpect(content().string(containsString("Ana")));
    }

    @Test
    void adminRemovesParticipantFromDashboardAndRepository() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        Event event = createEventThroughForm(session);
        Participant ana = addParticipant(session, event, "Ana", "Livro");

        mockMvc.perform(post(
                                "/admin/{adminToken}/participants/{id}/delete",
                                event.getAdminToken(),
                                ana.getId())
                        .session(session)
                        .param("_csrf", csrfToken(session)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/" + event.getAdminToken()))
                .andExpect(flash().attribute("message", "Participante removido."));

        participantRepository.flush();
        assertThat(participantRepository.findById(ana.getId())).isEmpty();

        mockMvc.perform(get("/admin/{adminToken}", event.getAdminToken()).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"))
                .andExpect(content().string(not(containsString("Ana"))));
    }

    @Test
    void adminCannotRemoveOrganizer() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        Event event = createEventThroughForm(session);
        Participant organizer = participantRepository
                .findByEvent_IdAndOrganizerTrue(event.getId())
                .orElseThrow();

        mockMvc.perform(post(
                                "/admin/{adminToken}/participants/{id}/delete",
                                event.getAdminToken(),
                                organizer.getId())
                        .session(session)
                        .param("_csrf", csrfToken(session)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/" + event.getAdminToken()))
                .andExpect(flash().attribute("error", "O organizador não pode ser removido."));

        assertThat(participantRepository.findById(organizer.getId())).isPresent();
        assertThat(participantRepository.findByEvent_IdAndOrganizerTrue(event.getId()))
                .isPresent();
    }

    @Test
    void closedRegistrationHidesJoinFormAndBlocksJoinPost() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        Event event = createEventThroughForm(session);
        long participantsBefore = participantRepository.countByEvent_Id(event.getId());

        mockMvc.perform(post("/admin/{adminToken}/registration", event.getAdminToken())
                        .session(session)
                        .param("_csrf", csrfToken(session))
                        .param("open", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/" + event.getAdminToken()))
                .andExpect(flash().attribute("message", "Inscrições encerradas."));

        mockMvc.perform(get("/join/{inviteCode}", event.getInviteCode()))
                .andExpect(status().isOk())
                .andExpect(view().name("join/closed"))
                .andExpect(content().string(containsString("encerradas")))
                .andExpect(content().string(not(containsString("name=\"name\""))));

        mockMvc.perform(post("/join/{inviteCode}", event.getInviteCode())
                        .session(session)
                        .param("_csrf", csrfToken(session))
                        .param("name", "Maria")
                        .param("wishlist", "Chocolate"))
                .andExpect(status().isOk())
                .andExpect(view().name("join/closed"));

        assertThat(participantRepository.countByEvent_Id(event.getId()))
                .isEqualTo(participantsBefore);

        mockMvc.perform(post("/admin/{adminToken}/registration", event.getAdminToken())
                        .session(session)
                        .param("_csrf", csrfToken(session))
                        .param("open", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", "Inscrições abertas."));

        mockMvc.perform(get("/join/{inviteCode}", event.getInviteCode()))
                .andExpect(status().isOk())
                .andExpect(view().name("join/form"))
                .andExpect(content().string(containsString("name=\"name\"")));
    }

    @Test
    void regeneratingInviteInvalidatesOldJoinLink() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        Event event = createEventThroughForm(session);
        String oldInviteCode = event.getInviteCode();

        mockMvc.perform(post("/admin/{adminToken}/invite/regenerate", event.getAdminToken())
                        .session(session)
                        .param("_csrf", csrfToken(session)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/" + event.getAdminToken()))
                .andExpect(flash().attribute("message", "Link de convite atualizado."));

        String newInviteCode = eventRepository.findByAdminToken(event.getAdminToken())
                .orElseThrow()
                .getInviteCode();

        assertThat(newInviteCode).startsWith("inv_").isNotEqualTo(oldInviteCode);
        assertThat(eventRepository.findByInviteCode(oldInviteCode)).isEmpty();
        assertThat(eventRepository.findByInviteCode(newInviteCode)).isPresent();

        mockMvc.perform(get("/join/{inviteCode}", oldInviteCode))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/join/{inviteCode}", newInviteCode))
                .andExpect(status().isOk())
                .andExpect(view().name("join/form"))
                .andExpect(content().string(containsString("name=\"name\"")));
    }

    @Test
    void joinThroughInviteCreatesParticipantAndShowsOnlyOwnWaitingPage() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        Event event = createEventThroughForm(session);
        addParticipant(session, event, "Ana", "Livro");

        String accessToken = joinThroughInvite(session, event, "Maria", "Chocolate");

        Participant maria = participantRepository.findByAccessToken(accessToken).orElseThrow();
        assertThat(maria.getName()).isEqualTo("Maria");
        assertThat(maria.getWishlist()).isEqualTo("Chocolate");
        assertThat(maria.isOrganizer()).isFalse();

        mockMvc.perform(get("/p/{accessToken}", accessToken).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("participant/view"))
                .andExpect(content().string(containsString("Maria")))
                .andExpect(content().string(containsString("Aguarde o sorteio")))
                .andExpect(content().string(not(containsString("Ana"))))
                .andExpect(content().string(not(containsString(ORGANIZER_NAME))))
                .andExpect(content().string(not(containsString(event.getAdminToken()))));
    }

    @Test
    void participantUpdatesOwnWishlist() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        Event event = createEventThroughForm(session);
        String accessToken = joinThroughInvite(session, event, "Maria", "Chocolate");

        mockMvc.perform(post("/p/{accessToken}/wishlist", accessToken)
                        .session(session)
                        .param("_csrf", csrfToken(session))
                        .param("wishlist", "Livro novo"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/p/" + accessToken))
                .andExpect(flash().attribute("message", "Lista de desejos atualizada."));

        assertThat(participantRepository.findByAccessToken(accessToken).orElseThrow().getWishlist())
                .isEqualTo("Livro novo");

        mockMvc.perform(get("/p/{accessToken}", accessToken).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Livro novo")));
    }

    @Test
    void unknownInviteCodeReturnsNotFound() throws Exception {
        mockMvc.perform(get("/join/{inviteCode}", "inv_inexistente"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownAccessTokenReturnsNotFound() throws Exception {
        mockMvc.perform(get("/p/{accessToken}", "p_inexistente"))
                .andExpect(status().isNotFound());
    }

    @Test
    void blankNameOnJoinReturnsFormWithFieldErrorAndDoesNotCreateParticipant() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        Event event = createEventThroughForm(session);
        long participantsBefore = participantRepository.countByEvent_Id(event.getId());

        mockMvc.perform(post("/join/{inviteCode}", event.getInviteCode())
                        .session(session)
                        .param("_csrf", csrfToken(session))
                        .param("name", "")
                        .param("wishlist", "Chocolate"))
                .andExpect(status().isOk())
                .andExpect(view().name("join/form"))
                .andExpect(model().attributeHasFieldErrors("form", "name"))
                .andExpect(content().string(containsString("Informe seu nome.")));

        assertThat(participantRepository.countByEvent_Id(event.getId()))
                .isEqualTo(participantsBefore);
    }

    @Test
    void drawnEventRevealsReceiverAndMarksFirstView() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        Event event = createEventThroughForm(session);
        String mariaToken = joinThroughInvite(session, event, "Maria", "Chocolate");

        Participant organizer = participantRepository
                .findByEvent_IdAndOrganizerTrue(event.getId())
                .orElseThrow();
        organizer.setWishlist("Fone de ouvido");
        Participant maria = participantRepository.findByAccessToken(mariaToken).orElseThrow();

        // Quem abre a página da Maria é ela mesma: o giver é Maria e o
        // receiver é o organizador, para "Você tirou" mostrar o organizador.
        assignmentRepository.save(new Assignment(event, maria, organizer));
        event.markDrawn(Instant.now());
        eventRepository.saveAndFlush(event);

        mockMvc.perform(get("/p/{accessToken}", mariaToken).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("participant/view"))
                .andExpect(content().string(containsString("Você tirou")))
                .andExpect(content().string(containsString(ORGANIZER_NAME)))
                .andExpect(content().string(containsString("Fone de ouvido")))
                .andExpect(content().string(not(containsString("Aguarde o sorteio"))));

        entityManager.flush();
        entityManager.clear();
        Participant reloaded =
                participantRepository.findByAccessToken(mariaToken).orElseThrow();
        assertThat(reloaded.getFirstViewedAt()).isNotNull();
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

    private Participant addParticipant(
            MockHttpSession session, Event event, String name, String wishlist) throws Exception {
        mockMvc.perform(post("/admin/{adminToken}/participants", event.getAdminToken())
                        .session(session)
                        .param("_csrf", csrfToken(session))
                        .param("name", name)
                        .param("wishlist", wishlist))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/" + event.getAdminToken()));

        return participantByName(event, name);
    }

    private Participant participantByName(Event event, String name) {
        ParticipantRow row = participantRepository.findRowsByEventId(event.getId()).stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("participante " + name + " não foi persistido"));
        return participantRepository.findByAccessToken(row.accessToken()).orElseThrow();
    }

    /**
     * Envia o formulário de convite e devolve o token pessoal extraído do
     * redirect para {@code /p/{accessToken}}.
     */
    private String joinThroughInvite(
            MockHttpSession session, Event event, String name, String wishlist) throws Exception {
        String redirectUrl = mockMvc.perform(post("/join/{inviteCode}", event.getInviteCode())
                        .session(session)
                        .param("_csrf", csrfToken(session))
                        .param("name", name)
                        .param("wishlist", wishlist))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/p/*"))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        return redirectUrl.substring("/p/".length());
    }
}
