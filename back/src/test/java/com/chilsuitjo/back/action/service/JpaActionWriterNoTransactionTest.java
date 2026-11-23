package com.chilsuitjo.back.action.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.rule.ActionType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;

/** 행동 기록은 pipeline의 트랜잭션 안에서만 쓴다. 이 테스트는 트랜잭션을 열지 않으므로 DB에 아무것도 남기지 않는다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties =
        "spring.datasource.hikari.connection-init-sql=SET search_path TO state, record, log, public")
class JpaActionWriterNoTransactionTest {

  @Autowired private ActionWriter writer;

  @Test
  void 트랜잭션_밖에서는_쓰지_않는다() {
    ActionCommand command =
        new ActionCommand(
            UUID.randomUUID(),
            Actor.server(),
            ActionType.AUTOCLOSE,
            "no-tx",
            null,
            null,
            null,
            null,
            null);

    assertThatThrownBy(() ->
            writer.write(command, UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.MICROS)))
        .isInstanceOf(IllegalTransactionStateException.class);
  }
}
