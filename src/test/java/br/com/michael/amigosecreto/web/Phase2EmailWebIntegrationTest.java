package br.com.michael.amigosecreto.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import br.com.michael.amigosecreto.event.CreateEventCommand;
import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.event.EventRepository;
import br.com.michael.amigosecreto.event.EventService;
import br.com.michael.amigosecreto.notification.Notification;
import br.com.michael.amigosecreto.notification.NotificationChannel;
import br.com.michael.amigosecreto.notification.NotificationRepository;
import br.com.michael.amigosecreto.notification.NotificationStatus;
import br.com.michael.amigosecreto.participant.Participant;
import br.com.michael.amigosecreto.participant.ParticipantRepository;
import br.com.michael.amigosecreto.participant.ParticipantService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Borda web da fase 2: e-mail opcional no join e na adição manual, e reenvio
 * pelo painel.
 *
 * <p>O evento é marcado como {@code DRAWN} diretamente no repositório para não
 * depender do {@code DrawCompletedEvent} (publicado só depois do commit, que o
 * teste transacional não faz). A notificação inicial também é criada à mão;
 * o que se verifica é a rota, o flash e o estado do outbox.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class Phase2EmailWebIntegrationTest {

    private static final String CSRF_SESSION_ATTRIBUTE = "br.com.michael.amigosecreto.csrfToken";

    private final MockMvc mockMvc;
    private final EventService eventService;
    private final EventRepository eventRepository;
    private final ParticipantService participantService;
    private final ParticipantRepository participantRepository;
    private final NotificationRepository notificationRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    Phase2EmailWebIntegrationTest(
            MockMvc mockMvc,
            EventService eventService,
            EventRepository eventRepository,
            ParticipantService participantService,
            ParticipantRepository participantRepository,
            NotificationRepository notificationRepository) {
        this.mockMvc = mockMvc;
        this.eventService = eventService;
        this.eventRepository = eventRepository;
        this.participantService = participantService;
        this.participantRepository = participantRepository;
        this.notificationRepository = notificationRepository;
    }

    @Test
    void joinNormalizesEmailToLowercaseAndDashboardShowsIt() throws Exception {
        Event event = createEvent("web_join_email");
        MockHttpSession session = sessionWithCsrfToken();

        mockMvc.perform(post("/join/{inviteCode}", event.getInviteCode())
                        .session(session)
                        .param("_csrf", csrfToken(session))
                        .param("name", "Bia")
                        .param("email", "  Bia.Silva@Example.COM  ")
                        .param("wishlist", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/p/*"));

        entityManager.flush();
        entityManager.clear();

        Participant bia = participantRepository.findByEvent_Id(event.getId()).stream()
                .filter(participant -> !participant.isOrganizer())
                .findFirst()
                .orElseThrow();
        assertThat(bia.getEmail()).isEqualTo("bia.silva@example.com");

        mockMvc.perform(get("/admin/{adminToken}", event.getAdminToken()).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"))
                .andExpect(content().string(containsString("bia.silva@example.com")));
    }

    @Test
    void adminCanAddParticipantWithEmailAndDashboardShowsIt() throws Exception {
        Event event = createEvent("web_admin_email");
        MockHttpSession session = sessionWithCsrfToken();

        mockMvc.perform(post("/admin/{adminToken}/participants", event.getAdminToken())
                        .session(session)
                        .param("_csrf", csrfToken(session))
                        .param("name", "Carla")
                        .param("email", "  Carla@Example.COM  ")
                        .param("wishlist", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/" + event.getAdminToken()))
                .andExpect(flash().attribute("message", "Participante adicionado."));

        entityManager.flush();
        entityManager.clear();

        Participant carla = participantRepository.findByEvent_Id(event.getId()).stream()
                .filter(participant -> "Carla".equals(participant.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(carla.getEmail()).isEqualTo("carla@example.com");

        mockMvc.perform(get("/admin/{adminToken}", event.getAdminToken()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("carla@example.com")))
                .andExpect(content().string(containsString("Aguardando sorteio")));
    }

    @Test
    void resendWithCsrfRequeuesTheCurrentDrawNotification() throws Exception {
        Event event = createEvent("web_resend");
        Participant ana = participantService.addByOrganizer(
                event, "Ana", "Ana@Example.COM", null);
        UUID anaId = ana.getId();
        Event drawn = eventRepository.findByAdminToken(event.getAdminToken()).orElseThrow();
        drawn.markDrawn(Instant.now());
        eventRepository.saveAndFlush(drawn);

        Notification notification = new Notification(
                ana, drawn.getDrawCount(), NotificationChannel.EMAIL);
        notification.markAttempt();
        notification.markSent("brevo-old-message");
        notificationRepository.saveAndFlush(notification);
        UUID oldKey = notification.getIdempotencyKey();

        entityManager.flush();
        entityManager.clear();

        MockHttpSession session = sessionWithCsrfToken();
        mockMvc.perform(post(
                                "/admin/{adminToken}/participants/{id}/email/resend",
                                event.getAdminToken(),
                                anaId)
                        .session(session)
                        .param("_csrf", csrfToken(session)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/" + event.getAdminToken()))
                .andExpect(flash().attribute("message", "E-mail reenfileirado."));

        Notification reloaded = notificationRepository
                .findByParticipant_IdAndDrawCountAndChannel(
                        anaId, 1, NotificationChannel.EMAIL)
                .orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(reloaded.getAttempts()).isZero();
        assertThat(reloaded.getProviderMessageId()).isNull();
        assertThat(reloaded.getIdempotencyKey()).isNotEqualTo(oldKey);

        mockMvc.perform(get("/admin/{adminToken}", event.getAdminToken()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Pendente")));
    }

    @Test
    void resendForParticipantWithoutEmailShowsFriendlyErrorAndCreatesNothing() throws Exception {
        Event event = createEvent("web_resend_no_email");
        Participant ana = participantService.addByOrganizer(event, "Sem e-mail", null, null);
        UUID anaId = ana.getId();
        Event drawn = eventRepository.findByAdminToken(event.getAdminToken()).orElseThrow();
        drawn.markDrawn(Instant.now());
        eventRepository.saveAndFlush(drawn);

        entityManager.flush();
        entityManager.clear();

        MockHttpSession session = sessionWithCsrfToken();
        mockMvc.perform(post(
                                "/admin/{adminToken}/participants/{id}/email/resend",
                                event.getAdminToken(),
                                anaId)
                        .session(session)
                        .param("_csrf", csrfToken(session)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/" + event.getAdminToken()))
                .andExpect(flash().attribute("error", "Participante sem e-mail cadastrado."));

        assertThat(notificationRepository.findByParticipant_IdAndDrawCountAndChannel(
                        anaId, 1, NotificationChannel.EMAIL))
                .isEmpty();
    }

    // ---------- helpers ----------

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
