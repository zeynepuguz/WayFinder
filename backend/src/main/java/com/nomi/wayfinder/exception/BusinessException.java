package com.nomi.wayfinder.exception;

import org.springframework.http.HttpStatus;

// A request that is well-formed but cannot be done (409 duplicate email, 400 invalid replan, 401 bad login, ...)
public class BusinessException extends RuntimeException {

    private final HttpStatus status;

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
