package com.nomi.wayfinder.exception;

public class PlaceNotFoundException extends ResourceNotFoundException {

    public PlaceNotFoundException(Long id) {
        super("Place not found with id: " + id);
    }
}
