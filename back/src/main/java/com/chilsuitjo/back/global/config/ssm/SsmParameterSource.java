package com.chilsuitjo.back.global.config.ssm;

import java.util.List;

/** Reads the explicitly selected application or Migration Parameter Store path. */
@FunctionalInterface
public interface SsmParameterSource {

  List<SsmConfigParameter> loadAppParameters();

  default List<SsmConfigParameter> loadMigrationParameters() {
    throw new IllegalStateException("Migration SSM 설정 조회가 구성되지 않았다");
  }
}
