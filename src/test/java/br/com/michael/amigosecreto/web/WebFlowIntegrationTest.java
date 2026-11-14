package br.com.michael.amigosecreto.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import br.com.michael.amigosecreto.event.CreateEventCommand;
import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.event.EventRepository;
import br.com.michael.amigosecreto.event.EventService;
import br.com.michael.amigosecreto.participant.Participant;
import br.com.michael.amigosecreto.participant.ParticipantRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fluxo web do Bloco 2: criação de evento, painel do organizador, CSRF e
 * apagamento. Cada teste roda na própria transação e cria os dados de que
 * precisa, sem depender de estado deixado por outro teste.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WebFlowIntegrationTest {

    private static final String CSRF_SESSION_ATTRIBUTE = "br.com.michael.amigosecreto.csrfToken";

    private final MockMvc mockMvc;
    private final EventService eventService;
    private final EventRepository eventRepository;
    private final ParticipantRepository participantRepository;

    @Autowired
    WebFlowIntegrationTest(
            MockMvc mockMvc,
            EventService eventService,
            EventRepository eventRepository,
            ParticipantRepository participantRepository) {
        this.mockMvc = mockMvc;
        this.eventService = eventService;
        this.eventRepository = eventRepository;
        this.participantRepository = participantRepository;
    }

    @Test
    void indexPageShowsCreateEventForm() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(content().string(containsString("name=\"name\"")))
                .andExpect(content().string(containsString("name=\"organizerName\"")));
    }

    @Test
    void postEventsWithoutCsrfTokenIsRejected() throws Exception {
        mockMvc.perform(post("/events")
                        .param("name", "Família")
                        .param("organizerName", "Michael")
                        .param("eventDate", "2026-12-24")
                        .param("suggestedValue", "50.00"))
                .andExpect(status().isForbidden());

        assertThat(eventRepository.count()).isZero();
    }

    @Test
    void postEventsWithCsrfTokenPersistsEventAndOrganizerAndRedirectsToAdmin() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();

        MvcResult result = mockMvc.perform(post("/events")
                        .session(session)
                        .param("_csrf", csrfToken(session))
                        .param("name", "Família")
                        .param("organizerName", "Michael")
                        .param("eventDate", "2026-12-24")
                        .param("suggestedValue", "50.00"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin/*"))
                .andReturn();

        Event event = onlyPersistedEvent();
        assertThat(result.getResponse().getRedirectedUrl())
                .isEqualTo("/admin/" + event.getAdminToken());
        assertThat(event.getName()).isEqualTo("Família");
        assertThat(event.getEventDate()).isEqualTo(LocalDate.of(2026, 12, 24));
        assertThat(event.getSuggestedValue()).isEqualByComparingTo(new BigDecimal("50.00"));

        Participant organizer = participantRepository
                .findByEvent_IdAndOrganizerTrue(event.getId())
                .orElseThrow();
        assertThat(organizer.getName()).isEqualTo("Michael");
        assertThat(organizer.isOrganizer()).isTrue();
    }

    @Test
    void adminDashboardShowsEventAndInviteLinksWithoutAssignmentData() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();
        String adminUrl = createEventThroughForm(session);

        mockMvc.perform(get(adminUrl).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"))
                .andExpect(content().string(containsString("Família")))
                .andExpect(content().string(containsString("/join/")))
                .andExpect(content().string(containsString("/p/")))
                .andExpect(content().string(not(containsString("assignment"))))
                .andExpect(content().string(not(containsString("sorteio"))));
    }

    @Test
    void adminDashboardWithUnknownTokenReturnsNotFound() throws Exception {
        mockMvc.perform(get("/admin/token_inexistente"))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminDashboardSendsNoStoreAndNoReferrerHeaders() throws Exception {
        Event event = createEvent();

        mockMvc.perform(get("/admin/" + event.getAdminToken()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void deleteEventRemovesItSoAdminDashboardReturnsNotFound() throws Exception {
        Event event = createEvent();
        MockHttpSession session = sessionWithCsrfToken();

        mockMvc.perform(post("/admin/" + event.getAdminToken() + "/delete")
                        .session(session)
                        .param("_csrf", csrfToken(session)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        assertThat(eventRepository.findByAdminToken(event.getAdminToken())).isEmpty();

        mockMvc.perform(get("/admin/" + event.getAdminToken()).session(session))
                .andExpect(status().isNotFound());
    }

    @Test
    void postEventsWithBlankNameShowsValidationErrorOnName() throws Exception {
        MockHttpSession session = sessionWithCsrfToken();

        mockMvc.perform(post("/events")
                        .session(session)
                        .param("_csrf", csrfToken(session))
                        .param("name", "   ")
                        .param("organizerName", "Michael"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(model().attributeHasFieldErrors("form", "name"))
                .andExpect(content().string(containsString("Informe o nome do evento.")));

        assertThat(eventRepository.count()).isZero();
    }

    /**
     * Faz o GET inicial que cria o token CSRF na sessão e devolve a mesma
     * sessão para ser reaproveitada nos POSTs.
     */
    private MockHttpSession sessionWithCsrfToken() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/").session(session))
                .andExpect(status().isOk());
        assertThat(csrfToken(session)).isNotBlank();
        return session;
    }

    private String csrfToken(MockHttpSession session) {
        return (String) session.getAttribute(CSRF_SESSION_ATTRIBUTE);
    }

    /**
     * Envia o formulário válido e devolve a URL de admin do evento criado,
     * exatamente como o navegador receberia no redirect.
     */
    private String createEventThroughForm(MockHttpSession session) throws Exception {
        return mockMvc.perform(post("/events")
                        .session(session)
                        .param("_csrf", csrfToken(session))
                        .param("name", "Família")
                        .param("organizerName", "Michael")
                        .param("eventDate", "2026-12-24")
                        .param("suggestedValue", "50.00"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin/*"))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
    }

    private Event createEvent() {
        return eventService.create(new CreateEventCommand(
                "Família",
                LocalDate.of(2026, 12, 24),
                new BigDecimal("50.00"),
                "Michael"));
    }

    private Event onlyPersistedEvent() {
        List<Event> events = eventRepository.findAll();
        assertThat(events).hasSize(1);
        return events.getFirst();
    }
}
