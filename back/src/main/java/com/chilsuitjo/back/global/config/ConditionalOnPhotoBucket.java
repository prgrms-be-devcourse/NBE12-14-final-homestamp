package com.chilsuitjo.back.global.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 사진 버킷({@code photo.s3.bucket})이 있을 때({@code true}) 또는 없을 때({@code false})만 빈을 만든다. 버킷이 있으면 실제 S3
 * 부품을, 없으면(local·test) 가짜 부품을 쓴다. 가짜는 {@code prod}에서 시작하지 않는다.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ConditionalOnPhotoBucket.BucketCondition.class)
public @interface ConditionalOnPhotoBucket {

  String BUCKET = "photo.s3.bucket";

  /** {@code true}면 버킷이 있을 때, {@code false}면 버킷이 비었을 때 맞다. */
  boolean value() default true;

  final class BucketCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      var attributes = metadata.getAnnotationAttributes(ConditionalOnPhotoBucket.class.getName());
      boolean wantBucket = attributes == null || (boolean) attributes.get("value");
      boolean hasBucket = !context.getEnvironment().getProperty(BUCKET, "").isBlank();
      return wantBucket == hasBucket;
    }
  }
}
