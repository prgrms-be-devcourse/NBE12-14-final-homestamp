package com.chilsuitjo.back.link.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.link.entity.Link;
import com.chilsuitjo.back.link.rule.LinkInvalidReason;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.jpa.show-sql=true")
@ActiveProfiles("test")
class LinkConcurrencyTest {
  @Autowired private LinkRepository links;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void 실패_횟수_증가가_오래된_링크_무효화에_유실되지_않는다() throws Exception {
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    UUID id = UUID.randomUUID();
    UUID room = UUID.randomUUID();
    UUID party = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    Instant invalidatedAt = Instant.parse("2026-11-20T00:00:00Z");
    tx.executeWithoutResult(status -> {
      String region = jdbc.queryForObject("SELECT code FROM state.region LIMIT 1", String.class);
      jdbc.update(
          "INSERT INTO state.member (id, status, created_at, version) VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
          member);
      jdbc.update(
          "INSERT INTO state.room_record (id, region_code, alias, status, created_by_side, created_at, version) VALUES (?, ?, '테스트', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)",
          room,
          region);
      jdbc.update(
          "INSERT INTO state.party (id, room_record_id, side, status, created_at, version) VALUES (?, ?, 'TENANT', 'EMPTY', CURRENT_TIMESTAMP, 0)",
          party,
          room);
      jdbc.update(
          "INSERT INTO state.link (id, room_record_id, party_id, issued_by_member_id, kind, token_hash, created_at, version) VALUES (?, ?, ?, ?, 'INVITE', ?, CURRENT_TIMESTAMP, 0)",
          id,
          room,
          party,
          member,
          id.toString());
    });

    CountDownLatch loaded = new CountDownLatch(1);
    CountDownLatch incrementCommitted = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<?> invalidation =
          executor.submit(() ->
              tx.executeWithoutResult(status -> {
                Link stale = links.findById(id).orElseThrow();
                assertThat(stale.getFailedViewCount()).isZero();
                assertThat(stale.getVersion()).isZero();
                loaded.countDown();
                await(incrementCommitted);
                assertThat(entityManager.contains(stale)).isTrue();
                assertThat(stale.getFailedViewCount()).isZero();
                stale.invalidate(LinkInvalidReason.REINVITED, invalidatedAt);
                entityManager.flush();
              }));
      assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
      tx.executeWithoutResult(status -> assertThat(links.countFailedView(id)).isEqualTo(1));
      tx.executeWithoutResult(status -> {
        assertThat(
                jdbc.queryForObject(
                    "SELECT failed_view_count FROM state.link WHERE id = ?", Integer.class, id))
            .isEqualTo(1);
        assertThat(
                jdbc.queryForObject("SELECT version FROM state.link WHERE id = ?", Long.class, id))
            .isZero();
      });
      incrementCommitted.countDown();
      invalidation.get(10, TimeUnit.SECONDS);

      tx.executeWithoutResult(status -> {
        Link result = links.findById(id).orElseThrow();
        System.out.printf(
            "최종 상태: failedViewCount=%d, invalidatedAt=%s, reason=%s, version=%d%n",
            result.getFailedViewCount(),
            result.getInvalidatedAt(),
            result.getInvalidatedReason(),
            result.getVersion());
        assertThat(result.getInvalidatedAt()).isEqualTo(invalidatedAt);
        assertThat(result.getInvalidatedReason()).isEqualTo(LinkInvalidReason.REINVITED);
        assertThat(result.getVersion()).isEqualTo(1);
        assertThat(result.getFailedViewCount()).isEqualTo(1);
      });
    } finally {
      incrementCommitted.countDown();
      executor.shutdownNow();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("트랜잭션 순서 대기 시간 초과");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  @Test
  void 무효화_후_실패_횟수를_올려도_무효화_정보는_유지된다() {
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    UUID id = createLink(tx);
    Instant at = Instant.parse("2026-11-20T00:00:00Z");
    tx.executeWithoutResult(
        status -> links.findById(id).orElseThrow().invalidate(LinkInvalidReason.REINVITED, at));
    tx.executeWithoutResult(status -> assertThat(links.countFailedView(id)).isEqualTo(1));
    tx.executeWithoutResult(status -> {
      Link result = links.findById(id).orElseThrow();
      assertThat(result.getFailedViewCount()).isEqualTo(1);
      assertThat(result.getInvalidatedAt()).isEqualTo(at);
      assertThat(result.getInvalidatedReason()).isEqualTo(LinkInvalidReason.REINVITED);
      assertThat(result.getVersion()).isEqualTo(1);
    });
  }

  @Test
  void 반복_무효화는_최초_시각과_사유를_유지한다() {
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    UUID id = createLink(tx);
    Instant at = Instant.parse("2026-11-20T00:00:00Z");
    tx.executeWithoutResult(
        status -> links.findById(id).orElseThrow().invalidate(LinkInvalidReason.REINVITED, at));
    tx.executeWithoutResult(status ->
        links
            .findById(id)
            .orElseThrow()
            .invalidate(LinkInvalidReason.CONNECTED, at.plusSeconds(60)));
    tx.executeWithoutResult(status -> {
      Link result = links.findById(id).orElseThrow();
      assertThat(result.getInvalidatedAt()).isEqualTo(at);
      assertThat(result.getInvalidatedReason()).isEqualTo(LinkInvalidReason.REINVITED);
      assertThat(result.getVersion()).isEqualTo(1);
    });
  }

  @Test
  void 동시_무효화는_먼저_커밋된_사유를_지키고_나중_변경은_충돌한다() throws Exception {
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    UUID id = createLink(tx);
    Instant at = Instant.parse("2026-11-20T00:00:00Z");
    CountDownLatch loaded = new CountDownLatch(1);
    CountDownLatch committed = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<?> later =
          executor.submit(() ->
              tx.executeWithoutResult(status -> {
                Link stale = links.findById(id).orElseThrow();
                loaded.countDown();
                await(committed);
                stale.invalidate(LinkInvalidReason.CONNECTED, at.plusSeconds(60));
                entityManager.flush();
              }));
      assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
      tx.executeWithoutResult(
          status -> links.findById(id).orElseThrow().invalidate(LinkInvalidReason.REINVITED, at));
      committed.countDown();
      assertThatThrownBy(() -> later.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(jakarta.persistence.OptimisticLockException.class);
      tx.executeWithoutResult(status -> {
        Link result = links.findById(id).orElseThrow();
        assertThat(result.getInvalidatedAt()).isEqualTo(at);
        assertThat(result.getInvalidatedReason()).isEqualTo(LinkInvalidReason.REINVITED);
        assertThat(result.getVersion()).isEqualTo(1);
      });
    } finally {
      committed.countDown();
      executor.shutdownNow();
    }
  }

  private UUID createLink(TransactionTemplate tx) {
    UUID id = UUID.randomUUID();
    UUID room = UUID.randomUUID();
    UUID party = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    tx.executeWithoutResult(status -> {
      String region = jdbc.queryForObject("SELECT code FROM state.region LIMIT 1", String.class);
      jdbc.update(
          "INSERT INTO state.member (id, status, created_at, version) VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
          member);
      jdbc.update(
          "INSERT INTO state.room_record (id, region_code, alias, status, created_by_side, created_at, version) VALUES (?, ?, '테스트', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)",
          room,
          region);
      jdbc.update(
          "INSERT INTO state.party (id, room_record_id, side, status, created_at, version) VALUES (?, ?, 'TENANT', 'EMPTY', CURRENT_TIMESTAMP, 0)",
          party,
          room);
      jdbc.update(
          "INSERT INTO state.link (id, room_record_id, party_id, issued_by_member_id, kind, token_hash, created_at, version) VALUES (?, ?, ?, ?, 'INVITE', ?, CURRENT_TIMESTAMP, 0)",
          id,
          room,
          party,
          member,
          id.toString());
    });
    return id;
  }
}
