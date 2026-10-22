package com.fintwin.assistant.user;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Development-only identity from request headers: {@code X-Demo-User}, {@code X-Demo-Role} (RISK_ANALYST,
 * PRODUCT_MANAGER, CUSTOMER) and {@code X-Demo-Customer-Id} (required for CUSTOMER). Anyone can send these headers,
 * so this is not security; step 7 replaces it with JWT authentication behind the same {@link CurrentUserProvider}.
 */
@Component
public class DemoHeaderCurrentUserProvider implements CurrentUserProvider {

    public static final String USER = "X-Demo-User";
    public static final String ROLE = "X-Demo-Role";
    public static final String CUSTOMER_ID = "X-Demo-Customer-Id";

    @Override
    public CurrentUser current() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw new UnauthenticatedException("No request bound to the current thread");
        }
        HttpServletRequest request = attributes.getRequest();
        String user = request.getHeader(USER);
        String role = request.getHeader(ROLE);
        String customer = request.getHeader(CUSTOMER_ID);
        if (user == null || user.isBlank() || role == null) {
            throw new UnauthenticatedException("Missing " + USER + " / " + ROLE + " headers");
        }
        try {
            return new CurrentUser(user.trim(), Role.valueOf(role.trim()),
                    customer == null || customer.isBlank() ? null : Long.valueOf(customer.trim()));
        } catch (IllegalArgumentException e) {
            throw new UnauthenticatedException("Invalid identity headers: " + e.getMessage());
        }
    }
}
