package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** local·test 프로필에서는 API 문서를 켠다(docs/rules/04 API 문서). local은 bootRun이 켠다(build.gradle). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class ApiDocsTestProfileTest {

  @Autowired private ApplicationContext context;

  @Test
  void test_프로필에서는_API_문서가_켜져_있다() {
    assertThat(context.getBeanNamesForType(OpenApiWebMvcResource.class)).isNotEmpty();
  }
}
