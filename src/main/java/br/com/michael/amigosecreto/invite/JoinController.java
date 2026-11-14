package br.com.michael.amigosecreto.invite;

import br.com.michael.amigosecreto.common.RuleViolationException;
import br.com.michael.amigosecreto.event.Event;
import br.com.michael.amigosecreto.event.EventService;
import br.com.michael.amigosecreto.invite.dto.JoinForm;
import br.com.michael.amigosecreto.participant.Participant;
import br.com.michael.amigosecreto.participant.ParticipantService;
import jakarta.validation.Valid;
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class JoinController {

    private final EventService eventService;
    private final ParticipantService participantService;

    public JoinController(EventService eventService, ParticipantService participantService) {
        this.eventService = eventService;
        this.participantService = participantService;
    }

    /** O e-mail chega com espaços do formulário; valida já sem eles. */
    @InitBinder("form")
    public void trimEmail(WebDataBinder binder) {
        binder.registerCustomEditor(String.class, "email", new StringTrimmerEditor(true));
    }

    @GetMapping("/join/{inviteCode}")
    public String form(@PathVariable String inviteCode, Model model) {
        Event event = eventService.byInviteCode(inviteCode);
        model.addAttribute("inviteCode", inviteCode);
        addEventDetails(event, model);
        if (!event.canJoin()) {
            return "join/closed";
        }
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new JoinForm("", "", ""));
        }
        return "join/form";
    }

    @PostMapping("/join/{inviteCode}")
    public String join(
            @PathVariable String inviteCode,
            @Valid @ModelAttribute("form") JoinForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        Event event = eventService.byInviteCode(inviteCode);
        model.addAttribute("inviteCode", inviteCode);
        addEventDetails(event, model);
        if (!event.canJoin()) {
            return "join/closed";
        }
        if (bindingResult.hasErrors()) {
            return "join/form";
        }

        try {
            Participant participant = participantService.joinByInvite(
                    event, form.name(), form.email(), form.wishlist());
            redirectAttributes.addFlashAttribute("message", "Guarde este link: é por ele que você verá o resultado.");
            redirectAttributes.addAttribute("accessToken", participant.getAccessToken());
            return "redirect:/p/{accessToken}";
        } catch (RuleViolationException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
            redirectAttributes.addFlashAttribute("form", form);
            redirectAttributes.addAttribute("inviteCode", inviteCode);
            return "redirect:/join/{inviteCode}";
        }
    }

    private void addEventDetails(Event event, Model model) {
        model.addAttribute("eventName", event.getName());
        model.addAttribute("eventDate", event.getEventDate());
        model.addAttribute("suggestedValue", event.getSuggestedValue());
    }
}
