package com.playchale.api.auth.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** {"demoCode": "123456"} on a laptop; {} everywhere else. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CodeResponse(String demoCode) {
}
