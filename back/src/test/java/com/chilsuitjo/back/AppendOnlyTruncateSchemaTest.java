package com.chilsuitjo.back;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;

/** 운영 DB가 아닌 Testcontainers의 트랜잭션 안에서 거절되는 경로만 검사한다. */
class AppendOnlyTruncateSchemaTest extends SchemaTestSupport {
  @ParameterizedTest
  @ValueSource(strings = {"action", "sms_opt_out", "deletion_log"})
  void rejectsTruncateEvenWithoutRowsAndWithPurgeFlag(String table) {
    jdbc.execute("SET LOCAL app.purge = 'on'");
    var error =
        assertThrows(DataAccessException.class, () -> jdbc.execute("TRUNCATE TABLE " + table));
    assertTrue(error.getMessage().contains("TRUNCATE on " + table + " is not allowed"));
  }
}
