package io.github.andassa.rmqverify.cluster;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

@AnalyzeClasses(
    packages = "io.github.andassa.rmqverify.cluster",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  void clusterDoesNotDependOnOtherAdapters(JavaClasses classes) {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "io.github.andassa.rmqverify.checker..", "io.github.andassa.rmqverify.workload..",
            "io.github.andassa.rmqverify.nemesis..", "io.github.andassa.rmqverify.report..",
            "io.github.andassa.rmqverify.cli..", "com.rabbitmq..")
        .check(classes);
  }

  @ArchTest
  void onlySystemProcessRunnerStartsProcesses(JavaClasses classes) {
    noClasses()
        .that()
        .doNotHaveSimpleName("SystemProcessRunner")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName(ProcessBuilder.class.getName())
        .check(classes);
  }
}
