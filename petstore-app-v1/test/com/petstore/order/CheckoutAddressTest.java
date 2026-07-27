package com.petstore.order;

import com.petstore.cart.CartDtos.CartItemView;
import com.petstore.cart.CartDtos.CartView;
import com.petstore.cart.CartOperations;
import com.petstore.cart.service.CartService;
import com.petstore.cart.web.CartIdFilter;
import com.petstore.opc.client.OrderDtos.CheckoutRequest;
import com.petstore.opc.client.OrderDtos.CheckoutResponse;
import com.petstore.opc.client.OrderProcessingClient;
import com.petstore.order.service.OrderIdGenerator;
import com.petstore.order.service.OrderService;
import com.petstore.order.web.ContactInfoForm;
import com.petstore.order.web.MissingFormDataException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ship-to / bill-to address collection + validation restored at checkout (H7),
 * mirroring the legacy {@code OrderHTMLAction.extractContactInfo}. Verifies the
 * exact required set (family/given name, street1, city, state, zip, telephone for
 * BOTH sides; street2/country/email optional) and that the contacts are forwarded
 * on the OPC intake request (CheckoutRequest.shipTo/billTo).
 */
class CheckoutAddressTest {

    private static final String CART_ID = "addr-test-cart";
    private static final String BEARER = "jwt-token";

    private CartOperations cartOps;
    private OrderProcessingClient orderProcessing;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        cartOps = mock(CartOperations.class);
        orderProcessing = mock(OrderProcessingClient.class);
        orderService = new OrderService(new CartService(cartOps), orderProcessing, new OrderIdGenerator());
        when(orderProcessing.checkout(any(CheckoutRequest.class), any())).thenAnswer(inv -> {
            CheckoutRequest req = inv.getArgument(0);
            return new CheckoutResponse(req.orderId(), "PENDING", req.totalPrice());
        });
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CartIdFilter.REQUEST_ATTR, CART_ID);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** Fully-populated valid contact (street2/country/email left null → still valid). */
    private static ContactInfoForm valid() {
        ContactInfoForm f = new ContactInfoForm();
        f.setFamilyName("Doe");
        f.setGivenName("Jane");
        f.setStreetName1("1 Main St");
        f.setCity("Palo Alto");
        f.setState("CA");
        f.setZipCode("94301");
        f.setTelephone("555-1234");
        return f;
    }

    @Test
    void requireValid_passes_whenAllRequiredPresent_optionalsBlank() {
        assertThatCode(() -> ContactInfoForm.requireValid(valid(), valid()))
                .doesNotThrowAnyException();
    }

    @Test
    void requireValid_reportsEachMissingRequiredField_bothSides() {
        ContactInfoForm blank = new ContactInfoForm();
        assertThatThrownBy(() -> ContactInfoForm.requireValid(blank, blank))
                .isInstanceOf(MissingFormDataException.class)
                .satisfies(ex -> {
                    List<String> missing = ((MissingFormDataException) ex).getMissingFields();
                    // 7 required fields per side, both ship-to and bill-to
                    assertThat(missing).hasSize(14);
                    assertThat(missing).contains("Ship-To Last Name", "Ship-To First Name",
                            "Ship-To Street Address", "Ship-To City", "Ship-To State or Province",
                            "Ship-To Postal Code", "Ship-To Telephone Number",
                            "Bill-To Last Name", "Bill-To Telephone Number");
                });
    }

    @Test
    void requireValid_street2_isOptional_notInMissingSet() {
        ContactInfoForm f = valid();
        f.setStreetName2("");   // blank optional must NOT trip validation
        assertThatCode(() -> ContactInfoForm.requireValid(f, valid()))
                .doesNotThrowAnyException();
    }

    @Test
    void requireValid_whitespaceOnly_treatedAsBlank() {
        ContactInfoForm f = valid();
        f.setCity("   ");
        assertThatThrownBy(() -> ContactInfoForm.requireValid(f, valid()))
                .isInstanceOf(MissingFormDataException.class)
                .satisfies(ex -> assertThat(((MissingFormDataException) ex).getMissingFields())
                        .containsExactly("Ship-To City"));
    }

    @Test
    void checkout_forwardsShipToAndBillTo_onIntakeRequest() {
        when(cartOps.view(CART_ID)).thenReturn(new CartView(List.of(
                new CartItemView("EST-1", "FI-SW-01", null, "Angelfish", "male", 1, 16.50)), 16.50, 1));

        orderService.checkout(BEARER, "bob", "bob@x.com",
                valid().toContactInfo(), valid().toContactInfo());

        ArgumentCaptor<CheckoutRequest> req = ArgumentCaptor.forClass(CheckoutRequest.class);
        verify(orderProcessing).checkout(req.capture(), any());
        CheckoutRequest sent = req.getValue();
        assertThat(sent.shipTo()).isNotNull();
        assertThat(sent.shipTo().familyName()).isEqualTo("Doe");
        assertThat(sent.shipTo().streetName2()).isNull();     // blank optional folded to null
        assertThat(sent.billTo()).isNotNull();
        assertThat(sent.billTo().telephone()).isEqualTo("555-1234");
    }
}
