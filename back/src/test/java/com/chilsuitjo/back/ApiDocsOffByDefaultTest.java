package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** 프로필이 없으면(운영과 같음) API 문서를 만들지 않는다(docs/rules/04 API 문서: 내부 API 구조 노출 방지). */
class ApiDocsOffByDefaultTest extends SchemaTestSupport {

  @Autowired private ApplicationContext context;

  @Test
  void 프로필이_없으면_API_문서가_꺼져_있다() {
    assertThat(context.getBeanNamesForType(OpenApiWebMvcResource.class)).isEmpty();
  }
}
