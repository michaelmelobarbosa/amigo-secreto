package br.com.michael.amigosecreto.email;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.michael.amigosecreto.common.AppProperties;
import br.com.michael.amigosecreto.common.LinkBuilder;
import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.participant.Participant;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Conteúdo do aviso de resultado: link individual absoluto, sem wishlist,
 * aviso de sorteio refeito e escape do nome no HTML.
 */
class EmailContentBuilderTest {

    private static final String BASE_URL = "https://app.example.com";
    private final EmailContentBuilder builder =
            new EmailContentBuilder(new LinkBuilder(new AppProperties(BASE_URL)));

    @Test
    void htmlAndTextCarryTheAbsoluteParticipantLink() {
        Event event = event("Família");
        Participant participant = participant(event, "Ana", "p_ana_token");

        EmailContent content = builder.build(event, participant, 1);

        String link = BASE_URL + "/p/p_ana_token";
        assertThat(content.html()).contains("href=\"" + link + "\"");
        assertThat(content.html()).contains(link);
        assertThat(content.text()).contains("Acesse: " + link);
    }

    @Test
    void contentNeverIncludesTheWishlist() {
        Event event = event("Família");
        Participant participant = participant(event, "Ana", "p_ana_token");
        participant.setWishlist("Livro raro que só o sorteado pode ver");

        EmailContent content = builder.build(event, participant, 1);

        assertThat(content.html()).doesNotContain("Livro raro");
        assertThat(content.text()).doesNotContain("Livro raro");
    }

    @Test
    void redrawnContentWarnsThatThePreviousResultWasDiscarded() {
        Event event = event("Família");
        Participant participant = participant(event, "Ana", "p_ana_token");

        EmailContent content = builder.build(event, participant, 2);

        assertThat(content.subject()).startsWith("Sorteio refeito");
        assertThat(content.html()).contains("Sorteio refeito");
        assertThat(content.html()).contains("O resultado anterior foi descartado");
        assertThat(content.text()).contains("O sorteio foi refeito");
        assertThat(content.tags()).containsExactly("amigo-secreto", "draw-2");
    }

    @Test
    void firstDrawContentDoesNotMentionARedraw() {
        Event event = event("Família");
        Participant participant = participant(event, "Ana", "p_ana_token");

        EmailContent content = builder.build(event, participant, 1);

        assertThat(content.subject()).startsWith("Seu resultado do amigo secreto");
        assertThat(content.html()).doesNotContain("refeito");
        assertThat(content.text()).doesNotContain("refeito");
        assertThat(content.tags()).containsExactly("amigo-secreto", "draw-1");
    }

    @Test
    void htmlEscapesScriptTagsInEventAndParticipantNames() {
        Event event = event("<script>alert('evento')</script>");
        Participant participant =
                participant(event, "<script>alert('pessoa')</script>", "p_ana_token");

        EmailContent content = builder.build(event, participant, 1);

        assertThat(content.html()).doesNotContain("<script>");
        assertThat(content.html()).contains("&lt;script&gt;alert(&#39;evento&#39;)&lt;/script&gt;");
        assertThat(content.html()).contains("&lt;script&gt;alert(&#39;pessoa&#39;)&lt;/script&gt;");
    }

    private static Event event(String name) {
        return new Event(
                name,
                LocalDate.of(2026, 12, 24),
                null,
                "adm_test",
                "inv_test",
                Instant.parse("2026-11-14T12:00:00Z"));
    }

    private static Participant participant(Event event, String name, String accessToken) {
        return new Participant(event, name, accessToken, false);
    }
}
