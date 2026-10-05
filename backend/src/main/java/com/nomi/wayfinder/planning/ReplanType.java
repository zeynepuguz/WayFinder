package com.nomi.wayfinder.planning;

// Things a user can say during a trip that change the rest of the route
public enum ReplanType {
    // "Çok yorulduk" -> rest stop, less walking, drop outdoor sightseeing
    TIRED,
    // "Yağmur başladı" -> treat as rainy, move outdoor stops indoor
    WEATHER_CHANGED,
    // "Burayı çıkar"
    REMOVE_STOP,
    // "Burayı başka bir yerle değiştir"
    REPLACE_STOP,
    // "Tatlı da ekle"
    ADD_STOP,
    // "Biraz daha tarihi yerler ekle"
    ADD_INTEREST,
    // "Çok yürümek istemiyoruz"
    LESS_WALKING,
    // "Geciktik" -> the same places at later times from now (a place closed at its new time is swapped)
    RUNNING_LATE
}
