package com.fintwin.twin.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class TwinNotFoundException extends RuntimeException {

    public TwinNotFoundException(long customerId) {
        super("No twin for customer " + customerId);
    }
}
