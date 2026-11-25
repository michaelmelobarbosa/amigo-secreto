package br.com.michael.amigosecreto.event;

import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping("/")
    public String index(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new CreateEventForm("", null, null, ""));
        }
        return "index";
    }

    @PostMapping("/events")
    public String create(
            @Valid @ModelAttribute("form") CreateEventForm form,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            return "index";
        }

        Event event = eventService.create(new CreateEventCommand(
                form.name(),
                form.eventDate(),
                form.suggestedValue(),
                form.organizerName()));

        redirectAttributes.addFlashAttribute("created", true);
        redirectAttributes.addAttribute("adminToken", event.getAdminToken());
        return "redirect:/admin/{adminToken}";
    }
}
