package com.petstore.customer.service;

import com.petstore.auth.client.AuthClient;
import com.petstore.customer.domain.Account;
import com.petstore.customer.domain.CreditCard;
import com.petstore.customer.domain.Customer;
import com.petstore.customer.domain.Profile;
import com.petstore.customer.repository.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

import java.util.Optional;

/**
 * Customer registration + profile management. customer-service owns the customer
 * DOMAIN (account/profile/card) but NOT credentials — those live in auth-service.
 *
 * <p>Registration provisions the credential in auth-service (role USER) and stores
 * the customer aggregate locally, keyed by the userId auth-service returns. A
 * duplicate user name (409 from auth-service) surfaces as
 * {@link DuplicateAccountException}.
 */
@Service
public class CustomerService {

    /** Role provisioned for every self-registered customer (staff roles are provisioned elsewhere). */
    private static final String CUSTOMER_ROLE = "USER";

    private final AuthClient auth;
    private final CustomerRepository customers;

    public CustomerService(AuthClient auth, CustomerRepository customers) {
        this.auth = auth;
        this.customers = customers;
    }

    /**
     * Registers a new customer: provision the credential in auth-service (USER),
     * then store the customer aggregate (account + default profile + optional card)
     * keyed by the returned userId. Throws {@link DuplicateAccountException} if the
     * user name is taken.
     */
    @Transactional
    public Customer register(String userName, String password, Account account, CreditCard creditCard) {
        String userId;
        try {
            userId = auth.provision(userName, password, CUSTOMER_ROLE);
        } catch (HttpClientErrorException.Conflict e) {
            throw new DuplicateAccountException(userName);
        }
        Customer customer = new Customer(userId, account, Profile.defaults(), creditCard);
        return customers.save(customer);
    }

    /** Convenience overload: register without a card. */
    @Transactional
    public Customer register(String userName, String password, Account account) {
        return register(userName, password, account, null);
    }

    @Transactional(readOnly = true)
    public Optional<Customer> findByUserId(String userId) {
        return customers.findByUserId(userId);
    }

    /** Update the contact/billing account of an existing customer. */
    @Transactional
    public Customer updateAccount(String userId, Account account) {
        Customer existing = require(userId);
        return customers.save(new Customer(userId, account, existing.getProfile(), existing.getCreditCard()));
    }

    /** Update profile preferences of an existing customer. */
    @Transactional
    public Customer updateProfile(String userId, Profile profile) {
        Customer existing = require(userId);
        return customers.save(new Customer(userId, existing.getAccount(), profile, existing.getCreditCard()));
    }

    /** Update (or set) the customer's credit card. */
    @Transactional
    public Customer updateCreditCard(String userId, CreditCard creditCard) {
        Customer existing = require(userId);
        return customers.save(new Customer(userId, existing.getAccount(), existing.getProfile(), creditCard));
    }

    private Customer require(String userId) {
        return customers.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("No such customer: " + userId));
    }
}
