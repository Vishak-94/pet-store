package com.petstore.security;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the login page. Spring Security handles the actual authentication
 * POST to {@code /login} and the {@code /logout} endpoint; this only renders
 * the form (replacing the legacy signon.screen).
 */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
