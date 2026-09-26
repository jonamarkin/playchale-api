/**
 * Adapters for outside systems (SMS, email through Resend, payments through Paystack). Each sits behind an interface, with a
 * stand-in for development, so the modules never know which provider is on the other end. An open
 * module: every module may use these interfaces.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.playchale.api.integration;

import org.springframework.modulith.ApplicationModule;
