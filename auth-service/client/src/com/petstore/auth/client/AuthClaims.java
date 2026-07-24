package com.petstore.auth.client;

import java.util.List;

/**
 * The verified identity carried by a token — the subset of JWT claims services
 * care about. This is NOT stored anywhere: it's decoded from the token per
 * request. {@code userId} is the stable opaque id (subject); {@code roles} drives
 * authorization.
 */
public record AuthClaims(String userId, String username, List<String> roles) {
}
