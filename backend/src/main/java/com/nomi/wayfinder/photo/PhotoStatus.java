package com.nomi.wayfinder.photo;

public enum PhotoStatus {
    // Waiting for the AI check (or the AI service was unreachable; retried by PhotoJobs)
    PENDING,
    // Shown in the collages
    APPROVED,
    // Never shown; the row stays a while so the user sees why (RejectReason)
    REJECTED
}
