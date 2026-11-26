package com.chilsuitjo.back.global.config.ssm;

import java.util.Map;
import org.springframework.core.env.EnumerablePropertySource;

/** An SSM-backed property source that never prints its property values. */
final class RedactedSsmPropertySource extends EnumerablePropertySource<Map<String, Object>> {

  RedactedSsmPropertySource(Map<String, Object> properties) {
    super("homestampSsmApp", properties);
  }

  @Override
  public String[] getPropertyNames() {
    return source.keySet().toArray(String[]::new);
  }

  @Override
  public Object getProperty(String name) {
    return source.get(name);
  }

  @Override
  public String toString() {
    return "RedactedSsmPropertySource[name=" + getName() + ", keys=" + source.keySet() + "]";
  }
}
