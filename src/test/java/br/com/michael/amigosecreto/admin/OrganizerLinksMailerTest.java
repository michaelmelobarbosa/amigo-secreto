package br.com.michael.amigosecreto.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.michael.amigosecreto.common.AppProperties;
import br.com.michael.amigosecreto.common.LinkBuilder;
import br.com.michael.amigosecreto.email.EmailMessage;
import br.com.michael.amigosecreto.email.EmailProperties;
import br.com.michael.amigosecreto.email.EmailSendResult;
import br.com.michael.amigosecreto.email.EmailSender;
import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.invite.InviteService;
import br.com.michael.amigosecreto.participant.Participant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OrganizerLinksMailerTest {

    private final Event event = mock(Event.class);
    private final Participant organizer = mock(Participant.class);

    OrganizerLinksMailerTest() {
        when(event.getName()).thenReturn("Família");
        when(event.getAdminToken()).thenReturn("adm_test");
        when(event.getInviteCode()).thenReturn("inv_test");
        when(organizer.getName()).thenReturn("Michael");
        when(organizer.getAccessToken()).thenReturn("p_test");
    }

    @Test
    void sendsTheThreeLinksToTheGivenEmail() {
        EmailSender sender = mock(EmailSender.class);
        when(sender.send(any())).thenReturn(EmailSendResult.accepted("brevo-id"));
        OrganizerLinksMailer mailer = mailer(sender, enabledProperties(false));

        OrganizerLinksMailer.Result result =
                mailer.send(event, organizer, "destino@example.com");

        assertThat(result.accepted()).isTrue();
        assertThat(result.sandbox()).isFalse();
        assertThat(result.disabled()).isFalse();

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(sender).send(captor.capture());
        EmailMessage message = captor.getValue();
        assertThat(message.toEmail()).isEqualTo("destino@example.com");
        assertThat(message.subject()).contains("Família");
        assertThat(message.html())
                .contains("https://example.test/admin/adm_test")
                .contains("https://example.test/join/inv_test")
                .contains("https://example.test/p/p_test");
        assertThat(message.text())
                .contains("https://example.test/admin/adm_test")
                .contains("https://example.test/join/inv_test")
                .contains("https://example.test/p/p_test")
                .contains("não compartilhe");
        assertThat(message.idempotencyKey()).isNotNull();
    }

    @Test
    void reportsSandboxWithoutPretendingItWasDelivered() {
        EmailSender sender = mock(EmailSender.class);
        when(sender.send(any())).thenReturn(EmailSendResult.accepted("brevo-id"));
        OrganizerLinksMailer mailer = mailer(sender, enabledProperties(true));

        OrganizerLinksMailer.Result result =
                mailer.send(event, organizer, "destino@example.com");

        assertThat(result.accepted()).isTrue();
        assertThat(result.sandbox()).isTrue();
        assertThat(result.disabled()).isFalse();
    }

    @Test
    void doesNotCallTheProviderWhenEmailIsDisabled() {
        EmailSender sender = mock(EmailSender.class);
        EmailProperties properties = new EmailProperties(
                false,
                "no-reply@example.test",
                "Amigo Secreto",
                null,
                new EmailProperties.Brevo("key", true));
        OrganizerLinksMailer mailer = mailer(sender, properties);

        OrganizerLinksMailer.Result result =
                mailer.send(event, organizer, "destino@example.com");

        assertThat(result.disabled()).isTrue();
        assertThat(result.accepted()).isFalse();
        verifyNoInteractions(sender);
    }

    @Test
    void reportsProviderRejectionWithoutThrowing() {
        EmailSender sender = mock(EmailSender.class);
        when(sender.send(any()))
                .thenReturn(EmailSendResult.rejected("invalid", "erro", false));
        OrganizerLinksMailer mailer = mailer(sender, enabledProperties(false));

        OrganizerLinksMailer.Result result =
                mailer.send(event, organizer, "destino@example.com");

        assertThat(result.accepted()).isFalse();
        assertThat(result.disabled()).isFalse();
    }

    private OrganizerLinksMailer mailer(EmailSender sender, EmailProperties properties) {
        LinkBuilder linkBuilder = new LinkBuilder(new AppProperties("https://example.test"));
        return new OrganizerLinksMailer(properties, sender, new InviteService(linkBuilder));
    }

    private EmailProperties enabledProperties(boolean sandbox) {
        return new EmailProperties(
                true,
                "no-reply@example.test",
                "Amigo Secreto",
                null,
                new EmailProperties.Brevo("key", sandbox));
    }
}
