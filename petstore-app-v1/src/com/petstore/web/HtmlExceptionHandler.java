package com.petstore.web;

import com.petstore.order.service.EmptyCartException;
import com.petstore.order.web.MissingFormDataException;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * HTML error translation for the browser-facing {@code @Controller} pages (SRP: the
 * mirror of {@link RestExceptionHandler}, but rendering a Thymeleaf {@code error} view
 * rather than a JSON body). Without this, an exception on an HTML page fell through to
 * either the generic whitelabel page or — worse, before the two advices were scoped —
 * a raw JSON payload rendered inside the browser.
 *
 * <p>Scoped to {@code @Controller}s (annotations = Controller.class); {@code @RestController}
 * endpoints keep their JSON error contract via {@link RestExceptionHandler}.
 */
@ControllerAdvice(annotations = org.springframework.stereotype.Controller.class)
public class HtmlExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(HtmlExceptionHandler.class);

    /** Thymeleaf error view + the model keys it reads. */
    private static final String VIEW_ERROR = "error";
    private static final String ATTR_STATUS = "status";
    private static final String ATTR_ERROR = "error";
    private static final String ATTR_MESSAGE = "message";

    /** Error codes rendered on the error page (parallel to {@link RestExceptionHandler}). */
    private static final String ERROR_NOT_FOUND = "not_found";
    private static final String ERROR_ILLEGAL_STATE = "illegal_state";
    private static final String ERROR_CART_EMPTY = "cart_empty";
    private static final String ERROR_MISSING_FORM_DATA = "missing_form_data";
    private static final String ERROR_SERVER = "server_error";
    private static final String MSG_SERVER_ERROR = "Something went wrong. Please try again.";

    /** Unknown item/category/customer or bad argument → a 404 page. */
    @ExceptionHandler(IllegalArgumentException.class)
    public String handleNotFound(IllegalArgumentException ex, Model model, HttpServletResponse response) {
        response.setStatus(HttpStatus.NOT_FOUND.value());
        return render(model, HttpStatus.NOT_FOUND, ERROR_NOT_FOUND, ex.getMessage());
    }

    /** Illegal workflow transition on an HTML flow → a 409 page. */
    @ExceptionHandler(IllegalStateException.class)
    public String handleConflict(IllegalStateException ex, Model model, HttpServletResponse response) {
        response.setStatus(HttpStatus.CONFLICT.value());
        return render(model, HttpStatus.CONFLICT, ERROR_ILLEGAL_STATE, ex.getMessage());
    }

    /** Checkout with an empty cart from an HTML flow → a 400 page. */
    @ExceptionHandler(EmptyCartException.class)
    public String handleEmptyCart(EmptyCartException ex, Model model, HttpServletResponse response) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        return render(model, HttpStatus.BAD_REQUEST, ERROR_CART_EMPTY, ex.getMessage());
    }

    /** Missing required address fields at HTML checkout → a 400 page. */
    @ExceptionHandler(MissingFormDataException.class)
    public String handleMissingFormData(MissingFormDataException ex, Model model, HttpServletResponse response) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        return render(model, HttpStatus.BAD_REQUEST, ERROR_MISSING_FORM_DATA, ex.getMessage());
    }

    /** Anything unmapped → a 500 page, with the cause logged (never shown to the shopper). */
    @ExceptionHandler(Exception.class)
    public String handleUnexpected(Exception ex, Model model, HttpServletResponse response) {
        log.error("Unhandled error rendering an HTML page", ex);
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        return render(model, HttpStatus.INTERNAL_SERVER_ERROR, ERROR_SERVER, MSG_SERVER_ERROR);
    }

    private static String render(Model model, HttpStatus status, String error, String message) {
        model.addAttribute(ATTR_STATUS, status.value());
        model.addAttribute(ATTR_ERROR, error);
        model.addAttribute(ATTR_MESSAGE, message == null ? "" : message);
        return VIEW_ERROR;
    }
}
