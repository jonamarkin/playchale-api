package com.playchale.api;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Conventions inside a module that the compiler can't see. (Rules between modules are checked by
 * ModularityTest.)
 */
class ArchitectureTest {

	private static final JavaClasses classes = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.importPackages("com.playchale.api");

	@Test
	void controllersGoThroughServicesNotRepositories() {
		noClasses().that().resideInAPackage("com.playchale.api.*.web..")
			.should().dependOnClassesThat().resideInAPackage("com.playchale.api.*.internal.repository..")
			.because("business rules and transactions live in services")
			.check(classes);
	}

}
