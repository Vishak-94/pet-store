package com.petstore.customer;

import com.petstore.auth.client.AuthClient;
import com.petstore.customer.domain.Account;
import com.petstore.customer.domain.Customer;
import com.petstore.customer.service.CustomerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Smoke test for customer-service after the auth split. It owns customer DOMAIN
 * data only; credentials are provisioned in auth-service (mocked here). Registration
 * stores the customer aggregate keyed by the userId auth-service returns.
 */
@SpringBootTest
class CustomerServiceTest {

    @Autowired CustomerService customers;

    @MockBean AuthClient auth;   // provisioning + login live in auth-service

    @Test
    void register_provisionsCredential_andStoresProfile() {
        when(auth.provision(eq("newuser"), any(), eq("USER"))).thenReturn("uid-999");
        Account a = new Account("Jane", "Doe", "jane@x.com", "212",
                "1 Main", null, "NYC", "NY", "10001", "USA");

        Customer c = customers.register("newuser", "pw", a);

        assertThat(c.getUserId()).isEqualTo("uid-999");   // keyed by auth-service userId
        assertThat(customers.findByUserId("uid-999")).isPresent()
                .get().satisfies(x -> assertThat(x.getAccount().getEmail()).isEqualTo("jane@x.com"));
    }
}
