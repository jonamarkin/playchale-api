package com.playchale.api.admin.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.playchale.api.admin.internal.service.AdminDesk;
import com.playchale.api.admin.internal.service.Staff;
import com.playchale.api.shared.security.CurrentUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin desk.
 *
 * <p>Every route takes the signed-in person and hands them to the service, which refuses anyone who
 * doesn't work here — the guard is in the service rather than here so that a route added later
 * cannot forget it. A request from someone who isn't staff gets "Not found", because an admin area
 * confirming its own existence is a map for anyone poking at it.
 */
@RestController
@RequestMapping("/admin")
class AdminController {

	private final AdminDesk desk;

	private final Staff staff;

	AdminController(AdminDesk desk, Staff staff) {
		this.desk = desk;
		this.staff = staff;
	}

	/** admin.me: whether this person works here, and as what. The web app asks before showing anything. */
	@GetMapping("/me")
	Map<String, String> me(CurrentUser me) {
		return Map.of("role", staff.require(me.id()));
	}

	/** admin.people */
	@GetMapping("/people")
	List<AdminDesk.Person> people(CurrentUser me, @RequestParam(required = false) String query,
			@RequestParam(defaultValue = "25") int limit) {
		return desk.find(me.id(), query, limit);
	}

	/** admin.history */
	@GetMapping("/people/{id}/history")
	List<AdminDesk.Entry> history(CurrentUser me, @PathVariable UUID id, @RequestParam(defaultValue = "50") int limit) {
		return desk.history(me.id(), id, limit);
	}

	/** admin.export: everything held about someone, for a data request. */
	@GetMapping("/people/{id}/export")
	Map<String, Object> export(CurrentUser me, @PathVariable UUID id) {
		return desk.export(me.id(), id);
	}

	/** admin.messages */
	@GetMapping("/messages")
	List<AdminDesk.Message> messages(CurrentUser me, @RequestParam(defaultValue = "50") int limit) {
		return desk.messages(me.id(), limit);
	}

	/** admin.removeMessage */
	@DeleteMapping("/messages/{id}")
	void removeMessage(CurrentUser me, @PathVariable UUID id, @RequestBody(required = false) Map<String, String> body) {
		desk.removeMessage(me.id(), id, body == null ? null : body.get("why"));
	}

	/** admin.sms: the SMS bundle's credits, for topping it up before sign-in codes stop. */
	@GetMapping("/sms")
	AdminDesk.Sms sms(CurrentUser me) {
		return desk.sms(me.id());
	}

	/** admin.health */
	@GetMapping("/health")
	AdminDesk.Health health(CurrentUser me, @RequestParam(defaultValue = "30") int days) {
		return desk.health(me.id(), days);
	}

}
