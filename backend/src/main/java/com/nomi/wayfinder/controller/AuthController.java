package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.dto.AuthDtos.AuthResponse;
import com.nomi.wayfinder.dto.AuthDtos.LoginRequest;
import com.nomi.wayfinder.dto.AuthDtos.RegisterRequest;
import com.nomi.wayfinder.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return userService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return userService.login(request);
    }
}
