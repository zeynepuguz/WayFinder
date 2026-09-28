package com.nomi.wayfinder.photo;

// Where the position that proves "taken here" came from
public enum ProofSource {
    // GPS written into the photo by the camera
    EXIF,
    // The phone's position sent with the upload (only precise enough ones count)
    DEVICE
}
