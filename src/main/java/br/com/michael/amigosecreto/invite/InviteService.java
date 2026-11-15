package br.com.michael.amigosecreto.invite;

import br.com.michael.amigosecreto.common.LinkBuilder;
import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.participant.Participant;
import org.springframework.stereotype.Service;

/**
 * Monta os links e textos de compartilhamento. Não envia nada: no MVP o
 * organizador copia ou abre o WhatsApp manualmente.
 */
@Service
public class InviteService {

    private final LinkBuilder linkBuilder;

    public InviteService(LinkBuilder linkBuilder) {
        this.linkBuilder = linkBuilder;
    }

    public InviteLinks linksFor(Event event, Participant organizer) {
        String organizerUrl = linkBuilder.participant(organizer);
        String inviteUrl = linkBuilder.join(event);

        return new InviteLinks(
                linkBuilder.admin(event),
                organizerUrl,
                inviteUrl,
                linkBuilder.whatsapp(inviteText(event, inviteUrl)),
                linkBuilder.whatsapp(participantText(event, organizer.getName(), organizerUrl)));
    }

    public String participantShareText(Event event, Participant participant) {
        return participantText(event, participant.getName(), linkBuilder.participant(participant));
    }

    /**
     * Texto para download: os três links do organizador, com o aviso de que o
     * painel é a única forma de voltar ao evento.
     */
    public String organizerLinksText(Event event, Participant organizer) {
        InviteLinks links = linksFor(event, organizer);
        return """
                Amigo Secreto — %s

                Painel do organizador (não compartilhe):
                %s

                Convite para o grupo:
                %s

                Seu link como participante:
                %s

                Guarde o link do painel. Sem ele, você não consegue voltar a gerenciar o evento.
                """.formatted(
                event.getName(),
                links.adminUrl(),
                links.inviteUrl(),
                links.organizerUrl());
    }

    public String participantWhatsappUrl(Event event, String participantName, String accessToken) {
        return linkBuilder.whatsapp(
                participantText(event, participantName, linkBuilder.participant(accessToken)));
    }

    public String inviteWhatsappUrl(Event event) {
        return linkBuilder.whatsapp(inviteText(event, linkBuilder.join(event)));
    }

    private String inviteText(Event event, String inviteUrl) {
        return "Você foi convidado para o amigo secreto \"" + event.getName()
                + "\". Entre pelo link: " + inviteUrl;
    }

    private String participantText(Event event, String participantName, String participantUrl) {
        return "Olá, " + participantName + "! Seu link do amigo secreto \"" + event.getName()
                + "\" é: " + participantUrl + ". Guarde este link e não compartilhe.";
    }
}
