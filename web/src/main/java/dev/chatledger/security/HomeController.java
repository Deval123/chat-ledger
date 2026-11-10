package dev.chatledger.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The root address sends everyone to the board, which asks for sign-in if needed. On the demo deployment an anonymous visitor goes to
 * the demo page instead (task 9): judges who open the app's address are one click from the demo.
 */
@Controller
public class HomeController {

    private final boolean demoEnabled;

    public HomeController(@Value("${app.demo.enabled:false}") boolean demoEnabled) {
        this.demoEnabled = demoEnabled;
    }

    @GetMapping("/")
    public String root() {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        boolean signedIn = current != null && current.isAuthenticated() && !(current instanceof AnonymousAuthenticationToken);
        return demoEnabled && !signedIn ? "redirect:/demo" : "redirect:/app";
    }
}
