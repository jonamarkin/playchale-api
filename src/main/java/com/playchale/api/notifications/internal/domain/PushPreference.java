package com.playchale.api.notifications.internal.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Which kinds of notification someone keeps off their phone. */
@Entity
@Table(name = "push_preferences")
public class PushPreference {

	@Id
	private UUID userId;

	/** A Postgres text[] column, read and written whole. */
	@JdbcTypeCode(SqlTypes.ARRAY)
	private List<String> muted = new ArrayList<>();

	protected PushPreference() {
	}

	public PushPreference(UUID userId) {
		this.userId = userId;
	}

	public void mute(List<String> categories) {
		this.muted = new ArrayList<>(categories);
	}

	public List<String> getMuted() {
		return List.copyOf(muted);
	}

}
