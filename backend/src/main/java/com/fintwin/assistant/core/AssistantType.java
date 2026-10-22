package com.fintwin.assistant.core;

import com.fintwin.assistant.user.Role;

/** The two assistants and the role each one serves. */
public enum AssistantType {
    SCENARIO(Role.RISK_ANALYST), COACH(Role.CUSTOMER);

    private final Role role;

    AssistantType(Role role) {
        this.role = role;
    }

    public Role role() {
        return role;
    }
}
