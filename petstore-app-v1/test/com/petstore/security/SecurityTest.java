package com.petstore.security;

import com.petstore.auth.client.AuthClient;
import com.petstore.customer.client.CustomerServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.logout;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security tests for the monolith after auth was delegated to auth-service (the
 * central IdP). The AuthClient is mocked so login is verified without the real
 * service running (the monolith holds no credentials).
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    AuthClient authClient;                   // auth provider delegates login here

    @MockBean
    CustomerServiceClient customerClient;    // storefront profile fetch depends on this

    @Test
    void publicPages_areOpen() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/search?keyword=fish")).andExpect(status().isOk());
        mvc.perform(get("/login")).andExpect(status().isOk());
    }

    @Test
    void checkout_requiresLogin_redirects() throws Exception {
        mvc.perform(get("/checkout")).andExpect(status().is3xxRedirection());   // -> /login
    }

    @Test
    void formLogin_delegatesToAuthService_success() throws Exception {
        when(authClient.login("j2ee", "j2ee"))
                .thenReturn(Optional.of(new AuthClient.LoginResult("jwt-token", "uid-1", List.of("USER"))));
        mvc.perform(formLogin().user("j2ee").password("j2ee"))
                .andExpect(authenticated().withUsername("j2ee"));
    }

    @Test
    void formLogin_badCredentials_fails() throws Exception {
        when(authClient.login("j2ee", "WRONG")).thenReturn(Optional.empty());
        mvc.perform(formLogin().user("j2ee").password("WRONG"))
                .andExpect(unauthenticated());
    }

    @Test
    void logout_endsSession() throws Exception {
        mvc.perform(logout()).andExpect(unauthenticated());
    }
}
