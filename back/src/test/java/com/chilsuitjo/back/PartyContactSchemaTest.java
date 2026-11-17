package com.chilsuitjo.back;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PartyContactSchemaTest extends SchemaTestSupport {
  @ParameterizedTest
  @ValueSource(strings = {"NUMBER_ONLY", "INVITED"})
  void allowsRemovingOptedOutPhoneWithoutForcingAnUndecidedStatusTransition(String status) {
    UUID party = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO party (id, room_record_id, side, invite_phone, status, created_at, version)
        VALUES (?, ?, 'LANDLORD', '01012345678', ?, CURRENT_TIMESTAMP, 0)
        """,
        party,
        room(),
        status);
    jdbc.update("UPDATE party SET invite_phone = NULL, version = version + 1 WHERE id = ?", party);
    assertNull(
        jdbc.queryForObject("SELECT invite_phone FROM party WHERE id = ?", String.class, party));
  }
}
