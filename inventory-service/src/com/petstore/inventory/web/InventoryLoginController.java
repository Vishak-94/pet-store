package com.petstore.inventory.web;

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
 * Supplier staff login for the inventory UI. Delegates credential checking to
 * auth-service via the {@link AuthClient}; on success the returned RS256 token is
 * dropped into a 'jwt' cookie the verify-only AuthJwtFilter reads. inventory-
 * service holds no credentials of its own.
 */
@Controller
public class InventoryLoginController {

    private final AuthClient auth;

    public InventoryLoginController(AuthClient auth) {
        this.auth = auth;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/inventory";
    }

    @GetMapping("/inventory/login")
    public String loginPage() {
        return "login";
    }

    @PostMapping("/inventory/login")
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
        return "redirect:/inventory";
    }

    @PostMapping("/inventory/logout")
    public String logout(HttpServletResponse response) {
        Cookie c = new Cookie("jwt", "");
        c.setPath("/");
        c.setMaxAge(0);
        response.addCookie(c);
        return "redirect:/inventory/login?loggedout";
    }
}
