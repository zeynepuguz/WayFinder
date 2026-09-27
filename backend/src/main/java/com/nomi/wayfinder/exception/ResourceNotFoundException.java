package com.nomi.wayfinder.exception;

// Base class for every "X not found" error; mapped to 404 by GlobalExceptionHandler
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
