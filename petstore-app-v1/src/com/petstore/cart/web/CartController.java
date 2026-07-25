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

    /** Cart page view name + redirect back to it after a full-page mutation. */
    private static final String VIEW_CART = "cart";
    private static final String REDIRECT_CART = "redirect:/cart";

    /** Request params bound from the cart forms / stepper. */
    private static final String PARAM_ITEM_ID = "itemId";
    private static final String PARAM_QTY = "qty";

    /** Model + JSON response keys consumed by the cart page and the stepper. */
    private static final String KEY_ITEMS = "items";
    private static final String KEY_SUBTOTAL = "subtotal";
    private static final String KEY_ITEM_ID = "itemId";
    private static final String KEY_QTY = "qty";
    private static final String KEY_COUNT = "count";

    private final CartService cart;

    public CartController(CartService cart) {
        this.cart = cart;
    }

    @GetMapping("/cart")
    public String view(Model model) {
        model.addAttribute(KEY_ITEMS, cart.getItems());
        model.addAttribute(KEY_SUBTOTAL, cart.getSubTotal());
        model.addAttribute(KEY_COUNT, cart.getCount());
        return VIEW_CART;
    }

    /** Stepper: set absolute quantity for an item, return JSON (no redirect). */
    @PostMapping("/cart/set")
    @ResponseBody
    public Map<String, Object> setQuantity(@RequestParam(PARAM_ITEM_ID) String itemId,
                                           @RequestParam(PARAM_QTY) int qty) {
        cart.updateItemQuantity(itemId, qty);   // qty<=0 removes (legacy behaviour)
        return Map.of(
                KEY_ITEM_ID, itemId,
                KEY_QTY, cart.quantityOf(itemId),
                KEY_COUNT, cart.getCount());
    }

    /** Full-page add (fallback / no-JS): add one, redirect to cart. */
    @PostMapping("/cart/add")
    public String add(@RequestParam(PARAM_ITEM_ID) String itemId) {
        cart.addItem(itemId);
        return REDIRECT_CART;
    }

    @PostMapping("/cart/update")
    public String update(@RequestParam(PARAM_ITEM_ID) String itemId,
                         @RequestParam(PARAM_QTY) int qty) {
        cart.updateItemQuantity(itemId, qty);
        return REDIRECT_CART;
    }

    @PostMapping("/cart/delete")
    public String delete(@RequestParam(PARAM_ITEM_ID) String itemId) {
        cart.deleteItem(itemId);
        return REDIRECT_CART;
    }
}
