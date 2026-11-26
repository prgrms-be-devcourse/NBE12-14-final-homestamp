package com.chilsuitjo.back.global.config.ssm;

import java.util.List;

/** Loads only the application's production parameters. */
@FunctionalInterface
public interface SsmParameterSource {

  List<SsmConfigParameter> loadAppParameters();
}
