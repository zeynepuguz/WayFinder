package com.nomi.wayfinder.exception;

public class PlaceNotFoundException extends RuntimeException {

    public PlaceNotFoundException(Long id) {
        super("Place not found with id: " + id);
    }
}