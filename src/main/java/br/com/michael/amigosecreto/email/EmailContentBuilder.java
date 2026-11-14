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
                ? "<p style=\"margin:0 0 16px;padding:12px;background:#fefce8;border:1px solid #fde047;border-radius:8px;\">"
                        + "<strong>Sorteio refeito.</strong> O resultado anterior foi descartado; veja o novo.</p>"
                : "";

        String html = """
                <!DOCTYPE html>
                <html lang="pt-BR">
                <body style="margin:0;padding:0;background:#f5f7fa;font-family:Arial,Helvetica,sans-serif;color:#182230;">
                  <div style="max-width:560px;margin:0 auto;padding:24px;">
                    <div style="background:#ffffff;border:1px solid #cbd5e1;border-radius:12px;padding:24px;">
                      <p style="margin:0 0 12px;">Olá, <strong>%s</strong>.</p>
                      %s
                      <p style="margin:0 0 16px;">Seu resultado do amigo secreto <strong>%s</strong> está pronto.</p>
                      <p style="margin:0 0 20px;">
                        <a href="%s" style="display:inline-block;padding:12px 18px;background:#075985;color:#ffffff;text-decoration:none;border-radius:6px;font-weight:bold;">Ver meu resultado</a>
                      </p>
                      <p style="margin:0 0 8px;font-size:14px;color:#475569;">Se o botão não funcionar, copie e cole este link no navegador:</p>
                      <p style="margin:0 0 20px;font-size:13px;word-break:break-all;">%s</p>
                      <p style="margin:0;font-size:14px;color:#475569;">Guarde este link: ele é a única forma de ver seu resultado. Não compartilhe.</p>
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
