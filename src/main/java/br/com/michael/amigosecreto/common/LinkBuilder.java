package br.com.michael.amigosecreto.common;

import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.participant.Participant;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
public class LinkBuilder {

    private final String baseUrl;

    public LinkBuilder(AppProperties properties) {
        this.baseUrl = properties.baseUrl();
    }

    public String admin(Event event) {
        return baseUrl + "/admin/" + event.getAdminToken();
    }

    public String participant(Participant participant) {
        return baseUrl + "/p/" + participant.getAccessToken();
    }

    public String join(Event event) {
        return baseUrl + "/join/" + event.getInviteCode();
    }

    public String whatsapp(String message) {
        String encoded = URLEncoder.encode(message, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "https://wa.me/?text=" + encoded;
    }
}
