package com.playchale.api.organisations.internal.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/** JSON contracts for organisation workspaces. Private fields only leave authenticated endpoints. */
public final class OrganisationViews {

	private OrganisationViews() {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Organisation(UUID id, String name, String slug, String country, String primaryColour,
			boolean corporateEnabled, String role, String logoUrl, Instant createdAt) {
	}

	public record Member(UUID userId, String name, String handle, String avatar, String role, Instant joinedAt) {
	}

	public record Invitation(UUID id, String role, Instant expiresAt, Instant createdAt, String inviteUrl) {
	}

	public record Workspace(Organisation organisation, List<Member> members, List<Invitation> invitations) {
	}

}
