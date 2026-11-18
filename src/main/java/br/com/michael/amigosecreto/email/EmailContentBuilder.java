package br.com.michael.amigosecreto.email;

import br.com.michael.amigosecreto.common.LinkBuilder;
import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.participant.Participant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Monta o conteúdo do aviso de resultado. O link individual é o mesmo que a
 * pessoa já usa na página; o e-mail não carrega a wishlist nem qualquer outro
 * participante.
 */
@Component
public class EmailContentBuilder {

    private final LinkBuilder linkBuilder;

    public EmailContentBuilder(LinkBuilder linkBuilder) {
        this.linkBuilder = linkBuilder;
    }

    public EmailContent build(Event event, Participant participant, int drawCount) {
        String link = linkBuilder.participant(participant);
        String participantName = HtmlUtils.htmlEscape(participant.getName());
        String eventName = HtmlUtils.htmlEscape(event.getName());
        String safeLink = HtmlUtils.htmlEscape(link);
        boolean redrawn = drawCount > 1;

        String subject = (redrawn ? "Sorteio refeito — " : "Seu resultado do amigo secreto — ")
                + singleLine(event.getName());

        String redrawNotice = redrawn
                ? "<p style=\"margin:0 0 16px;padding:12px 14px;background:#fffbeb;border:1px solid #fde68a;border-radius:10px;color:#92400e;font-size:14px;\">"
                        + "<strong>Sorteio refeito.</strong> O resultado anterior foi descartado; veja o novo.</p>"
                : "";

        String html = """
                <!DOCTYPE html>
                <html lang="pt-BR">
                <body style="margin:0;padding:0;background:#f4f6f8;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif;color:#1e293b;line-height:1.55;">
                  <div style="max-width:560px;margin:0 auto;padding:28px 16px;">
                    <div style="background:#ffffff;border:1px solid #e2e8f0;border-top:4px solid #059669;border-radius:14px;padding:28px 24px;">
                      <p style="margin:0 0 6px;font-size:12px;font-weight:bold;letter-spacing:0.06em;text-transform:uppercase;color:#059669;">Amigo Secreto</p>
                      <p style="margin:0 0 14px;font-size:16px;color:#0f172a;">Olá, <strong>%s</strong>.</p>
                      %s
                      <p style="margin:0 0 20px;color:#334155;">Seu resultado do amigo secreto <strong style="color:#0f172a;">%s</strong> está pronto.</p>
                      <p style="margin:0 0 22px;">
                        <a href="%s" style="display:inline-block;padding:12px 20px;background:#059669;color:#ffffff;text-decoration:none;border-radius:10px;font-weight:bold;font-size:15px;">Ver meu resultado</a>
                      </p>
                      <p style="margin:0 0 6px;font-size:13px;color:#64748b;">Se o botão não abrir, copie e cole este endereço no navegador:</p>
                      <p style="margin:0 0 20px;padding:10px 12px;background:#f8fafc;border:1px solid #e2e8f0;border-radius:8px;font-family:Consolas,monospace;font-size:12px;color:#0f172a;word-break:break-all;">%s</p>
                      <p style="margin:0;font-size:13px;color:#475569;">Guarde este link: ele é a única forma de ver seu resultado. Não compartilhe.</p>
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(participantName, redrawNotice, eventName, safeLink, safeLink);

        String text = """
                Olá, %s.

                Seu resultado do amigo secreto "%s" está pronto.

                %sAcesse: %s

                Guarde este link: ele é a única forma de ver seu resultado. Não compartilhe.
                """.formatted(
                participant.getName(),
                event.getName(),
                redrawn ? "O sorteio foi refeito; o resultado anterior foi descartado.\n\n" : "",
                link);

        return new EmailContent(subject, html, text, List.of("amigo-secreto", "draw-" + drawCount));
    }

    private static String singleLine(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
    }
}
