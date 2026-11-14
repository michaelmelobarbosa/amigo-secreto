package br.com.michael.amigosecreto.admin;

import br.com.michael.amigosecreto.admin.dto.AdminDashboardView;
import br.com.michael.amigosecreto.admin.dto.ParticipantRowView;
import br.com.michael.amigosecreto.common.LinkBuilder;
import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.event.EventService;
import br.com.michael.amigosecreto.invite.InviteLinks;
import br.com.michael.amigosecreto.invite.InviteService;
import br.com.michael.amigosecreto.participant.Participant;
import br.com.michael.amigosecreto.participant.ParticipantRepository;
import br.com.michael.amigosecreto.participant.dto.ParticipantRow;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminController {

    private final EventService eventService;
    private final ParticipantRepository participantRepository;
    private final InviteService inviteService;
    private final LinkBuilder linkBuilder;

    public AdminController(
            EventService eventService,
            ParticipantRepository participantRepository,
            InviteService inviteService,
            LinkBuilder linkBuilder) {
        this.eventService = eventService;
        this.participantRepository = participantRepository;
        this.inviteService = inviteService;
        this.linkBuilder = linkBuilder;
    }

    @GetMapping("/admin/{adminToken}")
    public String dashboard(@PathVariable String adminToken, Model model) {
        Event event = eventService.byAdminToken(adminToken);
        Participant organizer = eventService.organizerOf(event);
        List<ParticipantRow> rows = participantRepository.findRowsByEventId(event.getId());

        Map<String, Long> nameCounts = rows.stream()
                .collect(Collectors.groupingBy(ParticipantRow::name, Collectors.counting()));

        List<ParticipantRowView> participants = rows.stream()
                .map(row -> new ParticipantRowView(
                        row.id(),
                        row.name(),
                        linkBuilder.participant(row.accessToken()),
                        inviteService.participantWhatsappUrl(event, row.name(), row.accessToken()),
                        row.organizer(),
                        row.firstViewedAt(),
                        nameCounts.getOrDefault(row.name(), 0L) > 1))
                .toList();

        InviteLinks links = inviteService.linksFor(event, organizer);
        AdminDashboardView view = new AdminDashboardView(
                event.getName(),
                event.getEventDate(),
                event.getSuggestedValue(),
                event.isOpen() ? "Inscrições abertas" : "Sorteio realizado",
                event.isRegistrationOpen(),
                event.getDrawCount(),
                event.getDrawnAt(),
                links.inviteUrl(),
                links.inviteWhatsappUrl(),
                links.organizerUrl(),
                links.organizerWhatsappUrl(),
                participants.size(),
                participants.stream().anyMatch(ParticipantRowView::duplicateName),
                participants);

        model.addAttribute("event", view);
        model.addAttribute("links", links);
        model.addAttribute("adminToken", adminToken);
        return "admin/dashboard";
    }

    @PostMapping("/admin/{adminToken}/delete")
    public String delete(@PathVariable String adminToken, RedirectAttributes redirectAttributes) {
        eventService.deleteByAdminToken(adminToken);
        redirectAttributes.addFlashAttribute("message", "Evento apagado.");
        return "redirect:/";
    }
}
