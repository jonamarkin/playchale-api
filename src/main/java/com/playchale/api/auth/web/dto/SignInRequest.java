package com.playchale.api.auth.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {"phone": "...", "code": "123456"} or {"email": "...", "code": "123456"}. {@code country} is the web
 * app's guess at where someone is, used when signing in by email creates their account.
 */
public record SignInRequest(String phone, String email, @NotBlank(message = "Enter the code we sent you.") String code, String country) {
}
