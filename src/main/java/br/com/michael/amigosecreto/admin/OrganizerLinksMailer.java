package br.com.michael.amigosecreto.admin;

import br.com.michael.amigosecreto.email.EmailMessage;
import br.com.michael.amigosecreto.email.EmailProperties;
import br.com.michael.amigosecreto.email.EmailSendResult;
import br.com.michael.amigosecreto.email.EmailSender;
import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.invite.InviteLinks;
import br.com.michael.amigosecreto.invite.InviteService;
import br.com.michael.amigosecreto.notification.EmailQuotaGuard;
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
    private final EmailQuotaGuard emailQuotaGuard;

    public OrganizerLinksMailer(
            EmailProperties properties,
            EmailSender emailSender,
            InviteService inviteService,
            EmailQuotaGuard emailQuotaGuard) {
        this.properties = properties;
        this.emailSender = emailSender;
        this.inviteService = inviteService;
        this.emailQuotaGuard = emailQuotaGuard;
    }

    public Result send(Event event, Participant organizer, String toEmail) {
        if (!properties.enabled()) {
            return Result.disabledResult();
        }
        if (!emailQuotaGuard.allowSend(event.getId())) {
            log.warn("Envio avulso dos links recusado: cota diária de e-mail atingida.");
            return Result.quotaExceededResult();
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
            if (result.accepted()) {
                emailQuotaGuard.recordDirectSend(event.getId());
            } else {
                log.warn("Brevo recusou os links do organizador: {}", result.errorCode());
            }
            return new Result(result.accepted(), isSandbox(), false, false);
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
                <body style="margin:0;padding:0;background:#f4f6f8;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif;color:#1e293b;line-height:1.55;">
                  <div style="max-width:560px;margin:0 auto;padding:28px 16px;">
                    <div style="background:#ffffff;border:1px solid #e2e8f0;border-top:4px solid #059669;border-radius:14px;padding:28px 24px;">
                      <p style="margin:0 0 6px;font-size:12px;font-weight:bold;letter-spacing:0.06em;text-transform:uppercase;color:#059669;">Amigo Secreto</p>
                      <p style="margin:0 0 8px;font-size:16px;color:#0f172a;">Olá, <strong>%s</strong>.</p>
                      <p style="margin:0 0 22px;color:#334155;">Estes são os links de acesso do amigo secreto <strong style="color:#0f172a;">%s</strong>.</p>

                      <div style="margin:0 0 16px;padding:16px;background:#fffafa;border:1px solid #fecaca;border-radius:10px;">
                        <h2 style="margin:0 0 6px;font-size:16px;color:#0f172a;">Painel do organizador (privado)</h2>
                        <p style="margin:0 0 8px;"><a href="%s" style="color:#059669;font-weight:bold;">Abrir o painel</a></p>
                        <p style="margin:0 0 10px;font-family:Consolas,monospace;font-size:12px;color:#0f172a;word-break:break-all;">%s</p>
                        <p style="margin:0;font-size:13px;color:#991b1b;">
                          <strong>Não compartilhe este link.</strong> Sem ele, você não consegue voltar a gerenciar o evento.
                        </p>
                      </div>

                      <div style="margin:0 0 16px;padding:16px;background:#f8fafc;border:1px solid #e2e8f0;border-radius:10px;">
                        <h2 style="margin:0 0 6px;font-size:16px;color:#0f172a;">Convite para o grupo</h2>
                        <p style="margin:0 0 8px;"><a href="%s" style="color:#059669;font-weight:bold;">Abrir o convite</a></p>
                        <p style="margin:0 0 8px;font-family:Consolas,monospace;font-size:12px;color:#0f172a;word-break:break-all;">%s</p>
                        <p style="margin:0;font-size:13px;color:#475569;">Compartilhe este link com quem vai participar.</p>
                      </div>

                      <div style="margin:0;padding:16px;background:#f8fafc;border:1px solid #e2e8f0;border-radius:10px;">
                        <h2 style="margin:0 0 6px;font-size:16px;color:#0f172a;">Seu link como participante</h2>
                        <p style="margin:0 0 8px;"><a href="%s" style="color:#059669;font-weight:bold;">Ver meu resultado</a></p>
                        <p style="margin:0 0 8px;font-family:Consolas,monospace;font-size:12px;color:#0f172a;word-break:break-all;">%s</p>
                        <p style="margin:0;font-size:13px;color:#475569;">Guarde este link: ele mostra seu resultado e sua lista de desejos.</p>
                      </div>
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

    public record Result(boolean accepted, boolean sandbox, boolean disabled, boolean quotaExceeded) {

        static Result disabledResult() {
            return new Result(false, false, true, false);
        }

        static Result rejectedResult() {
            return new Result(false, false, false, false);
        }

        static Result quotaExceededResult() {
            return new Result(false, false, false, true);
        }
    }
}
