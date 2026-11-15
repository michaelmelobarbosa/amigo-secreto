package br.com.michael.amigosecreto.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.michael.amigosecreto.email.EmailMessage;
import br.com.michael.amigosecreto.email.EmailSendResult;
import br.com.michael.amigosecreto.email.EmailSender;
import br.com.michael.amigosecreto.event.CreateEventCommand;
import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.event.EventService;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Envio dos links do organizador com o e-mail habilitado: o adaptador é
 * mockado, então o teste não faz rede.
 */
@SpringBootTest(properties = {
        "app.email.enabled=true",
        "app.email.brevo.api-key=test-key",
        "app.email.brevo.sandbox=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OrganizerLinksEmailWebIntegrationTest {

    private static final String CSRF_SESSION_ATTRIBUTE = "br.com.michael.amigosecreto.csrfToken";

    private final MockMvc mockMvc;
    private final EventService eventService;

    @MockitoBean
    private EmailSender emailSender;

    @Autowired
    OrganizerLinksEmailWebIntegrationTest(MockMvc mockMvc, EventService eventService) {
        this.mockMvc = mockMvc;
        this.eventService = eventService;
    }

    @Test
    void sendsTheThreeLinksToTheNormalizedEmail() throws Exception {
        Event event = createEvent("links_email");
        MockHttpSession session = sessionWithCsrfToken();
        given(emailSender.send(any())).willReturn(EmailSendResult.accepted("brevo-test"));

        mockMvc.perform(post("/admin/{adminToken}/links/email", event.getAdminToken())
                        .session(session)
                        .param("_csrf", csrfToken(session))
                        .param("email", "  Destino@Example.COM  "))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/" + event.getAdminToken()))
                .andExpect(flash().attribute(
                        "message", "Links enviados para o e-mail informado."));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(captor.capture());
        EmailMessage message = captor.getValue();
        assertThat(message.toEmail()).isEqualTo("destino@example.com");
        assertThat(message.html())
                .contains("/admin/" + event.getAdminToken())
                .contains("/join/" + event.getInviteCode());
        assertThat(message.text())
                .contains("/admin/" + event.getAdminToken())
                .contains("/join/" + event.getInviteCode());
    }

    private Event createEvent(String suffix) {
        return eventService.create(new CreateEventCommand(
                "Família " + suffix,
                LocalDate.of(2026, 12, 24),
                new BigDecimal("50.00"),
                "Michael"));
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
