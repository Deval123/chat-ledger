package dev.chatledger.security;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The landing page after sign-in. The board itself arrives in task 8; this only shows that the
 * user is signed in and which role they have.
 */
@Controller
public class HomeController {

    /** The root address sends everyone to the application, which asks for sign-in if needed. */
    @GetMapping("/")
    public String root() {
        return "redirect:/app";
    }

    @GetMapping("/app")
    @PreAuthorize("hasAuthority('VIEW_BOARD')")
    public String home(@AuthenticationPrincipal TenantUserDetails user, Model model) {
        model.addAttribute("role", user.role().name());
        return "app/home";
    }
}
