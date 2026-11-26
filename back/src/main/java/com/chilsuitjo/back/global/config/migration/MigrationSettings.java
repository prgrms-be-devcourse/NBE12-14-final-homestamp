package com.chilsuitjo.back.global.config.migration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 일회성 Migration의 DB 접속 설정을 root가 준비한 고정 디렉터리에서 읽는다.
 *
 * <p>디렉터리에는 정해진 4개 이름의 일반 파일만 있어야 한다. 다른 이름·하위 디렉터리·심볼릭 링크·특수 파일이 하나라도 있으면 실패한다. 파일은 링크를 따라가지 않고
 * 열고, 4 KiB·UTF-8·제어 문자를 검사한다. 끝의 줄바꿈 하나만 지운다. 예외 메시지에는 값을 넣지 않는다.
 *
 * <p>파일 소유자·권한(0500/0400, Migration 전용 UID)은 호스트와 컨테이너 실행 설정이 보장한다. 이 클래스는 그 상태를 전제로 내용만 검증한다.
 */
public final class MigrationSettings {

  public static final Path DEFAULT_DIRECTORY = Path.of("/run/secrets/homestamp-migration");

  static final String URL = "spring.datasource.url";
  static final String USERNAME = "spring.datasource.username";
  static final String PASSWORD = "spring.datasource.password";
  static final String OWNER_ROLE = "app.migration.owner-role";
  static final List<String> NAMES = List.of(URL, USERNAME, PASSWORD, OWNER_ROLE);

  private static final int MAX_BYTES = 4096;
  private static final Set<OpenOption> READ_NO_FOLLOW =
      Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
  // 호스트가 비면 pgjdbc가 PGHOST 같은 기본값을 쓸 수 있으므로 호스트·DB 이름을 꼭 요구한다. 호스트는 하나만(쉼표 목록 불가)
  private static final Pattern JDBC_URL =
      Pattern.compile("jdbc:postgresql://[A-Za-z0-9.-]+(:[0-9]{1,5})?/[A-Za-z0-9_]+\\?(.+)");
  // pgjdbc는 URL 속성을 DriverManager 속성(파일의 user·password)보다 우선하므로 쿼리는 TLS 두 키만 받는다(04 "DB 연결":
  // verify-full)
  static final String SSL_MODE = "sslmode";
  static final String SSL_ROOT_CERT = "sslrootcert";
  static final String REQUIRED_SSL_MODE = "verify-full";
  private static final Pattern ROOT_CERT_PATH = Pattern.compile("(/[A-Za-z0-9_][A-Za-z0-9._-]*)+");
  // #185 bootstrap_roles.sql과 같은 규칙: 소문자 PostgreSQL 식별자, pg_ 접두어 금지
  private static final Pattern ROLE_NAME = Pattern.compile("(?!pg_)[a-z_][a-z0-9_]{0,62}");

  private final Path directory;

  public MigrationSettings() {
    this(DEFAULT_DIRECTORY);
  }

  public MigrationSettings(Path directory) {
    this.directory = directory;
  }

  /** 검증된 값. toString은 값을 보이지 않는다. */
  public record Values(String url, String username, String password, String ownerRole) {
    @Override
    public String toString() {
      return "Values[<redacted>]";
    }
  }

  public Values load() {
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException("Migration 설정 디렉터리가 없다");
    }
    Map<String, String> values = new HashMap<>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
      for (Path entry : entries) {
        String name = entry.getFileName().toString();
        if (!NAMES.contains(name)) {
          throw new IllegalStateException("Migration 설정 디렉터리에 허용하지 않는 항목이 있다");
        }
        if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
          throw new IllegalStateException("Migration 설정 파일이 일반 파일이 아니다: " + name);
        }
        values.put(name, read(entry, name));
      }
    } catch (AccessDeniedException exception) {
      throw new IllegalStateException("Migration 설정 디렉터리를 읽을 권한이 없다");
    } catch (IOException exception) {
      throw new IllegalStateException("Migration 설정 디렉터리를 읽지 못했다");
    }
    for (String name : NAMES) {
      String value = values.get(name);
      if (value == null) {
        throw new IllegalStateException("필수 Migration 설정이 없다: " + name);
      }
      if (value.isBlank()) {
        throw new IllegalStateException("필수 Migration 설정이 비어 있다: " + name);
      }
    }
    validateUrl(values.get(URL));
    if (!ROLE_NAME.matcher(values.get(OWNER_ROLE)).matches()) {
      throw new IllegalStateException("app.migration.owner-role 식별자 형식이 올바르지 않다");
    }
    return new Values(
        values.get(URL), values.get(USERNAME), values.get(PASSWORD), values.get(OWNER_ROLE));
  }

  /**
   * 주소는 {@code jdbc:postgresql://<host>[:port]/<db>?sslmode=verify-full&sslrootcert=<절대 경로>} 하나의
   * 형태만 받는다.
   *
   * <p>pgjdbc는 쿼리 값을 URL 디코딩하고 같은 키는 마지막 값을 쓰며 빈 토큰은 건너뛴다. 그래서 인코딩 문자({@code %}·{@code +}), 빈 토큰,
   * {@code =} 없는 토큰, 중복 키, 목록 밖 키({@code user}·{@code password}·{@code options}·{@code
   * socketFactory}·{@code sslfactory}·{@code service} 등)를 모두 거절한다. 인증서는 실행 사용자가 읽을 수 있는 일반 파일이어야
   * 한다. 메시지에 주소를 넣지 않는다.
   */
  static void validateUrl(String url) {
    Matcher matcher = JDBC_URL.matcher(url);
    if (!matcher.matches()) {
      throw new IllegalStateException("Migration 데이터베이스 주소 형식이 올바르지 않다");
    }
    String query = matcher.group(2);
    if (query.contains("%") || query.contains("+")) {
      throw new IllegalStateException("Migration 데이터베이스 주소에 인코딩된 문자를 쓰지 않는다");
    }
    Map<String, String> parameters = new HashMap<>();
    for (String token : query.split("&", -1)) {
      int separator = token.indexOf('=');
      if (separator <= 0 || separator == token.length() - 1) {
        throw new IllegalStateException("Migration 데이터베이스 주소의 연결 속성 형식이 올바르지 않다");
      }
      String key = token.substring(0, separator);
      if (!key.equals(SSL_MODE) && !key.equals(SSL_ROOT_CERT)) {
        throw new IllegalStateException("Migration 데이터베이스 주소에 허용하지 않는 연결 속성이 있다");
      }
      if (parameters.put(key, token.substring(separator + 1)) != null) {
        throw new IllegalStateException("Migration 데이터베이스 주소의 연결 속성이 중복됐다: " + key);
      }
    }
    if (!REQUIRED_SSL_MODE.equals(parameters.get(SSL_MODE))) {
      throw new IllegalStateException("Migration DB 연결은 sslmode=verify-full이어야 한다");
    }
    String rootCert = parameters.get(SSL_ROOT_CERT);
    if (rootCert == null
        || !ROOT_CERT_PATH.matcher(rootCert).matches()
        || rootCert.contains("/../")
        || rootCert.contains("/./")
        || rootCert.endsWith("/..")
        || rootCert.endsWith("/.")) {
      throw new IllegalStateException("Migration DB 연결의 sslrootcert는 절대 경로여야 한다");
    }
    Path certificate = Path.of(rootCert);
    if (!Files.isRegularFile(certificate) || !Files.isReadable(certificate)) {
      throw new IllegalStateException("Migration DB 연결의 sslrootcert 파일을 읽을 수 없다");
    }
  }

  private static String read(Path entry, String name) {
    try (SeekableByteChannel channel = Files.newByteChannel(entry, READ_NO_FOLLOW)) {
      ByteBuffer buffer = ByteBuffer.allocate(MAX_BYTES + 1);
      while (buffer.hasRemaining() && channel.read(buffer) > 0) {
        // 제한 + 1바이트까지만 읽는다
      }
      if (buffer.position() > MAX_BYTES) {
        throw new IllegalStateException("Migration 설정 파일이 너무 크다: " + name);
      }
      buffer.flip();
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(buffer)
              .toString();
      if (text.endsWith("\r\n")) {
        text = text.substring(0, text.length() - 2);
      } else if (text.endsWith("\n")) {
        text = text.substring(0, text.length() - 1);
      }
      if (text.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
        throw new IllegalStateException("Migration 설정 값에 제어 문자가 있다: " + name);
      }
      return text;
    } catch (CharacterCodingException exception) {
      throw new IllegalStateException("Migration 설정 파일이 UTF-8이 아니다: " + name);
    } catch (AccessDeniedException exception) {
      throw new IllegalStateException("Migration 설정 파일을 읽을 권한이 없다: " + name);
    } catch (IOException exception) {
      throw new IllegalStateException("Migration 설정 파일을 읽지 못했다: " + name);
    }
  }
}
