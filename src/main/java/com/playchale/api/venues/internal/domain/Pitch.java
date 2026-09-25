package com.playchale.api.venues.internal.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.playchale.api.catalog.api.SportCatalog;
import com.playchale.api.shared.error.BusinessException;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

/** One bookable pitch or court. Part of its venue: created, changed and removed through {@link Venue}. */
@Entity
@Table(name = "pitches")
public class Pitch {

	static final Set<String> SURFACES = Set.of("turf", "grass", "hard", "sand", "indoor");

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	private Venue venue;

	private String name;

	private String sport;

	private String format;

	private String surface;

	/** In the venue's currency, minor units. */
	private long pricePerHour;

	private int position;

	private Instant removedAt;

	protected Pitch() {
	}

	Pitch(Venue venue) {
		this.venue = venue;
	}

	void apply(PitchDetails details, int position) {
		var name = details.name() == null ? "" : details.name().strip();
		if (name.isEmpty()) {
			throw BusinessException.invalid("Every pitch needs a name.");
		}
		var sport = SportCatalog.find(details.sport());
		if (sport.isEmpty() || !sport.get().formats().contains(details.format())) {
			throw BusinessException.invalid("Pick a sport and format for every pitch.");
		}
		if (!SURFACES.contains(details.surface())) {
			throw BusinessException.invalid("Pick a surface for every pitch.");
		}
		if (details.pricePerHour() <= 0) {
			throw BusinessException.invalid("Every pitch needs a price per hour.");
		}
		this.name = name;
		this.sport = details.sport();
		this.format = details.format();
		this.surface = details.surface();
		this.pricePerHour = details.pricePerHour();
		this.position = position;
	}

	void remove(Instant now) {
		removedAt = now;
	}

	public boolean isActive() {
		return removedAt == null;
	}

	/** What a booking from {@code minutes} long costs, rounded to the nearest minor unit. */
	public long priceFor(long minutes) {
		return Math.round(pricePerHour * minutes / 60.0);
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getSport() {
		return sport;
	}

	public String getFormat() {
		return format;
	}

	public String getSurface() {
		return surface;
	}

	public long getPricePerHour() {
		return pricePerHour;
	}

}
