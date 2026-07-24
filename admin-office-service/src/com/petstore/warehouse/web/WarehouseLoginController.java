package com.petstore.warehouse.web;

import com.petstore.auth.client.AuthClient;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;

/**
 * Admin staff login for the warehouse UI. Delegates credential checking to
 * auth-service via the {@link AuthClient}; the returned RS256 token is dropped
 * into a 'jwt' cookie the verify-only AuthJwtFilter reads. warehouse-service holds
 * no credentials of its own.
 */
@Controller
public class WarehouseLoginController {

    private final AuthClient auth;

    public WarehouseLoginController(AuthClient auth) {
        this.auth = auth;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/warehouse/orders";
    }

    @GetMapping("/warehouse/login")
    public String loginPage() {
        return "login";
    }

    @PostMapping("/warehouse/login")
    public String doLogin(@RequestParam String username, @RequestParam String password,
                          HttpServletResponse response, Model model) {
        Optional<AuthClient.LoginResult> result = auth.login(username, password);
        if (result.isEmpty()) {
            model.addAttribute("error", "Invalid credentials");
            return "login";
        }
        Cookie c = new Cookie("jwt", result.get().token());
        c.setPath("/");
        c.setHttpOnly(true);
        response.addCookie(c);
        return "redirect:/warehouse/orders";
    }

    @PostMapping("/warehouse/logout")
    public String logout(HttpServletResponse response) {
        Cookie c = new Cookie("jwt", "");
        c.setPath("/");
        c.setMaxAge(0);
        response.addCookie(c);
        return "redirect:/warehouse/login?loggedout";
    }
}
