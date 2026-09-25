package com.playchale.api;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Keeps the modules apart. Each top-level package (auth, users, games, ...) is a module, and only its
 * public types are its API; everything else is package-private, which the compiler enforces. This
 * test adds what the compiler can't see: no two modules may depend on each other in a circle, and
 * a module may not reach into another's sub-packages.
 */
class ModularityTest {

	@Test
	void modulesOnlyUseEachOthersPublicApi() {
		ApplicationModules.of(PlaychaleApiApplication.class).verify();
	}

}
