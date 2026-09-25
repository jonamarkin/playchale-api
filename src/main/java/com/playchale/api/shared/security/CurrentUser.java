package com.playchale.api.shared.security;

import java.util.UUID;

/**
 * The signed-in player, for any module's controllers. Declare a parameter of this type and it's
 * filled in from the session cookie; without a session the request is refused with 401 before the
 * method runs. Declare {@code Optional<CurrentUser>} instead where signing in is optional.
 */
public record CurrentUser(UUID id) {
}
