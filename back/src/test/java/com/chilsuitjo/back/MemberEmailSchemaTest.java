package com.chilsuitjo.back;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class MemberEmailSchemaTest extends SchemaTestSupport {
  @Test
  void rejectsCaseInsensitiveDuplicate() {
    member("A@x.com");
    var error = assertThrows(DataIntegrityViolationException.class, () -> member("a@x.com"));
    assertTrue(error.getMessage().contains("ux_member_email_lower"));
  }

  @Test
  void allowsDifferentEmailsAndMultipleNulls() {
    member("a@x.com");
    member("b@x.com");
    member(null);
    member(null);
  }

  private void member(String email) {
    jdbc.update(
        """
        INSERT INTO member (id, email, status, created_at, version)
        VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, 0)
        """,
        UUID.randomUUID(),
        email);
  }
}
