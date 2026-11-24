package com.chilsuitjo.back.global.config.ssm;

import org.springframework.boot.context.config.ConfigDataResource;

final class SsmConfigDataResource extends ConfigDataResource {

  enum Path {
    APP,
    MIGRATION
  }

  private final Path path;

  SsmConfigDataResource() {
    this(Path.APP);
  }

  SsmConfigDataResource(Path path) {
    this.path = path;
  }

  Path path() {
    return path;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof SsmConfigDataResource resource && path == resource.path;
  }

  @Override
  public int hashCode() {
    return path.hashCode();
  }

  @Override
  public String toString() {
    return switch (path) {
      case APP -> "homestamp-ssm:/homestamp/prod/app/";
      case MIGRATION -> "homestamp-ssm:/homestamp/prod/migration/";
    };
  }
}
