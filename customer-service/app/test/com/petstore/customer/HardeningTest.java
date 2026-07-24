package com.petstore.customer;

import com.petstore.auth.client.AuthClient;
import com.petstore.customer.domain.Account;
import com.petstore.customer.service.CustomerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpStatus;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Hardening tests that remain customer-service concerns AFTER the auth split:
 * (2) @Valid + uniform error shape, (3) actuator, (4) correlation id, plus the
 * registration→duplicate mapping. Credential hashing + customerId generation now
 * live in auth-service and are pinned there; the AuthClient is mocked here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HardeningTest {

    @Autowired MockMvc mvc;
    @Autowired CustomerService customers;

    @MockBean AuthClient auth;

    // ---- #2 @Valid DTO validation + error shape (validation runs BEFORE auth call) ----

    @Test
    void register_blankUsername_returns400_withFieldErrors() throws Exception {
        mvc.perform(post("/register").contentType("application/json")
                .content("{\"userName\":\"\",\"password\":\"secret\",\"account\":{\"email\":\"a@x.com\"}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("validation_failed"))
            .andExpect(jsonPath("$.detail.userName").exists())
            .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void register_shortPassword_returns400() throws Exception {
        mvc.perform(post("/register").contentType("application/json")
                .content("{\"userName\":\"ok\",\"password\":\"ab\",\"account\":{\"email\":\"a@x.com\"}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail.password").exists());
    }

    @Test
    void register_badEmail_returns400() throws Exception {
        mvc.perform(post("/register").contentType("application/json")
                .content("{\"userName\":\"okuser\",\"password\":\"secret\",\"account\":{\"email\":\"not-an-email\"}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail['account.email']").exists());
    }

    @Test
    void register_duplicate_returns409_uniformShape() throws Exception {
        // auth-service reports a duplicate credential as 409 → mapped to DuplicateAccountException
        when(auth.provision(eq("dupuser"), any(), eq("USER")))
                .thenThrow(HttpClientErrorException.create(HttpStatus.CONFLICT, "Conflict", null, null, null));
        String body = "{\"userName\":\"dupuser\",\"password\":\"secret\","
                + "\"account\":{\"givenName\":\"Jane\",\"familyName\":\"Doe\",\"email\":\"d@x.com\","
                + "\"telephone\":\"212\",\"streetName1\":\"1 Main\",\"city\":\"NYC\",\"state\":\"NY\","
                + "\"zipCode\":\"10001\",\"country\":\"USA\"},"
                + "\"creditCard\":{\"cardNumber\":\"4111111111111111\",\"cardType\":\"VISA\",\"expiryDate\":\"12/2030\"}}";
        mvc.perform(post("/register").contentType("application/json").content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("duplicate_account"));
    }

    // ---- #3 Actuator ----

    @Test
    void actuator_health_isUp() throws Exception {
        mvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    // ---- #4 Correlation id ----

    @Test
    void response_carriesCorrelationIdHeader() throws Exception {
        mvc.perform(get("/actuator/health"))
            .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void inboundCorrelationId_isEchoed() throws Exception {
        mvc.perform(get("/actuator/health").header("X-Correlation-Id", "test-cid-123"))
            .andExpect(header().string("X-Correlation-Id", "test-cid-123"));
    }
}
