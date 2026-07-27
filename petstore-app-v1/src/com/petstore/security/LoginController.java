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

    /**
     * Renders the sign-on form. The actual authentication POST to {@code /login}
     * (and {@code /logout}) is handled by Spring Security, not this method.
     *
     * <pre>{@code
     * GET /login
     * GET /login?error       // after a failed sign-on
     * GET /login?registered  // after a successful sign-up (see RegistrationController)
     *
     * 200 OK  renders login.html
     * }</pre>
     */
    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
