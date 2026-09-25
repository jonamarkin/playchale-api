package com.playchale.api.auth.web.dto;

import jakarta.validation.constraints.NotBlank;

/** {"phone": "...", "code": "123456"} */
public record SignInRequest(@NotBlank(message = "Enter your mobile number.") String phone,
		@NotBlank(message = "Enter the code from the SMS.") String code) {
}
