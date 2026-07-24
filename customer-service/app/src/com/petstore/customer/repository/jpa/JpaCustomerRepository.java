package com.petstore.customer.repository.jpa;

import com.petstore.customer.domain.Customer;
import com.petstore.customer.repository.CustomerRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** JPA adapter implementing the {@link CustomerRepository} port. */
@Repository
public class JpaCustomerRepository implements CustomerRepository {

    private final CustomerJpaRepository jpa;

    JpaCustomerRepository(CustomerJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Customer> findByUserId(String userId) {
        return jpa.findById(userId).map(CustomerEntity::toDomain);
    }

    @Override
    public Customer save(Customer customer) {
        return jpa.save(CustomerEntity.fromDomain(customer)).toDomain();
    }
}
