package br.com.michael.amigosecreto.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import br.com.michael.amigosecreto.event.CreateEventCommand;
import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.event.EventService;
import br.com.michael.amigosecreto.participant.Participant;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Borda web do cartão de links do organizador: download do TXT, validação do
 * e-mail e resposta amigável quando o envio está desligado no ambiente.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OrganizerLinksWebIntegrationTest {

    private static final String CSRF_SESSION_ATTRIBUTE = "br.com.michael.amigosecreto.csrfToken";

    private final MockMvc mockMvc;
    private final EventService eventService;

    @Autowired
    OrganizerLinksWebIntegrationTest(MockMvc mockMvc, EventService eventService) {
        this.mockMvc = mockMvc;
        this.eventService = eventService;
    }

    @Test
    void downloadReturnsTheThreeLinksAsAnAttachment() throws Exception {
        Event event = createEvent("links_download");
        Participant organizer = eventService.organizerOf(event);

        mockMvc.perform(get("/admin/{adminToken}/links.txt", event.getAdminToken()))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.CONTENT_DISPOSITION,
                        containsString("attachment")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(containsString(
                        "/admin/" + event.getAdminToken())))
                .andExpect(content().string(containsString(
                        "/join/" + event.getInviteCode())))
                .andExpect(content().string(containsString(
                        "/p/" + organizer.getAccessToken())))
                .andExpect(content().string(containsString("não compartilhe")));
    }

    @Test
    void invalidEmailShowsFieldErrorAndDoesNotRedirect() throws Exception {
        Event event = createEvent("links_invalid_email");
        MockHttpSession session = sessionWithCsrfToken();

        mockMvc.perform(post("/admin/{adminToken}/links/email", event.getAdminToken())
                        .session(session)
                        .param("_csrf", csrfToken(session))
                        .param("email", "sem-arroba"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"))
                .andExpect(model().attributeHasFieldErrors("sendLinksForm", "email"));
    }

    @Test
    void disabledEmailExplainsThatTheTxtIsTheWayOut() throws Exception {
        Event event = createEvent("links_disabled");
        MockHttpSession session = sessionWithCsrfToken();

        mockMvc.perform(post("/admin/{adminToken}/links/email", event.getAdminToken())
                        .session(session)
                        .param("_csrf", csrfToken(session))
                        .param("email", "  Destino@Example.COM  "))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/" + event.getAdminToken()))
                .andExpect(flash().attribute(
                        "error",
                        containsString("desativado")));
    }

    private Event createEvent(String suffix) {
        Event event = eventService.create(new CreateEventCommand(
                "Família " + suffix,
                LocalDate.of(2026, 12, 24),
                new BigDecimal("50.00"),
                "Michael"));
        assertThat(event.getAdminToken()).startsWith("adm_");
        return event;
    }

    private MockHttpSession sessionWithCsrfToken() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/").session(session)).andExpect(status().isOk());
        assertThat(csrfToken(session)).isNotBlank();
        return session;
    }

    private String csrfToken(MockHttpSession session) {
        return (String) session.getAttribute(CSRF_SESSION_ATTRIBUTE);
    }
}
