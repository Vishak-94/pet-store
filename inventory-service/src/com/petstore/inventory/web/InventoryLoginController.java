package com.petstore.inventory.web;

import com.petstore.auth.client.AuthClient;
import com.petstore.auth.client.AuthJwtFilter;
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

    /** Cookie path scoping the JWT to the whole app. */
    private static final String COOKIE_PATH = "/";
    /** Thymeleaf view + redirect targets for the login flow. */
    private static final String VIEW_LOGIN = "login";
    private static final String REDIRECT_INVENTORY = "redirect:/inventory";
    private static final String REDIRECT_LOGGED_OUT = "redirect:/inventory/login?loggedout";
    /** Model attribute + message shown when credentials are rejected. */
    private static final String ATTR_ERROR = "error";
    private static final String MSG_INVALID_CREDENTIALS = "Invalid credentials";

    private final AuthClient auth;

    public InventoryLoginController(AuthClient auth) {
        this.auth = auth;
    }

    @GetMapping("/")
    public String home() {
        return REDIRECT_INVENTORY;
    }

    @GetMapping("/inventory/login")
    public String loginPage() {
        return VIEW_LOGIN;
    }

    @PostMapping("/inventory/login")
    public String doLogin(@RequestParam String username, @RequestParam String password,
                          HttpServletResponse response, Model model) {
        Optional<AuthClient.LoginResult> result = auth.login(username, password);
        if (result.isEmpty()) {
            model.addAttribute(ATTR_ERROR, MSG_INVALID_CREDENTIALS);
            return VIEW_LOGIN;
        }
        // Drop the RS256 token in the same 'jwt' cookie the verify-only AuthJwtFilter reads.
        Cookie c = new Cookie(AuthJwtFilter.JWT_COOKIE, result.get().token());
        c.setPath(COOKIE_PATH);
        c.setHttpOnly(true);
        response.addCookie(c);
        return REDIRECT_INVENTORY;
    }

    @PostMapping("/inventory/logout")
    public String logout(HttpServletResponse response) {
        Cookie c = new Cookie(AuthJwtFilter.JWT_COOKIE, "");
        c.setPath(COOKIE_PATH);
        c.setMaxAge(0);   // expire immediately
        response.addCookie(c);
        return REDIRECT_LOGGED_OUT;
    }
}
