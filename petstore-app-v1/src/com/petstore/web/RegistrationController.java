package com.petstore.web;

import com.petstore.customer.client.CustomerServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * HTML account registration — the sign-up form and its submission. Split out of
 * {@link StorefrontController} (SRP: that class now owns only the checkout flow) since
 * registration has its own concern, its own only-dependency ({@link CustomerServiceClient}),
 * and its own return-to-origin redirect rules. Holds NO customer business logic — it
 * DELEGATES account creation to the customer-service microservice.
 */
@Controller
public class RegistrationController {

    private static final Logger log = LoggerFactory.getLogger(RegistrationController.class);

    /** Registration route + the page it renders on. */
    private static final String REGISTER_PATH = "/register-form";
    private static final String VIEW_REGISTER = "register";
    /** Redirect to the login page after a successful sign-up (no return-to-origin). */
    private static final String REDIRECT_LOGIN_REGISTERED = "redirect:/login?registered";
    /** Model attribute keys consumed by the register template. */
    private static final String ATTR_RETURN_URL = "returnUrl";
    private static final String ATTR_ERROR = "error";
    /** User-facing registration error messages. */
    private static final String MSG_USERNAME_TAKEN = "That username is already taken.";
    private static final String MSG_INVALID_DETAILS = "Invalid registration details.";
    private static final String MSG_SERVICE_UNAVAILABLE = "Registration service unavailable, please try again.";

    private final CustomerServiceClient customerClient;

    public RegistrationController(CustomerServiceClient customerClient) {
        this.customerClient = customerClient;
    }

    /**
     * Show the sign-up form, capturing the originating screen so we can return there afterwards.
     *
     * <pre>{@code
     * GET /register-form?returnUrl=/cart        // ?returnUrl= wins; else the Referer is used
     * GET /register-form   (Referer: http://host/cart)
     *
     * 200 OK  renders register.html
     *   model: returnUrl = "/cart"   // reduced to a same-app path; null if not usable
     * }</pre>
     */
    @GetMapping(REGISTER_PATH)
    public String registerForm(@RequestParam(required = false) String returnUrl,
                               @RequestHeader(value = HttpHeaders.REFERER, required = false) String referer,
                               Model model) {
        // Capture the originating screen so we can return there after account creation
        // (legacy returned the user to the pre-signon URL). ?returnUrl= wins over Referer;
        // the Referer is reduced to a local path so the round-trip stays same-app.
        model.addAttribute(ATTR_RETURN_URL, returnUrl != null ? returnUrl : localPath(referer));
        return VIEW_REGISTER;
    }

    /** Reduces an (absolute) Referer to a same-app path+query, or null if not usable. */
    private String localPath(String referer) {
        if (referer == null) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(referer);
            String path = uri.getRawPath();
            if (path == null || path.isBlank() || path.equals(REGISTER_PATH)) {
                return null;   // don't loop back to the register page itself
            }
            return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Submit the sign-up form: assemble the account (+ optional card) and delegate creation to
     * customer-service. On success redirects to the originating screen (same-app only, open-redirect
     * guarded) or the login page; a duplicate username → 409, invalid details → 400, or an
     * unavailable service each re-render the form with the matching message.
     *
     * <pre>{@code
     * POST /register-form
     *   form: userName=jdoe&password=secret
     *         &givenName=Jane&familyName=Doe&email=jane@x.com
     *         &street1=1+Main+St&city=Palo+Alto&state=CA&zipCode=94301&country=US
     *         &cardNumber=4111...&cardType=Visa&cardExpiry=12/29   // card fields optional
     *         &returnUrl=/cart                                     // optional
     *
     * 302 Found  Location: /cart              // returnUrl (same-app only), else:
     * 302 Found  Location: /login?registered  // default landing after sign-up
     * }</pre>
     *
     * <p>Edge cases re-render register.html with an {@code error}: duplicate username (409) →
     * "username taken"; invalid details (400) → "invalid details"; customer-service down →
     * "service unavailable". A non-local {@code returnUrl} (e.g. {@code //evil.com}) is ignored
     * (open-redirect guard) and falls back to the login page.
     */
    @PostMapping(REGISTER_PATH)
    public String register(@RequestParam String userName,
                           @RequestParam String password,
                           @RequestParam(required = false) String givenName,
                           @RequestParam(required = false) String familyName,
                           @RequestParam(required = false) String email,
                           @RequestParam(required = false) String street1,
                           @RequestParam(required = false) String city,
                           @RequestParam(required = false) String state,
                           @RequestParam(required = false) String zipCode,
                           @RequestParam(required = false) String country,
                           @RequestParam(required = false) String cardNumber,
                           @RequestParam(required = false) String cardType,
                           @RequestParam(required = false) String cardExpiry,
                           @RequestParam(required = false) String returnUrl,
                           Model model) {
        var account = new com.petstore.customer.client.CustomerDtos.AccountDto(
                givenName, familyName, email, null, street1, null, city, state, zipCode, country);
        var card = (cardNumber == null || cardNumber.isBlank()) ? null
                : new com.petstore.customer.client.CustomerDtos.CardDto(cardNumber, cardType, cardExpiry);
        var request = new com.petstore.customer.client.CustomerDtos.RegisterRequest(
                userName, password, account, card);
        try {
            customerClient.register(request);
            log.info("Registered {} via customer-service", userName);
        } catch (org.springframework.web.client.HttpClientErrorException.Conflict e) {
            model.addAttribute(ATTR_ERROR, MSG_USERNAME_TAKEN);
            model.addAttribute(ATTR_RETURN_URL, returnUrl);
            return VIEW_REGISTER;
        } catch (org.springframework.web.client.HttpClientErrorException.BadRequest e) {
            model.addAttribute(ATTR_ERROR, MSG_INVALID_DETAILS);
            model.addAttribute(ATTR_RETURN_URL, returnUrl);
            return VIEW_REGISTER;
        } catch (org.springframework.web.client.RestClientException e) {
            model.addAttribute(ATTR_ERROR, MSG_SERVICE_UNAVAILABLE);
            model.addAttribute(ATTR_RETURN_URL, returnUrl);
            return VIEW_REGISTER;
        }
        // Return to the originating screen if we captured one (legacy behaviour),
        // else fall back to the login page.
        if (returnUrl != null && isLocalUrl(returnUrl)) {
            return "redirect:" + returnUrl;
        }
        return REDIRECT_LOGIN_REGISTERED;
    }

    /** Only allow same-app relative redirects (guards against open-redirect). */
    private static boolean isLocalUrl(String url) {
        return url.startsWith("/") && !url.startsWith("//");
    }
}
