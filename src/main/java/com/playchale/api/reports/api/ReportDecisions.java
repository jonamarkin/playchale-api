package com.playchale.api.reports.api;

import java.util.UUID;

/**
 * Staff deciding on reports, from the admin desk: every open report about one thing at once, since
 * one decision answers them all. Whoever reported hears it was looked at, never what was decided.
 */
public interface ReportDecisions {

	/** The thing was taken down (or otherwise dealt with). Returns how many reports that closed. */
	int actioned(String subjectType, UUID subjectId, String note, UUID staffId);

	/** Nothing to do: it's left as it is. Returns how many reports that closed. */
	int dismissed(String subjectType, UUID subjectId, String note, UUID staffId);

}
