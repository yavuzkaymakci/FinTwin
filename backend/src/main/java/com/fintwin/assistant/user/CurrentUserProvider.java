package com.fintwin.assistant.user;

/** Resolves the caller of the current request. Step 7 replaces the header-based implementation with JWT. */
public interface CurrentUserProvider {

    /**
     * @throws UnauthenticatedException if the request carries no (valid) identity
     */
    CurrentUser current();
}
