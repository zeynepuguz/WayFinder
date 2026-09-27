package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.dto.AuthDtos.AuthResponse;
import com.nomi.wayfinder.dto.AuthDtos.ForgotPasswordRequest;
import com.nomi.wayfinder.dto.AuthDtos.LoginRequest;
import com.nomi.wayfinder.dto.AuthDtos.RegisterRequest;
import com.nomi.wayfinder.dto.AuthDtos.ResetPasswordRequest;
import com.nomi.wayfinder.service.PasswordResetService;
import com.nomi.wayfinder.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserService userService;
    private final PasswordResetService passwordResetService;

    public AuthController(UserService userService, PasswordResetService passwordResetService) {
        this.userService = userService;
        this.passwordResetService = passwordResetService;
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

    // Always 202, whether or not the e-mail has an account
    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestCode(request.email());
    }

    // Sets the new password and signs the user in
    @PostMapping("/password/reset")
    public AuthResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        return passwordResetService.resetPassword(request.email(), request.code(), request.newPassword());
    }
}
