package com.chilsuitjo.back.global.config.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * {@code db/grants/grants.sql}을 JDBC로 실행한다(#156 계약). 한 연결·한 트랜잭션:
 *
 * <ol>
 *   <li>자동 커밋 끔 → {@code SET LOCAL ROLE "<owner>"} → session_user ≠ current_user = owner 확인
 *   <li>{@code pg_advisory_xact_lock(1752392040, 156)}: GRANT 실행끼리 직렬화
 *   <li>(선택) Flyway 이력 지문이 검증 때와 같은지 다시 확인: 검증과 GRANT 사이에 다른 Migration이 끼면 실패
 *   <li>{@code set_config('homestamp.grants.{app,purge,human}_role', ?, true)} → DO 블록
 *   <li>COMMIT. 어떤 예외든 ROLLBACK 후 다시 던진다. 끝나면 자동 커밋 설정을 되돌린다
 * </ol>
 *
 * <p>역할 이름은 {@link GrantRoles}가 검증한 값만 받는다. SQL에 직접 넣는 값은 따옴표로 감싼 owner 식별자 하나뿐이고 나머지는
 * PreparedStatement 인자다. 이 클래스는 Spring 빈이 아니며 {@code --apply-grants}에서만 부른다.
 */
public final class DatabaseGrants {

  public static final String RESOURCE = "db/grants/grants.sql";
  public static final int LOCK_CLASS = 1752392040;
  public static final int LOCK_OBJECT = 156;

  /**
   * Flyway 이력 지문. 행 수·마지막 순번·(순번, 버전, 체크섬, 성공 여부) 전체의 md5. {@code SET ROLE} 상태(Owner)에서만 읽을 수 있다.
   */
  static final String HISTORY_FINGERPRINT =
      "SELECT count(*) || ':' || coalesce(max(installed_rank), 0) || ':' || md5(coalesce(string_agg("
          + "installed_rank || '|' || coalesce(version, '-') || '|' || coalesce(checksum::text, '-')"
          + " || '|' || success::text, ',' ORDER BY installed_rank), '')) FROM public.flyway_schema_history";

  private DatabaseGrants() {}

  /** 실행 중에 본 접속 주체. 로그·테스트 확인용. */
  public record Observed(String sessionUser, String currentUser, int backendPid) {}

  public static String sql() {
    try (InputStream in = DatabaseGrants.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("GRANT SQL 리소스가 없다");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("GRANT SQL 리소스를 읽지 못했다");
    }
  }

  public static Observed apply(Connection connection, GrantRoles roles) throws SQLException {
    return apply(connection, roles, null);
  }

  /**
   * @param expectedHistory 검증 때 본 Flyway 이력 지문. null이면 확인하지 않는다(GRANT SQL만 따로 시험할 때)
   */
  public static Observed apply(Connection connection, GrantRoles roles, String expectedHistory)
      throws SQLException {
    String sql = sql();
    boolean autoCommit = connection.getAutoCommit();
    try (Statement statement = connection.createStatement()) {
      // 연결에 남아 있을 수 있는 세션 수준 SET ROLE(예: Flyway initSql)을 지우고 Migration 로그인으로 시작한다
      statement.execute("RESET ROLE");
    }
    connection.setAutoCommit(false);
    try {
      Observed observed = assumeOwner(connection, roles.owner());
      try (PreparedStatement statement =
          connection.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)")) {
        statement.setInt(1, LOCK_CLASS);
        statement.setInt(2, LOCK_OBJECT);
        statement.execute();
      }
      if (expectedHistory != null && !expectedHistory.equals(historyFingerprint(connection))) {
        throw new IllegalStateException(
            "Flyway 검증 뒤 Migration 이력이 바뀌었다(다른 배포와 겹침). GRANT를 실행하지 않는다");
      }
      setRoles(connection, roles.app(), roles.purge(), roles.human());
      try (Statement statement = connection.createStatement()) {
        statement.execute(sql);
      }
      connection.commit();
      return observed;
    } catch (SQLException | RuntimeException exception) {
      try {
        connection.rollback();
      } catch (SQLException rollbackFailure) {
        // 원래 실패를 가리지 않는다(연결이 끊겨도 서버는 커밋하지 않은 트랜잭션을 버린다)
        exception.addSuppressed(rollbackFailure);
      }
      throw exception;
    } finally {
      connection.setAutoCommit(autoCommit);
    }
  }

  /** 지금 트랜잭션(Owner로 전환한 상태)에서 Flyway 이력 지문을 읽는다. */
  static String historyFingerprint(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(HISTORY_FINGERPRINT)) {
      result.next();
      return result.getString(1);
    }
  }

  /** 자동 커밋을 끈 연결에서 Owner로 전환해 이력 지문을 읽고 트랜잭션을 끝낸다. */
  public static String readHistoryFingerprint(Connection connection, String owner)
      throws SQLException {
    boolean autoCommit = connection.getAutoCommit();
    try (Statement statement = connection.createStatement()) {
      statement.execute("RESET ROLE");
    }
    connection.setAutoCommit(false);
    try {
      assumeOwner(connection, owner);
      return historyFingerprint(connection);
    } finally {
      connection.rollback();
      connection.setAutoCommit(autoCommit);
    }
  }

  private static Observed assumeOwner(Connection connection, String owner) throws SQLException {
    if (!GrantRoles.ROLE_NAME.matcher(owner).matches()) {
      throw new IllegalStateException("GRANT 역할 이름 형식이 올바르지 않다");
    }
    Observed observed;
    try (Statement statement = connection.createStatement()) {
      // 검증된 식별자만 따옴표로 감싼다(정규식이 따옴표·대문자를 허용하지 않음)
      statement.execute("SET LOCAL ROLE \"" + owner + "\"");
      try (ResultSet result =
          statement.executeQuery("SELECT session_user, current_user, pg_backend_pid()")) {
        result.next();
        observed = new Observed(result.getString(1), result.getString(2), result.getInt(3));
      }
    }
    if (!observed.currentUser().equals(owner)
        || observed.sessionUser().equals(observed.currentUser())) {
      throw new IllegalStateException("GRANT는 Migration 로그인에서 Owner로 전환한 뒤에만 실행한다");
    }
    return observed;
  }

  private static void setRoles(Connection connection, String app, String purge, String human)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT set_config(?, ?, true)")) {
      String[][] settings = {
        {"homestamp.grants.app_role", app},
        {"homestamp.grants.purge_role", purge},
        {"homestamp.grants.human_role", human}
      };
      for (String[] setting : settings) {
        statement.setString(1, setting[0]);
        statement.setString(2, setting[1]);
        statement.execute();
      }
    }
  }
}
