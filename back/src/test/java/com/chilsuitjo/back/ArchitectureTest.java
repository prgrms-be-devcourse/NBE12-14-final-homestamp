package com.chilsuitjo.back;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 패키지 부르는 방향 검사. 규칙은 docs/rules/09-프로젝트-구조.md 3장.
 *
 * <p>패키지는 전체 경로로 쓴다. {@code "..link.."}처럼 짧게 쓰면 다른 라이브러리의 같은 이름 패키지에도 걸린다. 규칙은 "이 도메인에 의존하지
 * 않는다"로만 쓴다(허용 목록으로 쓰면 라이브러리를 더할 때마다 실패한다).
 */
@AnalyzeClasses(
    packages = "com.chilsuitjo.back",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  private static final String ROOT = "com.chilsuitjo.back.";

  private static String[] packages(String... names) {
    String[] result = new String[names.length];
    for (int i = 0; i < names.length; i++) {
      result[i] = ROOT + names[i] + "..";
    }
    return result;
  }

  /** 09 3장의 [도메인] 층 가운데 action·notification이 몰라야 하는 것. */
  private static final String[] 도메인 =
      packages(
          "member", "room", "inspection", "repair", "proposal", "payment", "settlement", "photo",
          "link");

  /** global(바닥) 위의 모든 패키지. */
  private static final String[] GLOBAL_위 =
      packages(
          "member", "auth", "room", "inspection", "action", "repair", "correction", "proposal",
          "payment", "settlement", "photo", "notification", "link", "respond");

  @ArchTest
  static final ArchRule 고리_없음 =
      slices().matching("com.chilsuitjo.back.(*)..").should().beFreeOfCycles();

  @ArchTest
  static final ArchRule action은_도메인을_모른다 =
      noClasses()
          .that()
          .resideInAPackage(ROOT + "action..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(도메인);

  @ArchTest
  static final ArchRule notification은_도메인을_부르지_않는다 =
      noClasses()
          .that()
          .resideInAPackage(ROOT + "notification..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(concat(도메인, packages("respond")));

  @ArchTest
  static final ArchRule 누구도_respond를_부르지_않는다 =
      noClasses()
          .that()
          .resideOutsideOfPackage(ROOT + "respond..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage(ROOT + "respond..");

  @ArchTest
  static final ArchRule global은_도메인을_모른다 =
      noClasses()
          .that()
          .resideInAPackage(ROOT + "global..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(GLOBAL_위);

  private static String[] concat(String[] a, String[] b) {
    String[] result = new String[a.length + b.length];
    System.arraycopy(a, 0, result, 0, a.length);
    System.arraycopy(b, 0, result, a.length, b.length);
    return result;
  }
}
