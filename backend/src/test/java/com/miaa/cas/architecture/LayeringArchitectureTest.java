package com.miaa.cas.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import archfixtures.BadController;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Architecture rules for the backend (MS §7), checked on the compiled production classes on every build:
 * controllers are thin and declare their access check, services never call controllers, shared code and entities do not
 * reach into business modules, development seeders stay out of production code, and money is never built from binary
 * floating point.
 */
class LayeringArchitectureTest {

    private static final String ROOT = "com.miaa.cas";
    private static final String[] BUSINESS_MODULES = Arrays.stream(new String[] {"accountspayable", "accountsreceivable", "approvals",
            "budgeting", "closing", "dashboard", "dataexport", "documents", "financialreporting", "fixedassets", "generalledger", "identity",
            "integrations", "inventoryaccounting", "journal", "migration", "notifications", "organization", "tax", "tickets", "treasury"})
            .map(m -> ROOT + "." + m + "..").toArray(String[]::new);

    /** The sign-in endpoints that are public by design; SecurityConfig opens exactly these and denies anything unmatched. */
    private static final Set<String> PUBLIC_AUTH_ENDPOINTS = Set.of("csrf", "login", "enroll", "activate", "recover", "devBypass", "verify",
            "refresh", "logout", "forgot", "reset", "sendEmailCode", "verifyEmailCode", "passkeyOptions", "passkeyVerify", "clientToken");

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ROOT);
    }

    private static final ArchRule CONTROLLERS_DO_NOT_QUERY = noClasses().that().areAnnotatedWith(RestController.class)
                .should().dependOnClassesThat().areAssignableTo(JdbcTemplate.class)
                .orShould().dependOnClassesThat().areAssignableTo(NamedParameterJdbcTemplate.class)
                .orShould().dependOnClassesThat().areAssignableTo(EntityManager.class)
                .orShould().dependOnClassesThat().areAssignableTo(Repository.class)
                .because("SQL and persistence belong in services; controllers only map HTTP to them");

    private static final ArchRule ENDPOINTS_DECLARE_ACCESS = methods().that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .and(areEndpoints()).should(declareAccessCheckUnlessPublicSignIn())
            .because("the backend enforces authorisation on every endpoint (deny by default)");

    private static final ArchRule EXACT_MONEY = noClasses().should().callConstructor(BigDecimal.class, double.class)
            .orShould().callMethod(BigDecimal.class, "valueOf", double.class)
            .because("amounts are exact decimals; a double cannot represent 0.10");

    @Test
    void controllersDelegateAndNeverQueryTheDatabase() {
        CONTROLLERS_DO_NOT_QUERY.check(production);
    }

    @Test
    void nothingCallsAController() {
        noClasses().that().areNotAnnotatedWith(RestController.class)
                .should().dependOnClassesThat().areAnnotatedWith(RestController.class)
                .because("controllers are the outer layer; DTO records shared with services live outside the controller classes")
                .check(production);
    }

    @Test
    void everyEndpointDeclaresItsAccessCheck() {
        ENDPOINTS_DECLARE_ACCESS.check(production);
    }

    @Test
    void sharedCodeDoesNotDependOnBusinessModules() {
        noClasses().that().resideInAPackage(ROOT + ".common..")
                .should().dependOnClassesThat().resideInAnyPackage(BUSINESS_MODULES)
                .because("common holds infrastructure used by every module (auditing and the current actor are cross-cutting)")
                .check(production);
    }

    @Test
    void entitiesDoNotDependOnServicesOrControllers() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().areAnnotatedWith(Service.class)
                .orShould().dependOnClassesThat().areAnnotatedWith(RestController.class)
                .check(production);
    }

    @Test
    void developmentSeedersStayOutOfProductionCode() {
        noClasses().that().resideOutsideOfPackage(ROOT + ".config.dev..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".config.dev..")
                .check(production);
        classes().that().resideInAPackage(ROOT + ".config.dev..").and().areTopLevelClasses().and().areNotInterfaces()
                .should().beAnnotatedWith(Profile.class)
                .because("development seeders must never load outside the dev profile")
                .check(production);
    }

    @Test
    void moneyIsNeverBuiltFromBinaryFloatingPoint() {
        EXACT_MONEY.check(production);
    }

    /** The rules are not vacuous: a deliberately bad class (test fixture, not production code) breaks each of them. */
    @Test
    void theRulesCatchViolations() {
        JavaClasses bad = new ClassFileImporter().importClasses(BadController.class);
        assertThat(CONTROLLERS_DO_NOT_QUERY.evaluate(bad).hasViolation()).isTrue();
        assertThat(ENDPOINTS_DECLARE_ACCESS.evaluate(bad).hasViolation()).isTrue();
        assertThat(EXACT_MONEY.evaluate(bad).hasViolation()).isTrue();
    }

    @Test
    void generalCodingRules() {
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION.check(production);
        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(production);
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.check(production);
    }

    private static DescribedPredicate<JavaMethod> areEndpoints() {
        return DescribedPredicate.describe("are HTTP endpoints", m -> m.isAnnotatedWith(GetMapping.class) || m.isAnnotatedWith(PostMapping.class)
                || m.isAnnotatedWith(PutMapping.class) || m.isAnnotatedWith(DeleteMapping.class) || m.isAnnotatedWith(PatchMapping.class)
                || m.isAnnotatedWith(RequestMapping.class));
    }

    private static ArchCondition<JavaMethod> declareAccessCheckUnlessPublicSignIn() {
        return new ArchCondition<>("declare @PreAuthorize (on the method or its class) unless a public sign-in endpoint") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                JavaClass owner = method.getOwner();
                boolean declared = method.isAnnotatedWith(PreAuthorize.class) || owner.isAnnotatedWith(PreAuthorize.class);
                boolean publicSignIn = owner.getSimpleName().equals("AuthController") && PUBLIC_AUTH_ENDPOINTS.contains(method.getName());
                if (!declared && !publicSignIn) {
                    events.add(SimpleConditionEvent.violated(method, method.getFullName() + " has no @PreAuthorize"));
                }
            }
        };
    }
}
