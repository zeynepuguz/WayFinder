package com.nomi.wayfinder.photo;

// A file refused right at the upload (unsupported type, too small); GlobalExceptionHandler answers 400
public class PhotoRejectedException extends RuntimeException {

    private final RejectReason reason;

    public PhotoRejectedException(RejectReason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
