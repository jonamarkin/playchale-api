package com.playchale.api.users;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * A player, as stored. Optional columns are null when not set. Created the first time a phone
 * number signs in, then filled in by onboarding.
 */
@Entity
@Table(name = "users")
public class User {

	/** UUID version 7 starts with a timestamp, so new rows land at the end of the index. */
	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	private String phone;

	/** The column is char(2); saying so lets Hibernate's startup check match it. */
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 2)
	private String country;

	private String name = "";

	private String handle = "";

	private String tint;

	private String avatarUrl;

	private String area;

	/** A Postgres text[] column, read and written whole. */
	@JdbcTypeCode(SqlTypes.ARRAY)
	private List<String> sports = new ArrayList<>();

	private String position;

	private String payoutPhone;

	private boolean onboarded;

	private Instant createdAt;

	private Instant updatedAt;

	/** For Hibernate, which builds entities empty and then fills them in. */
	protected User() {
	}

	/** A brand-new player, before onboarding. */
	public User(String phone, String country, String tint, Instant now) {
		this.phone = phone;
		this.country = country;
		this.tint = tint;
		this.createdAt = now;
		this.updatedAt = now;
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

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
