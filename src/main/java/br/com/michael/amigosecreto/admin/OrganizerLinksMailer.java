package br.com.michael.amigosecreto.admin;

import br.com.michael.amigosecreto.email.EmailMessage;
import br.com.michael.amigosecreto.email.EmailProperties;
import br.com.michael.amigosecreto.email.EmailSendResult;
import br.com.michael.amigosecreto.email.EmailSender;
import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.invite.InviteLinks;
import br.com.michael.amigosecreto.invite.InviteService;
import br.com.michael.amigosecreto.participant.Participant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Envia, sob demanda, os links de acesso do organizador para um e-mail
 * informado no painel. É um envio pontual: nada é persistido e o e-mail não
 * vira login nem canal de recuperação automática.
 */
@Component
public class OrganizerLinksMailer {

    private static final Logger log = LoggerFactory.getLogger(OrganizerLinksMailer.class);

    private final EmailProperties properties;
    private final EmailSender emailSender;
    private final InviteService inviteService;

    public OrganizerLinksMailer(
            EmailProperties properties,
            EmailSender emailSender,
            InviteService inviteService) {
        this.properties = properties;
        this.emailSender = emailSender;
        this.inviteService = inviteService;
    }

    public Result send(Event event, Participant organizer, String toEmail) {
        if (!properties.enabled()) {
            return Result.disabledResult();
        }

        InviteLinks links = inviteService.linksFor(event, organizer);
        EmailMessage message = new EmailMessage(
                toEmail,
                organizer.getName(),
                "Seus links do amigo secreto — " + singleLine(event.getName()),
                html(event, organizer, links),
                text(event, organizer, links),
                UUID.randomUUID(),
                List.of("amigo-secreto", "organizer-links"));

        try {
            EmailSendResult result = emailSender.send(message);
            if (!result.accepted()) {
                log.warn("Brevo recusou os links do organizador: {}", result.errorCode());
            }
            return new Result(result.accepted(), isSandbox(), false);
        } catch (RuntimeException exception) {
            // Sem e-mail ou token no log; a classe basta para diagnóstico.
            log.warn("Falha ao enviar links do organizador: {}", exception.getClass().getSimpleName());
            return Result.rejectedResult();
        }
    }

    private boolean isSandbox() {
        return properties.brevo() != null && properties.brevo().sandbox();
    }

    private String html(Event event, Participant organizer, InviteLinks links) {
        String eventName = HtmlUtils.htmlEscape(event.getName());
        String organizerName = HtmlUtils.htmlEscape(organizer.getName());
        String adminUrl = HtmlUtils.htmlEscape(links.adminUrl());
        String inviteUrl = HtmlUtils.htmlEscape(links.inviteUrl());
        String participantUrl = HtmlUtils.htmlEscape(links.organizerUrl());

        return """
                <!DOCTYPE html>
                <html lang="pt-BR">
                <body style="margin:0;padding:0;background:#f5f7fa;font-family:Arial,Helvetica,sans-serif;color:#182230;">
                  <div style="max-width:560px;margin:0 auto;padding:24px;">
                    <div style="background:#ffffff;border:1px solid #cbd5e1;border-radius:12px;padding:24px;">
                      <p style="margin:0 0 12px;">Olá, <strong>%s</strong>.</p>
                      <p style="margin:0 0 20px;">Estes são os links do amigo secreto <strong>%s</strong>.</p>

                      <h2 style="margin:0 0 8px;font-size:18px;">Painel do organizador</h2>
                      <p style="margin:0 0 8px;"><a href="%s" style="color:#075985;">Abrir o painel</a></p>
                      <p style="margin:0 0 8px;font-size:13px;word-break:break-all;">%s</p>
                      <p style="margin:0 0 20px;padding:12px;background:#fefce8;border:1px solid #fde047;border-radius:8px;font-size:14px;">
                        <strong>Não compartilhe este link.</strong> Sem ele, você não consegue voltar a gerenciar o evento.
                      </p>

                      <h2 style="margin:0 0 8px;font-size:18px;">Convite para o grupo</h2>
                      <p style="margin:0 0 8px;"><a href="%s" style="color:#075985;">Abrir o convite</a></p>
                      <p style="margin:0 0 8px;font-size:13px;word-break:break-all;">%s</p>
                      <p style="margin:0 0 20px;font-size:14px;color:#475569;">Compartilhe este link com quem vai participar.</p>

                      <h2 style="margin:0 0 8px;font-size:18px;">Seu link como participante</h2>
                      <p style="margin:0 0 8px;"><a href="%s" style="color:#075985;">Ver meu resultado</a></p>
                      <p style="margin:0 0 8px;font-size:13px;word-break:break-all;">%s</p>
                      <p style="margin:0;font-size:14px;color:#475569;">Guarde este link: ele mostra seu resultado e sua lista de desejos.</p>
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(
                organizerName,
                eventName,
                adminUrl,
                adminUrl,
                inviteUrl,
                inviteUrl,
                participantUrl,
                participantUrl);
    }

    private String text(Event event, Participant organizer, InviteLinks links) {
        return """
                Olá, %s.

                Estes são os links do amigo secreto "%s".

                Painel do organizador (não compartilhe):
                %s

                Convite para o grupo:
                %s

                Seu link como participante:
                %s

                Guarde o link do painel. Sem ele, você não consegue voltar a gerenciar o evento.
                """.formatted(
                organizer.getName(),
                event.getName(),
                links.adminUrl(),
                links.inviteUrl(),
                links.organizerUrl());
    }

    private static String singleLine(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
    }

    public record Result(boolean accepted, boolean sandbox, boolean disabled) {

        static Result disabledResult() {
            return new Result(false, false, true);
        }

        static Result rejectedResult() {
            return new Result(false, false, false);
        }
    }
}
