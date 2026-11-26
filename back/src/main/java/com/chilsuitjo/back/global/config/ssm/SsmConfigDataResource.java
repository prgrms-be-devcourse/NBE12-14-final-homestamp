package com.chilsuitjo.back.global.config.ssm;

import org.springframework.boot.context.config.ConfigDataResource;

final class SsmConfigDataResource extends ConfigDataResource {

  @Override
  public boolean equals(Object other) {
    return other instanceof SsmConfigDataResource;
  }

  @Override
  public int hashCode() {
    return SsmConfigDataResource.class.hashCode();
  }

  @Override
  public String toString() {
    return "homestamp-ssm:/homestamp/prod/app/";
  }
}
