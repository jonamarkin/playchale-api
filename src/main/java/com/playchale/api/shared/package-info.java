/**
 * Shared infrastructure every module may use: settings, the error shape, CORS, request logging and
 * JPA base classes. An open module, so all of its packages are usable from anywhere. Keep it small:
 * business rules belong in the modules, never here.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.playchale.api.shared;

import org.springframework.modulith.ApplicationModule;
