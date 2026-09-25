package com.playchale.api.auth.web.dto;

import jakarta.validation.constraints.NotBlank;

/** {"phone": "...", "code": "123456"} or {"email": "...", "code": "123456"} */
public record SignInRequest(String phone, String email, @NotBlank(message = "Enter the code we sent you.") String code) {
}
