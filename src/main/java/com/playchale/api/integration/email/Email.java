package com.playchale.api.integration.email;

/**
 * One email to send.
 *
 * @param to      the address
 * @param subject the subject line
 * @param text    the message as plain text, for mail apps that don't show HTML (and spam filters,
 *                which trust an email more when it has one)
 * @param html    the same message in PlayChale's look (see {@link EmailLayout}), or null for text only
 */
public record Email(String to, String subject, String text, String html) {

}
