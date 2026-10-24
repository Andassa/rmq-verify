package io.github.andassa.rmqverify.checker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

@AnalyzeClasses(
    packages = "io.github.andassa.rmqverify.checker",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  void checkersDependOnlyOnTheModel(JavaClasses classes) {
    classes()
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage(
            "io.github.andassa.rmqverify.checker..", "io.github.andassa.rmqverify.model", "java..")
        .check(classes);
  }

  @ArchTest
  void noCycles(JavaClasses classes) {
    slices()
        .matching("io.github.andassa.rmqverify.checker.(**)")
        .should()
        .beFreeOfCycles()
        .check(classes);
  }
}
