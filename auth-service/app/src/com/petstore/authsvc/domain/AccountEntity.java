package com.petstore.authsvc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A credential record — the ONE place any user's login lives (customers + staff).
 * {@code userId} is the stable opaque id that other services reference; {@code role}
 * distinguishes the realm (USER = customer, SUPPLIER/ADMIN = staff). This service
 * stores ONLY authentication data — customer profile/cards live in customer-service.
 */
@Entity
@Table(name = "account")
public class AccountEntity {

    @Id
    @Column(name = "user_name")
    private String userName;

    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "user_id", nullable = false, unique = true)
    private String userId;

    @Column(name = "role", nullable = false)
    private String role;

    protected AccountEntity() {
    }

    public AccountEntity(String userName, String password, String userId, String role) {
        this.userName = userName;
        this.password = password;
        this.userId = userId;
        this.role = role;
    }

    public String getUserName() { return userName; }
    public String getPassword() { return password; }
    public String getUserId() { return userId; }
    public String getRole() { return role; }
}
