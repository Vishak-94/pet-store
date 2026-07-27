package com.petstore.security;

import com.petstore.auth.client.AuthClient;
import com.petstore.catalog.client.CatalogDtos.CategoryPage;
import com.petstore.catalog.client.CatalogDtos.ItemPage;
import com.petstore.catalog.client.CatalogServiceClient;
import com.petstore.customer.client.CustomerServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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

    @MockBean
    CatalogServiceClient catalogClient;      // browse pages ("/", "/search") fetch catalog here

    @Test
    void publicPages_areOpen() throws Exception {
        // The public browse pages call catalog-service over HTTP; stub it so this security slice
        // stays hermetic (no running catalog-service). We only assert the pages are OPEN (200),
        // not their catalog content — an empty page is fine for that.
        when(catalogClient.getCategories(anyInt(), anyInt(), anyString()))
                .thenReturn(new CategoryPage(List.of(), 0, false));
        when(catalogClient.searchItems(anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(new ItemPage(List.of(), 0, false));

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
    void formLogin_retainsJwtAsCredential_notErased() throws Exception {
        // Regression: ProviderManager erases credentials by default, which would wipe the JWT
        // that CustomerServiceAuthProvider stashes as the credential. The storefront forwards
        // that JWT as a Bearer token to OPC at checkout — if it were erased, getCredentials()
        // returns null → "Bearer null" → OPC 401 → checkout fails. SecurityConfig disables
        // erasure; this test pins that the stored Authentication keeps the JWT.
        when(authClient.login("j2ee", "j2ee"))
                .thenReturn(Optional.of(new AuthClient.LoginResult("jwt-token", "uid-1", List.of("USER"))));

        MvcResult result = mvc.perform(formLogin().user("j2ee").password("j2ee"))
                .andExpect(authenticated().withUsername("j2ee"))
                .andReturn();

        SecurityContext context = (SecurityContext) result.getRequest().getSession()
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        Authentication stored = context.getAuthentication();
        assertThat(stored.getCredentials()).isEqualTo("jwt-token");        // NOT erased to null
        assertThat(stored.getDetails()).isEqualTo("uid-1");                // stable userId survives too
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
