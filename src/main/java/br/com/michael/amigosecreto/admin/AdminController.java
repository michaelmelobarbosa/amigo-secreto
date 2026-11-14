package br.com.michael.amigosecreto.admin;

import br.com.michael.amigosecreto.admin.dto.AdminDashboardView;
import br.com.michael.amigosecreto.admin.dto.AddParticipantForm;
import br.com.michael.amigosecreto.admin.dto.ParticipantRowView;
import br.com.michael.amigosecreto.common.LinkBuilder;
import br.com.michael.amigosecreto.common.RuleViolationException;
import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.event.EventService;
import br.com.michael.amigosecreto.invite.InviteLinks;
import br.com.michael.amigosecreto.invite.InviteService;
import br.com.michael.amigosecreto.participant.Participant;
import br.com.michael.amigosecreto.participant.ParticipantService;
import br.com.michael.amigosecreto.participant.dto.ParticipantRow;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminController {

    private final EventService eventService;
    private final ParticipantService participantService;
    private final InviteService inviteService;
    private final LinkBuilder linkBuilder;

    public AdminController(
            EventService eventService,
            ParticipantService participantService,
            InviteService inviteService,
            LinkBuilder linkBuilder) {
        this.eventService = eventService;
        this.participantService = participantService;
        this.inviteService = inviteService;
        this.linkBuilder = linkBuilder;
    }

    @GetMapping("/admin/{adminToken}")
    public String dashboard(@PathVariable String adminToken, Model model) {
        return renderDashboard(adminToken, model);
    }

    @PostMapping("/admin/{adminToken}/participants")
    public String addParticipant(
            @PathVariable String adminToken,
            @Valid @ModelAttribute("addParticipantForm") AddParticipantForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("addParticipantForm", form);
            return renderDashboard(adminToken, model);
        }

        Event event = eventService.byAdminToken(adminToken);
        try {
            participantService.addByOrganizer(event, form.name(), form.wishlist());
            redirectAttributes.addFlashAttribute("message", "Participante adicionado.");
        } catch (RuleViolationException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return redirectToDashboard(adminToken, redirectAttributes);
    }

    @PostMapping("/admin/{adminToken}/participants/{id}/delete")
    public String removeParticipant(
            @PathVariable String adminToken,
            @PathVariable UUID id,
            RedirectAttributes redirectAttributes) {
        Event event = eventService.byAdminToken(adminToken);
        try {
            participantService.remove(event, id);
            redirectAttributes.addFlashAttribute("message", "Participante removido.");
        } catch (RuleViolationException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return redirectToDashboard(adminToken, redirectAttributes);
    }

    @PostMapping("/admin/{adminToken}/registration")
    public String setRegistration(
            @PathVariable String adminToken,
            @RequestParam("open") boolean open,
            RedirectAttributes redirectAttributes) {
        try {
            eventService.setRegistrationByAdminToken(adminToken, open);
            redirectAttributes.addFlashAttribute(
                    "message", open ? "Inscrições abertas." : "Inscrições encerradas.");
        } catch (RuleViolationException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return redirectToDashboard(adminToken, redirectAttributes);
    }

    @PostMapping("/admin/{adminToken}/invite/regenerate")
    public String regenerateInvite(
            @PathVariable String adminToken,
            RedirectAttributes redirectAttributes) {
        eventService.regenerateInviteCodeByAdminToken(adminToken);
        redirectAttributes.addFlashAttribute("message", "Link de convite atualizado.");
        return redirectToDashboard(adminToken, redirectAttributes);
    }

    private String renderDashboard(String adminToken, Model model) {
        Event event = eventService.byAdminToken(adminToken);
        Participant organizer = eventService.organizerOf(event);
        List<ParticipantRow> rows = participantService.rowsFor(event);

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
                        nameCounts.getOrDefault(row.name(), 0L) > 1,
                        event.isOpen() && !row.organizer()))
                .toList();

        InviteLinks links = inviteService.linksFor(event, organizer);
        AdminDashboardView view = new AdminDashboardView(
                event.getName(),
                event.getEventDate(),
                event.getSuggestedValue(),
                event.isOpen()
                        ? (event.isRegistrationOpen() ? "Inscrições abertas" : "Inscrições encerradas")
                        : "Sorteio realizado",
                event.isRegistrationOpen(),
                event.getDrawCount(),
                event.getDrawnAt(),
                links.inviteUrl(),
                links.inviteWhatsappUrl(),
                links.organizerUrl(),
                links.organizerWhatsappUrl(),
                participants.size(),
                participants.stream().anyMatch(ParticipantRowView::duplicateName),
                participants,
                event.isOpen(),
                event.isOpen() && participants.size() < AdminDashboardView.MAX_PARTICIPANTS,
                AdminDashboardView.MAX_PARTICIPANTS);

        model.addAttribute("event", view);
        model.addAttribute("links", links);
        model.addAttribute("adminToken", adminToken);
        if (!model.containsAttribute("addParticipantForm")) {
            model.addAttribute("addParticipantForm", new AddParticipantForm("", ""));
        }
        return "admin/dashboard";
    }

    private String redirectToDashboard(String adminToken, RedirectAttributes redirectAttributes) {
        redirectAttributes.addAttribute("adminToken", adminToken);
        return "redirect:/admin/{adminToken}";
    }

    @PostMapping("/admin/{adminToken}/delete")
    public String delete(@PathVariable String adminToken, RedirectAttributes redirectAttributes) {
        eventService.deleteByAdminToken(adminToken);
        redirectAttributes.addFlashAttribute("message", "Evento apagado.");
        return "redirect:/";
    }
}
