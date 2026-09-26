package com.playchale.api.venues.internal.service;

/**
 * An in-person booking's details, from the manager. When booking, a null price means the pitch's
 * rate; when editing, a null field stays as it was, and a blank {@code paidVia} means still owed.
 *
 * @param price   in the venue's currency, minor units
 * @param paidVia "cash", "momo", or blank for still owed
 */
public record InPersonDetails(String customerName, String customerPhone, Long price, String paidVia, String note) {
}
