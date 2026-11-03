package se.rightech.fakturummetsarkiv.video;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Modulgräns för video-paketet (se docs/video-integration-notes.md).
 * Paketet video får inte bero på resten av appen, och resten av appen får
 * bara nå public API:et (video), inte video.internal.
 */
@AnalyzeClasses(packages = "se.rightech.fakturummetsarkiv")
public class VideoModuleBoundaryTest {

    @ArchTest
    static final ArchRule videoPackageDoesNotDependOnApplicationPackages =
            noClasses().that().resideInAPackage("se.rightech.fakturummetsarkiv.video..")
                    .and().resideOutsideOfPackage("se.rightech.fakturummetsarkiv.video.internal..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "se.rightech.fakturummetsarkiv.rag..",
                            "se.rightech.fakturummetsarkiv.chat..",
                            "se.rightech.fakturummetsarkiv.grounding..",
                            "se.rightech.fakturummetsarkiv.llm..",
                            "se.rightech.fakturummetsarkiv.system..",
                            "se.rightech.fakturummetsarkiv.markdown..");

    @ArchTest
    static final ArchRule applicationDoesNotDependOnVideoInternals =
            noClasses().that().resideOutsideOfPackage("se.rightech.fakturummetsarkiv.video..")
                    .should().dependOnClassesThat().resideInAPackage("se.rightech.fakturummetsarkiv.video.internal..");
}
