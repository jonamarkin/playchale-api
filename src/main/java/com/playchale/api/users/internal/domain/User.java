package com.playchale.api.users.internal.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.playchale.api.catalog.api.SportCatalog;
import com.playchale.api.market.Market;
import com.playchale.api.shared.error.BusinessException;
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

	/** Lower-case letters, digits and _, 3 to 20 of them. The web app's handle field allows the same. */
	private static final Pattern HANDLE = Pattern.compile("^[a-z0-9_]{3,20}$");

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

	/** The name other players see. */
	public void rename(String name) {
		var trimmed = name.strip();
		if (trimmed.isEmpty()) {
			throw BusinessException.invalid("Tell us your name.");
		}
		if (trimmed.length() > 60) {
			throw BusinessException.invalid("Keep your name under 60 characters.");
		}
		this.name = trimmed;
	}

	/**
	 * The public @handle, stored lower-case. Whether someone else has it is the service's check,
	 * since that needs the other players.
	 */
	public void changeHandle(String handle) {
		var normalised = normaliseHandle(handle);
		if (!HANDLE.matcher(normalised).matches()) {
			throw BusinessException.invalid("Use 3 to 20 characters: letters, numbers or _.");
		}
		this.handle = normalised;
	}

	/** Where they usually play, e.g. "East Legon". Blank clears it. */
	public void moveTo(String area) {
		this.area = optional(area, 80, "Keep the area under 80 characters.");
	}

	/** Only sports PlayChale supports, each once, in the order picked. */
	public void playSports(List<String> sports) {
		if (sports.stream().anyMatch(sport -> sport == null || !SportCatalog.exists(sport))) {
			throw BusinessException.invalid("Pick sports from the list.");
		}
		this.sports = new ArrayList<>(sports.stream().distinct().toList());
	}

	/** What they usually play, e.g. "Striker". Blank clears it. */
	public void playPosition(String position) {
		this.position = optional(position, 40, "Keep your position under 40 characters.");
	}

	/** The mobile money number money should reach them on. Blank clears it. */
	public void payTo(String phone) {
		if (phone.isBlank()) {
			this.payoutPhone = null;
			return;
		}
		var market = Market.get(country);
		this.payoutPhone = market.normalisePhone(phone)
			.orElseThrow(() -> BusinessException.invalid("Enter a valid %s mobile number for payouts, e.g. 024 123 4567.".formatted(market.countryName())));
	}

	/** Onboarding is done once they have a name and a handle, which a public profile needs. */
	public void finishOnboarding() {
		if (name.isBlank() || handle.isBlank()) {
			throw BusinessException.invalid("Add your name and a username to finish.");
		}
		this.onboarded = true;
	}

	/** How a handle is compared and stored: trimmed, lower-case, without a leading @. */
	public static String normaliseHandle(String handle) {
		var trimmed = handle.strip().toLowerCase(Locale.ROOT);
		return trimmed.startsWith("@") ? trimmed.substring(1) : trimmed;
	}

	public static boolean isValidHandle(String handle) {
		return HANDLE.matcher(normaliseHandle(handle)).matches();
	}

	private static String optional(String value, int max, String tooLong) {
		var trimmed = value.strip();
		if (trimmed.length() > max) {
			throw BusinessException.invalid(tooLong);
		}
		return trimmed.isEmpty() ? null : trimmed;
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
