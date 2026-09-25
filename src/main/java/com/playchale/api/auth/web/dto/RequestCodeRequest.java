package com.playchale.api.auth.web.dto;

import jakarta.validation.constraints.NotBlank;

/** {"phone": "024 123 4567"}: the number as the person typed it. */
public record RequestCodeRequest(@NotBlank(message = "Enter your mobile number.") String phone) {
}
