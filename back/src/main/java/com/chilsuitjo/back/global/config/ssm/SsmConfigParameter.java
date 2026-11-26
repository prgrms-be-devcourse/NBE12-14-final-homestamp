package com.chilsuitjo.back.global.config.ssm;

/** A Parameter Store value whose textual representation never exposes its value. */
public record SsmConfigParameter(String name, String value, String type) {

  @Override
  public String toString() {
    return "SsmConfigParameter[name=" + name + ", value=<redacted>, type=" + type + "]";
  }
}
