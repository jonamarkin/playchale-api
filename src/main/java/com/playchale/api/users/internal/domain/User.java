package com.playchale.api.users.internal.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.playchale.api.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** A player. Registered the first time a phone number signs in, then filled in by onboarding. */
@Entity
@Table(name = "users")
public class User extends AuditableEntity {

	/** Colours behind a new player's initials until they add a photo. Same palette as the web app. */
	public static final List<String> TINTS = List.of("#b7d3c9", "#a9c4f2", "#f2d4a9", "#d9b8e8", "#f5c9b3", "#c9a1d8", "#e8e8e4");

	/** UUID version 7 starts with a timestamp, so new rows land at the end of the index. */
	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	/** E.164, e.g. +233241234567. How they sign in, so it never changes. */
	private String phone;

	/** ISO 3166-1 alpha-2 of the market they signed up in, e.g. GH. The column is char(2). */
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 2)
	private String country;

	private String name = "";

	/** Public profile slug. Empty until onboarding picks one. */
	private String handle = "";

	private String tint;

	private String avatarUrl;

	private String area;

	/** A Postgres text[] column, read and written whole. */
	@JdbcTypeCode(SqlTypes.ARRAY)
	private List<String> sports = new ArrayList<>();

	private String position;

	/** Where money reaches them (E.164). Never used to charge them. */
	private String payoutPhone;

	private boolean onboarded;

	protected User() {
	}

	/** A brand-new player, on their first sign-in. */
	public User(String phone, String country, String tint) {
		this.phone = phone;
		this.country = country;
		this.tint = tint;
	}

	public UUID getId() {
		return id;
	}

	public String getPhone() {
		return phone;
	}

	public String getCountry() {
		return country;
	}

	public String getName() {
		return name;
	}

	public String getHandle() {
		return handle;
	}

	public String getTint() {
		return tint;
	}

	public String getAvatarUrl() {
		return avatarUrl;
	}

	public String getArea() {
		return area;
	}

	public List<String> getSports() {
		return sports;
	}

	public String getPosition() {
		return position;
	}

	public String getPayoutPhone() {
		return payoutPhone;
	}

	public boolean isOnboarded() {
		return onboarded;
	}

}
