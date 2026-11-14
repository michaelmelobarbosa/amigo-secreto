package br.com.michael.amigosecreto.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.participant.Participant;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LinkBuilderTest {

    private final LinkBuilder linkBuilder =
            new LinkBuilder(new AppProperties("http://localhost:8080/"));

    @Test
    void buildsAbsoluteLinksWithoutDoubleSlash() {
        Event event = new Event("Família", null, null, "adm_123", "inv_456", Instant.now());
        Participant participant = new Participant(event, "Ana", "p_789", true);

        assertEquals("http://localhost:8080/admin/adm_123", linkBuilder.admin(event));
        assertEquals("http://localhost:8080/p/p_789", linkBuilder.participant(participant));
        assertEquals("http://localhost:8080/join/inv_456", linkBuilder.join(event));
    }

    @Test
    void encodesWhatsAppMessage() {
        String url = linkBuilder.whatsapp("Olá & você");

        assertTrue(url.startsWith("https://wa.me/?text="));
        assertTrue(url.contains("%20"));
        assertTrue(url.contains("%26"));
        assertFalse(url.contains("+"));
    }
}
