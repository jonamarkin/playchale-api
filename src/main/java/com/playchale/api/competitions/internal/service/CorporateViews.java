package com.playchale.api.competitions.internal.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Authenticated operations contracts. None of these private records are used by the public page. */
public final class CorporateViews {

	private CorporateViews() {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Dashboard(UUID competitionId, UUID organisationId, String competitionName, String scheduleStatus,
			Counts teams, Counts rosters, Counts fixtures, int missingOfficials, long feesDue, long feesPaid,
			int attendance, int goals, int yellowCards, int redCards, int announcements, int acknowledgements) {
	}

	public record Counts(int total, int pending, int complete) {
	}

	/** A company whose entry someone keeps the roster for. */
	public record ManagedTeam(UUID teamId, String teamName) {
	}

	/**
	 * What a person who is not running the competition may do in it: the companies they enter, and
	 * whether they referee any of its fixtures. Enough for their own screen, and nothing more.
	 */
	public record MyPart(UUID competitionId, String competitionName, List<ManagedTeam> teams, boolean officiates) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record RosterMember(UUID id, UUID teamId, String displayName, String employeeReference, UUID userId,
			String eligibilityState, UUID attestedBy, Instant attestedAt, UUID reviewedBy, Instant reviewedAt,
			String reviewNote, Instant createdAt, String claimUrl) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Location(UUID id, String name, String area, String mapUrl, UUID venueId, UUID pitchId) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Fixture(UUID id, int round, UUID homeTeamId, String homeTeam, UUID awayTeamId, String awayTeam,
			Instant startsAt, int durationMinutes, String status, UUID locationId, String locationName,
			UUID officialId, String officialName, boolean hasResult) {
	}

	public record Conflict(String code, String message, List<UUID> fixtureIds) {
	}

	public record Validation(boolean valid, List<Conflict> conflicts) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Finance(UUID teamId, String teamName, long amountDue, String status, String method,
			String reference, Instant paidAt, String privateNote, Instant updatedAt) {
	}

	public record Announcement(UUID id, String audience, UUID teamId, String title, String body, boolean acknowledgement,
			UUID publishedBy, Instant publishedAt, int recipients, int acknowledged, String whatsappUrl) {
	}

	public record RoleInvitation(UUID id, String role, UUID teamId, Instant expiresAt, String inviteUrl) {
	}

	public record AuditEvent(UUID id, UUID actorId, String eventType, String subjectType, UUID subjectId,
			String details, Instant occurredAt) {
	}

	public record MatchPlayer(UUID rosterMemberId, UUID teamId, String displayName, UUID userId,
			String participation, boolean checkedIn, int goals, int assists) {
	}

	public record Card(UUID id, UUID rosterMemberId, String colour, Integer minute, String note) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record MatchSheet(UUID gameId, int homeScore, int awayScore, String notes, String status,
			UUID savedBy, Instant savedAt, UUID submittedBy, Instant submittedAt, int version,
			List<MatchPlayer> players, List<Card> cards) {
	}
}
