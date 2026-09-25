package com.playchale.api.venues.internal.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.playchale.api.market.Market;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.shared.persistence.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * A partner venue: the place, its pitches and its opening hours. Owned by the player who listed it,
 * and the only way its pitches change.
 */
@Entity
@Table(name = "venues")
public class Venue extends AuditableEntity {

	static final Set<String> AMENITIES = Set.of("floodlights", "changing-rooms", "showers", "parking", "water",
			"equipment", "seating", "toilets");

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	private UUID ownerId;

	private String name;

	private String area;

	private String description;

	private String address;

	private String phone;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 2)
	private String country;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 3)
	private String currency;

	private String timezone;

	private boolean listed;

	/** Sunday first: "HH:MM-HH:MM", or "" when closed. {@link #hours()} reads them. */
	@JdbcTypeCode(SqlTypes.ARRAY)
	private List<String> hours = new ArrayList<>();

	@JdbcTypeCode(SqlTypes.ARRAY)
	private List<String> amenities = new ArrayList<>();

	@OneToMany(mappedBy = "venue", cascade = CascadeType.ALL)
	@OrderBy("position")
	private List<Pitch> pitches = new ArrayList<>();

	protected Venue() {
	}

	/** A new listed venue in a market, before its details are applied. */
	public Venue(UUID ownerId, Market market) {
		this.ownerId = ownerId;
		this.country = market.country();
		this.currency = market.currency();
		this.timezone = market.timezone();
		this.listed = true;
	}

	/**
	 * Applies what the owner filled in. Pitches with an ID are updated, new ones added, and any not
	 * mentioned are removed (the caller checks first that none of those have bookings to come).
	 */
	public void apply(VenueDetails details, Instant now) {
		var name = strip(details.name());
		if (name.length() < 2 || name.length() > 80) {
			throw BusinessException.invalid("Give your venue a name.");
		}
		var area = strip(details.area());
		if (area.isEmpty() || area.length() > 80) {
			throw BusinessException.invalid("Add the area your venue is in.");
		}
		if (details.pitches() == null || details.pitches().isEmpty()) {
			throw BusinessException.invalid("Add at least one pitch or court.");
		}
		if (details.hours() == null || details.hours().size() != 7) {
			throw BusinessException.invalid("The opening hours aren’t valid. Use times like 06:00 and 22:00.");
		}
		if (details.hours().stream().allMatch(Objects::isNull)) {
			throw BusinessException.invalid("Open at least one day a week.");
		}
		var amenities = details.amenities() == null ? List.<String>of() : details.amenities();
		if (amenities.stream().anyMatch(a -> a == null || !AMENITIES.contains(a))) {
			throw BusinessException.invalid("Pick amenities from the list.");
		}
		var market = Market.get(country);
		String phone = null;
		if (!strip(details.phone()).isEmpty()) {
			phone = market.normalisePhone(details.phone())
				.orElseThrow(() -> BusinessException.invalid("Enter a valid %s number, or leave it blank.".formatted(market.countryName())));
		}

		this.name = name;
		this.area = area;
		this.description = optional(details.description(), 1000);
		this.address = optional(details.address(), 200);
		this.phone = phone;
		this.hours = new ArrayList<>(details.hours().stream().map(h -> h == null ? "" : h.stored()).toList());
		this.amenities = new ArrayList<>(amenities.stream().distinct().toList());
		applyPitches(details.pitches(), now);
	}

	private void applyPitches(List<PitchDetails> wanted, Instant now) {
		var keep = wanted.stream().map(PitchDetails::id).filter(Objects::nonNull).toList();
		for (var pitch : activePitches()) {
			if (!keep.contains(pitch.getId())) {
				pitch.remove(now);
			}
		}
		for (int i = 0; i < wanted.size(); i++) {
			var details = wanted.get(i);
			var pitch = details.id() == null ? addPitch() : activePitch(details.id())
				.orElseThrow(() -> BusinessException.invalid("That pitch isn’t at this venue any more. Reload and try again."));
			pitch.apply(details, i);
		}
	}

	private Pitch addPitch() {
		var pitch = new Pitch(this);
		pitches.add(pitch);
		return pitch;
	}

	/** Pitches the owner would remove by saving these details, so bookings on them can be checked first. */
	public List<UUID> pitchesRemovedBy(VenueDetails details) {
		var keep = details.pitches() == null ? List.<UUID>of()
				: details.pitches().stream().map(PitchDetails::id).filter(Objects::nonNull).toList();
		return activePitches().stream().map(Pitch::getId).filter(id -> !keep.contains(id)).toList();
	}

	public List<Pitch> activePitches() {
		return pitches.stream().filter(Pitch::isActive).toList();
	}

	public Optional<Pitch> activePitch(UUID pitchId) {
		return activePitches().stream().filter(p -> p.getId().equals(pitchId)).findFirst();
	}

	/** Every sport played across its pitches. */
	public List<String> sports() {
		return activePitches().stream().map(Pitch::getSport).distinct().toList();
	}

	/** Seven days, Sunday first; null when closed. */
	public List<DayHours> hours() {
		return hours.stream().map(h -> h.isEmpty() ? null : DayHours.fromStored(h)).toList();
	}

	public Optional<DayHours> hoursOn(DayOfWeek day) {
		return Optional.ofNullable(hours().get(day.getValue() % 7));
	}

	/** Whether the whole of [start, end) falls within one day's opening hours, in the venue's time. */
	public boolean isOpen(Instant start, Instant end) {
		var localStart = start.atZone(zone());
		var localEnd = end.atZone(zone());
		var lastMoment = end.minusNanos(1).atZone(zone());
		if (!localStart.toLocalDate().equals(lastMoment.toLocalDate())) {
			return false;
		}
		var day = hoursOn(localStart.getDayOfWeek());
		if (day.isEmpty()) {
			return false;
		}
		int startMinute = localStart.getHour() * 60 + localStart.getMinute();
		int endMinute = (int) Duration.between(localStart.toLocalDate().atStartOfDay(zone()), localEnd).toMinutes();
		return startMinute >= day.get().openMinute() && endMinute <= day.get().closeMinute();
	}

	public boolean isOwnedBy(UUID userId) {
		return ownerId.equals(userId);
	}

	public ZoneId zone() {
		return ZoneId.of(timezone);
	}

	public UUID getId() {
		return id;
	}

	public UUID getOwnerId() {
		return ownerId;
	}

	public String getName() {
		return name;
	}

	public String getArea() {
		return area;
	}

	public String getDescription() {
		return description;
	}

	public String getAddress() {
		return address;
	}

	public String getPhone() {
		return phone;
	}

	public String getCurrency() {
		return currency;
	}

	public boolean isListed() {
		return listed;
	}

	public List<String> getAmenities() {
		return List.copyOf(amenities);
	}

	private static String strip(String value) {
		return value == null ? "" : value.strip();
	}

	private static String optional(String value, int max) {
		var stripped = strip(value);
		if (stripped.length() > max) {
			throw BusinessException.invalid("That’s too long. Keep it under %d characters.".formatted(max));
		}
		return stripped.isEmpty() ? null : stripped;
	}

}
