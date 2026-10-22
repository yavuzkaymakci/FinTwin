package com.fintwin.twin.risk.client;

/** The ML service could not be reached, failed, or its circuit breaker is open. */
public class MlServiceUnavailableException extends RuntimeException {

    public MlServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
