package com.fintwin.assistant.user;

/**
 * The authenticated caller. Assistant tools take the identity (and the customer whose data they may read) only from
 * here, never from model-generated arguments.
 *
 * @param customerId the customer the user is, for {@link Role#CUSTOMER}; null otherwise
 */
public record CurrentUser(String userId, Role role, Long customerId) {

    public CurrentUser {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        if (role == Role.CUSTOMER && customerId == null) {
            throw new IllegalArgumentException("a CUSTOMER user needs a customer id");
        }
    }

    public boolean has(Role required) {
        return role == required;
    }
}
