package family.eilertsen.rack.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.onionArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(
    packages = "family.eilertsen.rack",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule onion = onionArchitecture()
        .domainModels("family.eilertsen.rack.domain.model..")
        .domainServices("family.eilertsen.rack.domain.port..")
        .applicationServices("family.eilertsen.rack.application..")
        .adapter("in", "family.eilertsen.rack.adapter.in..")
        .adapter("out", "family.eilertsen.rack.adapter.out..")
        .withOptionalLayers(true);

    @ArchTest
    static final ArchRule domainHasNoFrameworkImports = noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework..",
            "jakarta.persistence..",
            "jakarta.servlet..",
            "com.fasterxml.jackson..");

    @ArchTest
    static final ArchRule portsAreInterfaces = classes()
        .that().resideInAPackage("..domain.port..")
        .should().beInterfaces();

    @ArchTest
    static final ArchRule noPackageCycles = slices()
        .matching("family.eilertsen.rack.(*)..")
        .should().beFreeOfCycles();
}
