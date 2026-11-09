package dev.chatledger.security;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The root address sends everyone to the board, which asks for sign-in if needed. */
@Controller
public class HomeController {

    @GetMapping("/")
    public String root() {
        return "redirect:/app";
    }
}
