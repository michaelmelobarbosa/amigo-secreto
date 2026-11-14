package br.com.michael.amigosecreto.admin;

import br.com.michael.amigosecreto.admin.dto.AdminDashboardView;
import br.com.michael.amigosecreto.admin.dto.AddParticipantForm;
import br.com.michael.amigosecreto.admin.dto.ExclusionGroupForm;
import br.com.michael.amigosecreto.admin.dto.ParticipantRowView;
import br.com.michael.amigosecreto.common.LinkBuilder;
import br.com.michael.amigosecreto.common.RuleViolationException;
import br.com.michael.amigosecreto.draw.DrawImpossibleException;
import br.com.michael.amigosecreto.draw.DrawService;
import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.event.EventStatus;
import br.com.michael.amigosecreto.event.EventService;
import br.com.michael.amigosecreto.exclusion.ExclusionGroupService;
import br.com.michael.amigosecreto.exclusion.dto.ExclusionGroupView;
import br.com.michael.amigosecreto.invite.InviteLinks;
import br.com.michael.amigosecreto.invite.InviteService;
import br.com.michael.amigosecreto.participant.Participant;
import br.com.michael.amigosecreto.participant.ParticipantService;
import br.com.michael.amigosecreto.participant.dto.ParticipantRow;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
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

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DRAWN_AT_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final EventService eventService;
    private final ParticipantService participantService;
    private final InviteService inviteService;
    private final LinkBuilder linkBuilder;
    private final ExclusionGroupService exclusionGroupService;
    private final DrawService drawService;

    public AdminController(
            EventService eventService,
            ParticipantService participantService,
            InviteService inviteService,
            LinkBuilder linkBuilder,
            ExclusionGroupService exclusionGroupService,
            DrawService drawService) {
        this.eventService = eventService;
        this.participantService = participantService;
        this.inviteService = inviteService;
        this.linkBuilder = linkBuilder;
        this.exclusionGroupService = exclusionGroupService;
        this.drawService = drawService;
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

    @PostMapping("/admin/{adminToken}/exclusions")
    public String createExclusionGroup(
            @PathVariable String adminToken,
            @Valid @ModelAttribute("exclusionGroupForm") ExclusionGroupForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("exclusionGroupForm", form);
            return renderDashboard(adminToken, model);
        }

        Event event = eventService.byAdminToken(adminToken);
        try {
            exclusionGroupService.create(event, form.label(), new HashSet<>(form.memberIds()));
            redirectAttributes.addFlashAttribute("message", "Restrição criada.");
        } catch (RuleViolationException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return redirectToDashboard(adminToken, redirectAttributes);
    }

    @PostMapping("/admin/{adminToken}/exclusions/{id}/delete")
    public String removeExclusionGroup(
            @PathVariable String adminToken,
            @PathVariable UUID id,
            RedirectAttributes redirectAttributes) {
        Event event = eventService.byAdminToken(adminToken);
        try {
            exclusionGroupService.remove(event, id);
            redirectAttributes.addFlashAttribute("message", "Restrição removida.");
        } catch (RuleViolationException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return redirectToDashboard(adminToken, redirectAttributes);
    }

    @PostMapping("/admin/{adminToken}/draw")
    public String draw(
            @PathVariable String adminToken,
            RedirectAttributes redirectAttributes) {
        try {
            drawService.drawByAdminToken(adminToken);
            redirectAttributes.addFlashAttribute("message", "Sorteio realizado.");
        } catch (DrawImpossibleException | RuleViolationException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return redirectToDashboard(adminToken, redirectAttributes);
    }

    @PostMapping("/admin/{adminToken}/redraw")
    public String redraw(
            @PathVariable String adminToken,
            RedirectAttributes redirectAttributes) {
        try {
            drawService.redrawByAdminToken(adminToken);
            redirectAttributes.addFlashAttribute("message", "Sorteio refeito.");
        } catch (DrawImpossibleException | RuleViolationException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return redirectToDashboard(adminToken, redirectAttributes);
    }

    @PostMapping("/admin/{adminToken}/reopen")
    public String reopen(
            @PathVariable String adminToken,
            RedirectAttributes redirectAttributes) {
        try {
            eventService.reopenByAdminToken(adminToken);
            redirectAttributes.addFlashAttribute("message", "Evento reaberto para edição.");
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
        List<ExclusionGroupView> exclusionGroups = exclusionGroupService.viewsFor(event);

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
        boolean drawn = event.getStatus() == EventStatus.DRAWN;
        boolean hasExclusionGroups = !exclusionGroups.isEmpty();
        int minParticipantsToDraw = hasExclusionGroups ? 4 : 3;
        boolean canDraw = event.isOpen() && participants.size() >= minParticipantsToDraw;
        String drawDisabledReason = event.isOpen() && !canDraw
                ? (hasExclusionGroups
                        ? "Com restrições, são necessários pelo menos 4 participantes."
                        : "São necessários pelo menos 3 participantes.")
                : null;
        String drawnAtLabel = drawn && event.getDrawnAt() != null
                ? DRAWN_AT_FORMAT.format(event.getDrawnAt().atZone(SAO_PAULO))
                : null;
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
                AdminDashboardView.MAX_PARTICIPANTS,
                exclusionGroups,
                event.isOpen(),
                canDraw,
                drawDisabledReason,
                drawn,
                event.getDrawCount() > 1,
                drawnAtLabel);

        model.addAttribute("event", view);
        model.addAttribute("links", links);
        model.addAttribute("adminToken", adminToken);
        if (!model.containsAttribute("addParticipantForm")) {
            model.addAttribute("addParticipantForm", new AddParticipantForm("", ""));
        }
        if (!model.containsAttribute("exclusionGroupForm")) {
            model.addAttribute("exclusionGroupForm", new ExclusionGroupForm("", List.of()));
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
