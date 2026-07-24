package com.petstore.customer.repository.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository backing the customer JPA adapter (domain data only —
 * credentials live in auth-service). */
interface CustomerJpaRepository extends JpaRepository<CustomerEntity, String> {
}
