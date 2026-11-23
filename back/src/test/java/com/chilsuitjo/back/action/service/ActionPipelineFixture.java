package com.chilsuitjo.back.action.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * 다른 도메인의 단위 테스트에서 쓰는 pipeline 준비물. Spring·DB 없이 진짜 {@link ActionPipeline}에 가짜 부품(행동 기록 저장, 호실
 * 잠금·읽기, 트랜잭션)을 끼운다. {@code ActionPipeline} 생성자와 {@code FakeActionWriter}는 package-private이라 다른
 * 패키지에서는 이 준비물로 만든다. 쓰는 법은 docs/guides/action-pipeline.md "테스트 쓰기".
 *
 * <pre>{@code
 * ActionPipelineFixture fx = new ActionPipelineFixture();
 * fx.gates().member(roomId, tenantId, PartySide.TENANT); // 거주 중, 막히는 것 없음
 * RepairService service = new RepairService(fx.pipeline(), store, rules);
 *
 * service.register(...);
 *
 * assertThat(fx.actions().findByRoom(roomId)).extracting(ActionEntry::type)
 *     .containsExactly(ActionType.REGISTER);
 * }</pre>
 */
public final class ActionPipelineFixture {

  /** 기본 시계: 2026-11-17T00:00:00Z에 멈춰 있다. */
  public static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneOffset.UTC);

  private final FakeActionWriter writer = new FakeActionWriter();
  private final FakeRoomGateReader gates = new FakeRoomGateReader();
  private final ActionFollowUps followUps = new ActionFollowUps();
  private final ActionRequestContext requestContext = new ActionRequestContext();
  private final NoOpTransactionManager transactions = new NoOpTransactionManager();
  private final ActionPipeline pipeline;

  public ActionPipelineFixture() {
    this(FIXED_CLOCK);
  }

  /** 기한 계산처럼 시각이 중요한 테스트는 시계를 넘긴다. */
  public ActionPipelineFixture(Clock clock) {
    this.pipeline =
        new ActionPipeline(gates, writer, followUps, requestContext, transactions, clock);
  }

  /** 서비스에 넣을 pipeline. */
  public ActionPipeline pipeline() {
    return pipeline;
  }

  /** 호실 상태를 정하는 곳. 정산 완료·공유 중단 같은 상태는 {@code gates().put(회원 ID, new RoomGate(...))}로 넣는다. */
  public FakeRoomGateReader gates() {
    return gates;
  }

  /** 남은 행동 읽기({@code findByRoom}, {@code findByTarget}). 되돌려진 요청의 행동은 남지 않는다. */
  public ActionReader actions() {
    return writer;
  }

  /** {@code save}에서 딸린 행동을 남기는 창구. 서비스·저장 구현이 {@code ActionFollowUps}를 받으면 이것을 넣는다. */
  public ActionFollowUps followUps() {
    return followUps;
  }

  /**
   * {@code load}·{@code save}에서 이 요청의 행동 시각·{@code requestId}를 읽는 창구. 저장 구현이 {@code
   * ActionRequestContext}를 받으면 이것을 넣는다.
   */
  public ActionRequestContext requestContext() {
    return requestContext;
  }

  /** 지금까지 되돌린 트랜잭션 수. 실패한 요청이 정말 되돌려졌는지 볼 때 쓴다. */
  public int rollbacks() {
    return transactions.rollbacks;
  }

  /** DB 없이 트랜잭션 경계만 흉내 낸다(동기화 콜백은 실제처럼 돌아, 되돌리면 가짜 기록도 지워진다). */
  static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
    int rollbacks;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      rollbacks++;
    }
  }
}
