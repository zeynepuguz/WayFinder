package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.dto.AuthDtos.AuthResponse;
import com.nomi.wayfinder.dto.AuthDtos.CodeSentResponse;
import com.nomi.wayfinder.dto.AuthDtos.ForgotPasswordRequest;
import com.nomi.wayfinder.dto.AuthDtos.LoginRequest;
import com.nomi.wayfinder.dto.AuthDtos.RefreshRequest;
import com.nomi.wayfinder.dto.AuthDtos.RegisterRequest;
import com.nomi.wayfinder.dto.AuthDtos.ResendCodeRequest;
import com.nomi.wayfinder.dto.AuthDtos.ResetPasswordRequest;
import com.nomi.wayfinder.dto.AuthDtos.VerifyCodeRequest;
import com.nomi.wayfinder.service.PasswordResetService;
import com.nomi.wayfinder.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Sign-up and sign-in take two steps: the form (name / password), then the 6-digit code e-mailed to the address.
 * The app keeps the session with POST /refresh (7 days after the last use).
 */
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
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CodeSentResponse register(@Valid @RequestBody RegisterRequest request) {
        return userService.register(request);
    }

    @PostMapping("/register/verify")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse verifyRegister(@Valid @RequestBody VerifyCodeRequest request,
                                       @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return userService.verifySignUp(request, userAgent);
    }

    @PostMapping("/login")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CodeSentResponse login(@Valid @RequestBody LoginRequest request) {
        return userService.login(request);
    }

    @PostMapping("/login/verify")
    public AuthResponse verifyLogin(@Valid @RequestBody VerifyCodeRequest request,
                                    @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return userService.verifySignIn(request, userAgent);
    }

    // Always 202: a new code only while a sign-up / sign-in is running
    @PostMapping("/code/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resendCode(@Valid @RequestBody ResendCodeRequest request) {
        userService.resendCode(request);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return userService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        userService.logout(request.refreshToken());
    }

    // Always 202, whether or not the e-mail has an account
    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestCode(request.email());
    }

    // Sets the new password, signs out every other device and signs the user in here
    @PostMapping("/password/reset")
    public AuthResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request,
                                      @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return passwordResetService.resetPassword(request.email(), request.code(), request.newPassword(), userAgent);
    }
}
