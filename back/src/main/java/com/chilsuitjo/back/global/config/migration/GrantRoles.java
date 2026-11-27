package com.chilsuitjo.back.global.config.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * GRANT 대상 역할 이름. Owner는 Migration 비밀 파일({@code app.migration.owner-role})에서, App·Purge·Human은 이미지
 * 안의 {@code db/grants/roles.properties}에서만 읽는다. 환경변수·시스템 속성·Spring 설정은 보지 않는다(역할 이름은 비밀이 아니고 이미지와
 * 함께 검증·버전 관리한다).
 *
 * <p>Human은 #185 계약(decisions 2026-11-25 「Human 그룹」)대로 NOLOGIN 읽기 그룹 하나다. 사람별 LOGIN 역할은 그 그룹의 멤버로
 * 관리자가 따로 만들고(런북), GRANT는 그룹에만 준다.
 *
 * @param owner 객체 소유 역할(NOLOGIN)
 * @param app 앱 로그인
 * @param purge 삭제 작업 로그인
 * @param human Human 읽기 그룹(NOLOGIN)
 */
public record GrantRoles(String owner, String app, String purge, String human) {

  public static final String RESOURCE = "db/grants/roles.properties";
  static final String APP_KEY = "app-role";
  static final String PURGE_KEY = "purge-role";
  static final String HUMAN_KEY = "human-role";
  private static final List<String> KEYS = List.of(APP_KEY, PURGE_KEY, HUMAN_KEY);
  private static final int MAX_BYTES = 4096;

  /** bootstrap_roles.sql·MigrationSettings와 같은 식별자 규칙. 따옴표로 감싸도 안전한 소문자 이름만. */
  static final Pattern ROLE_NAME = Pattern.compile("(?!pg_)[a-z_][a-z0-9_]{0,62}");

  public GrantRoles {
    List<String> all = List.of(nonNull(owner), nonNull(app), nonNull(purge), nonNull(human));
    for (String name : all) {
      if (!ROLE_NAME.matcher(name).matches()) {
        throw new IllegalStateException("GRANT 역할 이름 형식이 올바르지 않다");
      }
    }
    if (new HashSet<>(all).size() != all.size()) {
      throw new IllegalStateException("GRANT 역할 이름이 겹친다");
    }
  }

  /** 이미지 안의 역할 설정을 읽는다. Owner는 이미 검증한 Migration 설정 값이다. */
  public static GrantRoles fromImage(String owner, String migrationLogin) {
    return fromResource(RESOURCE, owner, migrationLogin);
  }

  /** 테스트가 다른 리소스 이름으로 형식 오류를 재현할 때만 쓴다(운영 실행 경로는 {@link #fromImage}). */
  public static GrantRoles fromResource(String resource, String owner, String migrationLogin) {
    try (InputStream in = GrantRoles.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("GRANT 역할 설정 리소스가 없다");
      }
      byte[] bytes = in.readNBytes(MAX_BYTES + 1);
      if (bytes.length > MAX_BYTES) {
        throw new IllegalStateException("GRANT 역할 설정이 너무 크다");
      }
      return parse(decode(bytes), owner, migrationLogin);
    } catch (IOException exception) {
      throw new IllegalStateException("GRANT 역할 설정을 읽지 못했다");
    }
  }

  /** {@code 키=값} 줄과 {@code #} 주석·빈 줄만 허용한다. 모르는 키·중복 키·빠진 키는 실패. */
  static GrantRoles parse(String text, String owner, String migrationLogin) {
    Map<String, String> values = new LinkedHashMap<>();
    for (String raw : text.split("\n", -1)) {
      String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      int equals = line.indexOf('=');
      if (equals <= 0) {
        throw new IllegalStateException("GRANT 역할 설정 줄 형식이 올바르지 않다");
      }
      String key = line.substring(0, equals);
      if (!KEYS.contains(key)) {
        throw new IllegalStateException("GRANT 역할 설정에 모르는 키가 있다: " + safeKey(key));
      }
      if (values.put(key, line.substring(equals + 1)) != null) {
        throw new IllegalStateException("GRANT 역할 설정 키가 중복됐다: " + key);
      }
    }
    for (String key : KEYS) {
      if (!values.containsKey(key) || values.get(key).isEmpty()) {
        throw new IllegalStateException("GRANT 역할 설정이 없다: " + key);
      }
    }
    GrantRoles roles =
        new GrantRoles(owner, values.get(APP_KEY), values.get(PURGE_KEY), values.get(HUMAN_KEY));
    if (Set.of(roles.app(), roles.purge(), roles.human()).contains(migrationLogin)) {
      throw new IllegalStateException("GRANT 대상에 Migration 로그인을 넣지 않는다");
    }
    return roles;
  }

  private static String decode(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException exception) {
      throw new IllegalStateException("GRANT 역할 설정이 UTF-8이 아니다");
    }
  }

  private static String nonNull(String value) {
    if (value == null) {
      throw new IllegalStateException("GRANT 역할 이름이 없다");
    }
    return value;
  }

  private static String safeKey(String key) {
    return key.matches("[a-z0-9.-]{1,40}") ? key : "(형식 밖)";
  }
}
