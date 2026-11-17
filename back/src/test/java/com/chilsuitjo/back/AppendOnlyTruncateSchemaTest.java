package com.chilsuitjo.back;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;

/** 운영 DB가 아닌 Testcontainers의 트랜잭션 안에서 거절되는 경로만 검사한다. */
class AppendOnlyTruncateSchemaTest extends SchemaTestSupport {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "record.action",
        "record.member_acknowledgement",
        "state.sms_opt_out",
        "record.repair_content",
        "record.cause_note",
        "record.progress_note",
        "record.payment_version",
        "record.proposal",
        "record.proposal_line",
        "record.inspection_item_version",
        "record.settlement_transfer_version",
        "record.photo",
        "log.deletion_log"
      })
  void everyProtectedTableHasEnabledBeforeTruncateStatementTrigger(String table) {
    assertTrue(
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                """
                SELECT EXISTS (
                  SELECT 1 FROM pg_trigger
                  WHERE tgrelid = ?::regclass AND NOT tgisinternal
                    AND tgenabled = 'O' AND tgtype = 34
                    AND tgfoid = 'record.forbid_change()'::regprocedure
                )
                """,
                Boolean.class,
                table)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"action", "sms_opt_out", "deletion_log"})
  void rejectsTruncateEvenWithoutRowsAndWithPurgeFlag(String table) {
    jdbc.execute("SET LOCAL app.purge = 'on'");
    var error =
        assertThrows(DataAccessException.class, () -> jdbc.execute("TRUNCATE TABLE " + table));
    assertTrue(error.getMessage().contains("TRUNCATE on " + table + " is not allowed"));
  }
}
