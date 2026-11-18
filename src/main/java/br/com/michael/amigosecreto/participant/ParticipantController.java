package br.com.michael.amigosecreto.participant;

import br.com.michael.amigosecreto.common.LinkBuilder;
import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.event.EventStatus;
import br.com.michael.amigosecreto.invite.InviteService;
import br.com.michael.amigosecreto.participant.dto.ParticipantPageView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ParticipantController {

    private static final ZoneId DISPLAY_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DRAWN_AT_FORMAT = DateTimeFormatter.ofPattern("dd/MM");
    private static final List<String> PREVIEW_BOT_MARKERS = List.of(
            "whatsapp",
            "telegrambot",
            "facebookexternalhit",
            "slackbot",
            "twitterbot",
            "linkedinbot",
            "discordbot",
            "skypeuripreview",
            "googlebot",
            "bingbot",
            "applebot",
            "pinterest",
            "embedly",
            "redditbot",
            "snapchat");

    private final ParticipantService participantService;
    private final LinkBuilder linkBuilder;
    private final InviteService inviteService;

    public ParticipantController(
            ParticipantService participantService,
            LinkBuilder linkBuilder,
            InviteService inviteService) {
        this.participantService = participantService;
        this.linkBuilder = linkBuilder;
        this.inviteService = inviteService;
    }

    @GetMapping("/p/{accessToken}")
    public String view(@PathVariable String accessToken, HttpServletRequest request, Model model) {
        Participant participant = participantService.byAccessToken(accessToken);
        model.addAttribute("accessToken", accessToken);
        ParticipantPageView page = pageFor(participant, !isPreviewBot(request));
        model.addAttribute("page", page);
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new WishlistForm(page.ownWishlist()));
        }
        return "participant/view";
    }

    @PostMapping("/p/{accessToken}/wishlist")
    public String updateWishlist(
            @PathVariable String accessToken,
            @Valid @ModelAttribute("form") WishlistForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        Participant participant = participantService.byAccessToken(accessToken);
        model.addAttribute("accessToken", accessToken);
        if (bindingResult.hasErrors()) {
            model.addAttribute("page", pageFor(participant, true));
            model.addAttribute("form", form);
            return "participant/view";
        }

        participantService.updateWishlist(participant, form.wishlist());
        redirectAttributes.addFlashAttribute("message", "Lista de desejos atualizada.");
        redirectAttributes.addAttribute("accessToken", accessToken);
        return "redirect:/p/{accessToken}";
    }

    private ParticipantPageView pageFor(Participant participant, boolean markViewed) {
        Event event = participant.getEvent();
        boolean drawn = event.getStatus() == EventStatus.DRAWN;
        String receiverName = null;
        String receiverWishlist = null;
        if (drawn) {
            var result = participantService.resultFor(participant);
            receiverName = result.map(value -> value.receiverName()).orElse(null);
            receiverWishlist = result.map(value -> value.receiverWishlist()).orElse(null);
        }
        if (drawn && markViewed) {
            participantService.markFirstViewed(participant);
        }

        String drawnAtLabel = null;
        if (drawn && event.getDrawCount() > 1 && event.getDrawnAt() != null) {
            drawnAtLabel = DRAWN_AT_FORMAT.format(event.getDrawnAt().atZone(DISPLAY_ZONE));
        }

        return new ParticipantPageView(
                event.getName(),
                event.getEventDate(),
                event.getSuggestedValue(),
                drawn,
                participant.getName(),
                participant.getWishlist(),
                receiverName,
                receiverWishlist,
                drawn && event.getDrawCount() > 1,
                drawnAtLabel,
                linkBuilder.participant(participant),
                inviteService.participantWhatsappUrl(
                        event, participant.getName(), participant.getAccessToken()));
    }

    private boolean isPreviewBot(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        if (userAgent == null || userAgent.isBlank()) {
            return false;
        }
        String normalized = userAgent.toLowerCase(Locale.ROOT);
        return PREVIEW_BOT_MARKERS.stream().anyMatch(normalized::contains);
    }

    public record WishlistForm(
            @Size(max = 2000, message = "A lista de desejos deve ter até 2.000 caracteres.")
            String wishlist) {
    }
}
