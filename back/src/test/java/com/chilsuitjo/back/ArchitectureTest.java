package com.chilsuitjo.back;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** 패키지 부르는 방향 검사. 규칙은 docs/planning/09-프로젝트-구조.md 3장. */
@AnalyzeClasses(
    packages = "com.chilsuitjo.back",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule 고리_없음 =
      slices().matching("com.chilsuitjo.back.(*)..").should().beFreeOfCycles();

  @ArchTest
  static final ArchRule action은_도메인을_모른다 =
      noClasses()
          .that()
          .resideInAPackage("..action..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..member..", "..room..", "..inspection..", "..repair..", "..proposal..",
              "..payment..", "..settlement..", "..photo..", "..link..");

  @ArchTest
  static final ArchRule notification은_도메인을_부르지_않는다 =
      noClasses()
          .that()
          .resideInAPackage("..notification..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..member..", "..room..", "..inspection..", "..repair..", "..proposal..",
              "..payment..", "..settlement..", "..photo..", "..link..", "..respond..");

  @ArchTest
  static final ArchRule 누구도_respond를_부르지_않는다 =
      noClasses()
          .that()
          .resideOutsideOfPackage("..respond..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..respond..");

  @ArchTest
  static final ArchRule global은_도메인을_모른다 =
      noClasses()
          .that()
          .resideInAPackage("..global..")
          .should()
          .dependOnClassesThat()
          .resideOutsideOfPackages("..global..", "java..", "javax..", "jakarta..", "org..", "com.fasterxml..");
}
