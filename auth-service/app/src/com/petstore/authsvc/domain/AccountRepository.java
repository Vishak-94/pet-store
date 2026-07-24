package com.petstore.authsvc.domain;

import org.springframework.data.jpa.repository.JpaRepository;

/** The single credential store for all users (customers + staff). */
public interface AccountRepository extends JpaRepository<AccountEntity, String> {
}
