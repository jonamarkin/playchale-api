package com.playchale.api.competitions.internal.domain;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.playchale.api.catalog.api.SportCatalog;
import com.playchale.api.market.Market;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.shared.maps.MapLink;
import com.playchale.api.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * A league: teams play each other in fixtures that are ordinary games. Called a competition so it
 * fits corporate leagues, inter-school events and community tournaments alike.
 */
@Entity
@Table(name = "competitions")
public class Competition extends AuditableEntity {

	public static final String DRAFT = "draft";

	public static final String RUNNING = "running";

	public static final String FINISHED = "finished";

	/** Crest colours, handed out to teams in turn. Same palette as the web app. */
	public static final String PLAYERS_EXPECTED = "expected";

	/** Schools or organisations: a team may play with no players listed, and its fixtures take a score only. */
	public static final String PLAYERS_OPTIONAL = "optional";

	public static final List<String> TEAM_TINTS = List.of("#7cf0c8", "#a9c4f2", "#f2d4a9", "#d9b8e8", "#b7d3c9", "#f5c9b3", "#c9a1d8",
			"#e8e8e4");

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	private String name;

	private String sport;

	private String format;

	private UUID organiserId;

	private String venueKind;

	private UUID venueId;

	private String venueName;

	private String venueArea;

	/** A map link for fixtures anywhere but a partner venue (a partner venue's own is looked up). See MapLink. */
	private String mapUrl;

	private Instant startsAt;

	private int durationMinutes;

	private String status;

	/** {@link #PLAYERS_EXPECTED} or {@link #PLAYERS_OPTIONAL}. */
	private String playerLists = PLAYERS_EXPECTED;

	/** Where it's played (ISO 3166-1), and its money: fixtures take both. */
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 2)
	private String country = Market.DEFAULT;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 3)
	private String currency = Market.get(Market.DEFAULT).currency();

	/** Matchdays are in this local time (IANA). */
	private String timezone = Market.get(Market.DEFAULT).timezone();

	private int pointsWin = 3;

	private int pointsDraw = 1;

	private int pointsLoss = 0;

	protected Competition() {
	}

	/** A new competition, in draft until its fixtures are drawn. */
	public Competition(CompetitionDetails details, UUID organiserId, Instant now) {
		var name = details.name() == null ? "" : details.name().strip();
		if (name.length() < 3 || name.length() > 80) {
			throw BusinessException.invalid("Give the league a name people will recognise.");
		}
		var sport = SportCatalog.find(details.sport());
		if (sport.isEmpty() || !sport.get().formats().contains(details.format())) {
			throw BusinessException.invalid("Pick a sport and format.");
		}
		if (details.startsAt() == null || !details.startsAt().isAfter(now)) {
			throw BusinessException.invalid("Pick a first matchday in the future.");
		}
		if (details.durationMinutes() < 15 || details.durationMinutes() > 480) {
			throw BusinessException.invalid("Pick how long each fixture lasts.");
		}
		this.name = name;
		this.sport = details.sport();
		this.format = details.format();
		this.organiserId = organiserId;
		this.startsAt = details.startsAt();
		this.durationMinutes = details.durationMinutes();
		this.status = DRAFT;
		var lists = details.playerLists() == null ? PLAYERS_EXPECTED : details.playerLists();
		if (!PLAYERS_EXPECTED.equals(lists) && !PLAYERS_OPTIONAL.equals(lists)) {
			throw BusinessException.invalid("Say whether teams list their players.");
		}
		this.playerLists = lists;
	}

	/** Fixtures played at a partner venue. */
	public void playAt(UUID venueId, String venueName, String venueArea) {
		this.venueKind = "listed";
		this.venueId = venueId;
		this.venueName = venueName;
		this.venueArea = venueArea;
		this.mapUrl = null;
	}

	/** Fixtures played anywhere the organiser types, maybe with a map link or coordinates. */
	public void playAt(String venueName, String venueArea, String mapUrl) {
		var name = venueName == null ? "" : venueName.strip();
		if (name.isEmpty() || name.length() > 120) {
			throw BusinessException.invalid("Say where the fixtures are played.");
		}
		var area = venueArea == null ? "" : venueArea.strip();
		this.venueKind = "unlisted";
		this.venueName = name;
		this.venueArea = area.isEmpty() ? null : area;
		this.mapUrl = MapLink.normalise(mapUrl);
	}

	/** Where it's played: its country's money, and kick-offs in {@code timezone} (left out: the country's own). */
	public void placeIn(Market market, String timezone) {
		this.country = market.country();
		this.currency = market.currency();
		if (timezone == null || timezone.isBlank()) {
			this.timezone = market.timezone();
			return;
		}
		try {
			this.timezone = ZoneId.of(timezone.strip()).getId();
		}
		catch (DateTimeException e) {
			throw BusinessException.invalid("Pick a time zone from the list.");
		}
	}

	public ZoneId zone() {
		return ZoneId.of(timezone);
	}

	/** The fixtures are out: the league is under way. */
	public void start() {
		status = RUNNING;
	}

	public boolean isOrganisedBy(UUID userId) {
		return organiserId.equals(userId);
	}

	/** Points for a result, by this league's rules. */
	public int pointsFor(String outcome) {
		return switch (outcome) {
			case "W" -> pointsWin;
			case "D" -> pointsDraw;
			default -> pointsLoss;
		};
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

	public UUID getOrganiserId() {
		return organiserId;
	}

	public String getVenueKind() {
		return venueKind;
	}

	public UUID getVenueId() {
		return venueId;
	}

	public String getVenueName() {
		return venueName;
	}

	public String getMapUrl() {
		return mapUrl;
	}

	public String getVenueArea() {
		return venueArea;
	}

	public Instant getStartsAt() {
		return startsAt;
	}

	public int getDurationMinutes() {
		return durationMinutes;
	}

	public String getCountry() {
		return country;
	}

	public String getCurrency() {
		return currency;
	}

	public String getTimezone() {
		return timezone;
	}

	public String getPlayerLists() {
		return playerLists;
	}

	public String getStatus() {
		return status;
	}

	public int getPointsWin() {
		return pointsWin;
	}

	public int getPointsDraw() {
		return pointsDraw;
	}

	public int getPointsLoss() {
		return pointsLoss;
	}

}
