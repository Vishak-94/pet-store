package com.petstore.web;

import com.petstore.cart.service.CartService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Injects {@code cartCount} into the model of every HTML view, so the nav-bar
 * cart badge is correct on every page load (not just after a live JS update).
 *
 * <p>Scoped to {@code @Controller}s (annotations = Controller.class) so it does
 * NOT run for {@code @RestController} JSON endpoints.
 */
@ControllerAdvice(annotations = org.springframework.stereotype.Controller.class)
public class GlobalModelAdvice {

    private final CartService cart;

    public GlobalModelAdvice(CartService cart) {
        this.cart = cart;
    }

    @ModelAttribute("cartCount")
    public int cartCount() {
        return cart.getCount();
    }
}
