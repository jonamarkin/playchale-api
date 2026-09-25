package com.playchale.api.auth.web.dto;

/** {"phone": "024 123 4567"} or {"email": "name@example.com"}: as the person typed it. */
public record RequestCodeRequest(String phone, String email) {
}
