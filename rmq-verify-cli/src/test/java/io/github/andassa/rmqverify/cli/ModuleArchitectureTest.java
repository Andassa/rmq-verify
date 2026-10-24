package io.github.andassa.rmqverify.cli;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import java.util.regex.Pattern;

/** The hexagon of docs/ARCHITECTURE.md, checked across every module. */
@AnalyzeClasses(
    packages = "io.github.andassa.rmqverify",
    importOptions = {ImportOption.DoNotIncludeTests.class, ModuleArchitectureTest.NoTestKits.class})
class ModuleArchitectureTest {

  /** Test kits published as test-jars sit on this classpath but are not production code. */
  static final class NoTestKits implements ImportOption {
    private static final Pattern TEST_CODE =
        Pattern.compile(".*(-tests\\.jar|/testkit/|(Test|Properties)(\\$[^/]*)?\\.class).*");

    @Override
    public boolean includes(Location location) {
      return !location.matches(TEST_CODE);
    }
  }

  private static final String ROOT = "io.github.andassa.rmqverify";

  @ArchTest
  void modulesFormNoCycle(JavaClasses classes) {
    slices().matching(ROOT + ".(*)..").should().beFreeOfCycles().check(classes);
  }

  @ArchTest
  void modelHasNoOutgoingDependency(JavaClasses classes) {
    classes()
        .that()
        .resideInAPackage(ROOT + ".model..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage(ROOT + ".model..", "java..", "com.fasterxml.jackson.core..")
        .check(classes);
  }

  @ArchTest
  void checkersOnlyKnowTheModel(JavaClasses classes) {
    classes()
        .that()
        .resideInAPackage(ROOT + ".checker..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage(ROOT + ".checker..", ROOT + ".model..", "java..")
        .check(classes);
  }

  @ArchTest
  void reportOnlyKnowsTheModel(JavaClasses classes) {
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".report..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            ROOT + ".checker..",
            ROOT + ".cluster..",
            ROOT + ".workload..",
            ROOT + ".nemesis..",
            ROOT + ".cli..",
            "com.rabbitmq..")
        .check(classes);
  }

  @ArchTest
  void brokerClientsAreConfinedToTheWorkload(JavaClasses classes) {
    noClasses()
        .that()
        .resideOutsideOfPackage(ROOT + ".workload..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.rabbitmq..")
        .check(classes);
  }

  @ArchTest
  void processesAreStartedOnlyByTheCluster(JavaClasses classes) {
    noClasses()
        .that()
        .resideOutsideOfPackage(ROOT + ".cluster..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName(ProcessBuilder.class.getName())
        .check(classes);
  }

  @ArchTest
  void workloadDoesNotTouchTheCluster(JavaClasses classes) {
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".workload..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(ROOT + ".cluster..", ROOT + ".nemesis..", ROOT + ".checker..")
        .check(classes);
  }
}
