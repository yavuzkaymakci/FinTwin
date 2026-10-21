package com.fintwin.events;

/** A payload that cannot be turned into a valid event; never retried, goes to the dead letter topic. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
