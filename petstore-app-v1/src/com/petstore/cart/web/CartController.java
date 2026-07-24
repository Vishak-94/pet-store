package com.petstore.cart.web;

import com.petstore.cart.service.CartService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

/**
 * Cart UI — replaces the legacy WAF {@code cart.do} + CartEJBAction. Thin
 * controller delegating to the session-scoped {@link CartService}. Actions map
 * to the legacy CartEvent types: add / delete / update.
 *
 * <p>The add/change operations return JSON (used by the in-page quantity
 * stepper, so the shopper stays on the catalog page instead of being redirected
 * to the cart). The full-page update/delete (from the cart page itself) still
 * redirect back to the cart.
 */
@Controller
public class CartController {

    private final CartService cart;

    public CartController(CartService cart) {
        this.cart = cart;
    }

    @GetMapping("/cart")
    public String view(Model model) {
        model.addAttribute("items", cart.getItems());
        model.addAttribute("subtotal", cart.getSubTotal());
        model.addAttribute("count", cart.getCount());
        return "cart";
    }

    /** Stepper: set absolute quantity for an item, return JSON (no redirect). */
    @PostMapping("/cart/set")
    @ResponseBody
    public Map<String, Object> setQuantity(@RequestParam("itemId") String itemId,
                                           @RequestParam("qty") int qty) {
        cart.updateItemQuantity(itemId, qty);   // qty<=0 removes (legacy behaviour)
        return Map.of(
                "itemId", itemId,
                "qty", cart.quantityOf(itemId),
                "count", cart.getCount());
    }

    /** Full-page add (fallback / no-JS): add one, redirect to cart. */
    @PostMapping("/cart/add")
    public String add(@RequestParam("itemId") String itemId) {
        cart.addItem(itemId);
        return "redirect:/cart";
    }

    @PostMapping("/cart/update")
    public String update(@RequestParam("itemId") String itemId,
                         @RequestParam("qty") int qty) {
        cart.updateItemQuantity(itemId, qty);
        return "redirect:/cart";
    }

    @PostMapping("/cart/delete")
    public String delete(@RequestParam("itemId") String itemId) {
        cart.deleteItem(itemId);
        return "redirect:/cart";
    }
}
