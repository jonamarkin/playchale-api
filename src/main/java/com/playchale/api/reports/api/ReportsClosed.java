package com.playchale.api.reports.api;

import java.util.List;
import java.util.UUID;

/**
 * Staff decided on the reports about one thing. Each reporter is thanked, without the outcome: what
 * happens to someone else's game or account isn't theirs to know.
 *
 * @param subjectType "game", "message", "user" or "venue"
 */
public record ReportsClosed(String subjectType, UUID subjectId, List<UUID> reporterIds) {
}
