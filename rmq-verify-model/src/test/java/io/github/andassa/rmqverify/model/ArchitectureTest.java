package io.github.andassa.rmqverify.model;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

@AnalyzeClasses(
    packages = "io.github.andassa.rmqverify.model",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  void domainDependsOnlyOnTheJdk(JavaClasses classes) {
    classes()
        .that()
        .resideInAPackage("io.github.andassa.rmqverify.model")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage("io.github.andassa.rmqverify.model", "java..")
        .check(classes);
  }

  @ArchTest
  void jsonlUsesOnlyTheStreamingApi(JavaClasses classes) {
    classes()
        .that()
        .resideInAPackage("..model.jsonl..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage(
            "io.github.andassa.rmqverify.model..", "java..", "com.fasterxml.jackson.core..")
        .check(classes);
  }

  @ArchTest
  void domainDoesNotKnowTheCodec(JavaClasses classes) {
    noClasses()
        .that()
        .resideInAPackage("io.github.andassa.rmqverify.model")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..model.jsonl..")
        .check(classes);
  }

  @ArchTest
  void noCycles(JavaClasses classes) {
    slices().matching("io.github.andassa.rmqverify.(**)").should().beFreeOfCycles().check(classes);
  }

  @ArchTest
  @SuppressWarnings("checkstyle:RegexpSinglelineJava") // The rule has to name what it forbids.
  void noJavaSerialization(JavaClasses classes) {
    noClasses()
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.io.ObjectInputStream")
        .check(classes);
  }
}
