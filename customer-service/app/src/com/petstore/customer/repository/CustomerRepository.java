package com.petstore.customer.repository;

import com.petstore.customer.domain.Customer;

import java.util.Optional;

/**
 * Persistence <b>port</b> for the customer aggregate (account + profile).
 */
public interface CustomerRepository {

    Optional<Customer> findByUserId(String userId);

    Customer save(Customer customer);
}
