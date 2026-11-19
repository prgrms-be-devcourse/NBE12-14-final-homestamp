# ActionPipeline 사용 안내

내 도메인에서 "상태를 바꾸는 요청"을 만들 때 따라 하는 안내서예요. **규칙의 원본은 `rules/09-프로젝트-구조.md` 3-1**이고, 여기에는 규칙을 다시 적지 않고 "어떻게 쓰나"만 적어요. 둘이 다르면 09가 맞고, 이 안내서를 고쳐요.

| 더 읽을 곳 | 무엇 |
|---|---|
| `rules/09-프로젝트-구조.md` 3-1 | 계약, 타입 표, pipeline이 지키는 순서 1~9, 트랜잭션 경계 |
| `rules/06-요구사항-명세.md` 4-0 | `Idempotency-Key`, 다시 온 요청의 응답 |
| `rules/06-요구사항-명세.md` 4-10 | 호실 단위 거절(`SETTLED`·`ROOM_ENDED`·`JOINED_RECORD`·`SHARING_STOPPED`)의 순서 |
| `rules/05-ERD-초안.md` 2-3 | 행동 종류 목록(`ActionType`)과 `action` 테이블 |
| 코드 `back/src/test/java/.../action/ActionPipelineFixtureTest.java` | 이 안내서의 예시가 실제로 도는 테스트 |

---

## 1. 한 줄로

**행동 기록을 남기는 요청은 모두 `ActionPipeline`에 맡겨요.** 내 도메인은 "상태 값·규칙·저장" 세 가지만 만들고, 잠금·같은 요청 다시 오기(멱등)·호실 단위 거절·행동 기록·트랜잭션은 pipeline이 해요.

```mermaid
sequenceDiagram
    participant C as 내 Controller
    participant S as 내 Service
    participant P as ActionPipeline
    participant G as RoomGateReader (room)
    participant St as 내 StateStore
    participant R as 내 Transitions
    C->>S: 요청 + Idempotency-Key
    S->>P: create / execute / executeAll
    P->>G: 1. 호실 잠그고 읽기
    P->>P: 2. 같은 키면 처음 결과 돌려주고 끝
    P->>P: 3. 호실 단위 거절 (정산 완료 등)
    P->>St: 5. load (지금 상태)
    P->>R: 6. apply (다음 상태, 규칙 위반이면 예외)
    P->>St: 7. save
    P->>P: 8. 행동 기록 (room_seq, hash)
    P-->>S: 결과 (대상 ID, replayed)
    S-->>C: 결과
```

## 2. 언제 쓰나

| 쓴다 | 쓰지 않는다 |
|---|---|
| 06 4-0에서 `Idempotency-Key`가 필수인 요청(행동 기록을 남기는 요청) | 로그인, 인증 문자, 업로드 표, 사진 주소, 알림 읽음 |
| 하자 등록, 응답, 초대, 지불 기록, 점검 항목, 정산 … | 회원 신원 칸 바꾸기(05 0장 신원 예외), 조회만 하는 API |

헷갈리면 05 2-3 행동 종류 표에 그 행동이 있는지 보세요. 있으면 pipeline이에요.

## 3. 내 도메인에 만들 것

이름은 09 4장을 따라요. `Xxx`에 도메인 이름을 넣어요(예: `Repair`). 아래 수리 코드는 REP-1을 만들기 전에 **모양을 보여 주는 예시**라 실제 `RepairState`와 다를 수 있어요. 그대로 도는 코드는 `ActionPipelineFixtureTest`예요.

| 만들 것 | 위치 | 하는 일 | 지킬 것 |
|---|---|---|---|
| `XxxState` | `rule/` | `apply`가 다루는 상태 값 | 불변 `record` |
| `XxxTransitions` | `rule/` | `Transitions<XxxState>` 구현. 지금 상태 + 행동 → 다음 상태 | **순수 함수**. Repository·Clock·다른 서비스를 부르지 않아요. 시각은 `action.createdAt()` |
| `XxxStateStore` | `repository/` | `StateStore<XxxState>` 구현. 엔티티 ↔ 상태 값 | 규칙을 검사하지 않아요. 다른 도메인 값은 공개 서비스·포트로 읽기만 |
| `XxxService` | `service/` | 요청을 `ActionCommand`로 바꿔 pipeline에 넘김 | **`@Transactional`을 두지 않아요** |
| `XxxController` | `controller/` | `Idempotency-Key`를 받아 서비스로, 다시 온 요청이면 헤더 | 06 4-0 |

### 3-1. 상태 값과 규칙

```java
// repair/rule/RepairState.java
public record RepairState(RepairStatus status, AckState defectAck) {}

// repair/rule/RepairTransitions.java
@Component
public class RepairTransitions implements Transitions<RepairState> {

  @Override
  public RepairState initial() {
    return new RepairState(null, AckState.PENDING); // 아직 없는 기록
  }

  @Override
  public RepairState apply(RepairState now, ActionEntry action) {
    return switch (action.type()) {
      case REGISTER -> new RepairState(RepairStatus.NOT_STARTED, AckState.PENDING);
      case RESPOND_DEFECT -> {
        RespondPayload in = action.payloadAs(RespondPayload.class); // payload는 내 record로 바꿔 읽어요
        yield new RepairState(now.status(), AckState.valueOf(in.answer().name()));
      }
      // 내 영역이 아닌 행동이나 지금 상태에서 안 되는 행동은 예외 → 저장도 기록도 남지 않아요
      default -> throw new BusinessException(ErrorCode.INVALID_STATE);
    };
  }
}
```

`apply`에서 **보지 않는 것**: 정산 완료·거주 종료·합류 전 기록·내 공유 중단. pipeline이 3단계에서 먼저 막아요(09 3-1 순서 3).
`apply`에서 **보는 것**: 기록 단위 규칙(`NOT_COUNTERPART_SIDE`, `ACK_FINAL` …), 그리고 행동마다 결과가 다른 호실 사실(예: 상대 공유 중단에 따른 정산 끝내기 막기). 뒤의 것은 `load`가 상태 값에 담아 줘요(09 3-1 "계약").

### 3-2. 저장

```java
// repair/repository/RepairStateStore.java
@Component
class RepairStateStore implements StateStore<RepairState> {

  private final RepairCaseRepository cases;

  @Override
  public RepairState load(ActionCommand command) {
    if (command.target() == null) {
      return new RepairTransitions().initial(); // 새로 만드는 행동이면 빈 상태
    }
    RepairCase row = cases.findById(command.target().id()).orElseThrow(...);
    return new RepairState(row.getStatus(), row.getDefectAck());
  }

  @Override
  public SavedState save(ActionCommand command, RepairState next) {
    if (command.target() == null) { // 새 기록
      RepairCase created = cases.save(RepairCase.create(command.roomRecordId(), next, ...));
      return SavedState.created(ActionTarget.repairCase(created.getId())); // 행동 기록의 대상이 돼요
    }
    cases.findById(command.target().id()).orElseThrow().apply(next);
    return SavedState.unchanged();
  }
}
```

- `load`는 호실을 잠근 **뒤**에 불려서, 여기서 읽은 값은 최신이에요.
- 링크·응답 요청·문자처럼 행동이 아닌 딸린 일은 `save` 안에서 주인 서비스를 불러요(같은 트랜잭션).
- 서버 기록이 하나 더 필요하면 `ActionFollowUps.add(Actor.server(), 종류, 대상, payload)`를 `save`에서 불러요. pipeline을 다시 부르지 않아요.
- 이 요청의 시각이 필요하면(응답 요청의 기한 시작 등) `Clock`을 다시 읽지 말고 `ActionRequestContext.at()`을 써요. 행동 기록의 `created_at`과 같은 값이에요. `requestId()`도 있어요. 테스트에서는 `fx.requestContext()`를 넣어요.

### 3-3. 서비스

```java
// repair/service/RepairService.java
@Service
public class RepairService {

  private final ActionPipeline pipeline;
  private final RepairStateStore store;
  private final RepairTransitions rules;

  // @Transactional 없음: 트랜잭션은 pipeline이 열어요
  public RegisterResult register(UUID roomRecordId, UUID memberId, String idempotencyKey, RegisterRequest req) {
    PipelineResult<RepairState> result = pipeline.create(
        new ActionCommand(roomRecordId, Actor.member(memberId), ActionType.REGISTER, idempotencyKey,
            null,              // 대상: 새로 만드는 행동이라 비워요
            null,              // seenVersionNo
            content(req),      // DigestInput, 행동 기록의 내용 지문 재료
            null,              // reason
            Map.of()),         // payload
        store, rules);
    return new RegisterResult(result.action().target().id(), result.action().replayed());
  }
}
```

### 3-4. 컨트롤러

```java
@PostMapping("/api/rooms/{roomId}/repair-cases")
public ResponseEntity<CreatedResponse> register(
    @PathVariable UUID roomId,
    @RequestHeader("Idempotency-Key") String idempotencyKey,
    @RequestBody RegisterRequest req) {
  RegisterResult r = repairService.register(roomId, currentMemberId(), idempotencyKey, req);
  ResponseEntity.BodyBuilder res = ResponseEntity.status(HttpStatus.CREATED);
  if (r.replayed()) {
    res.header("Idempotent-Replayed", "true"); // 다시 온 요청도 처음과 같은 상태 코드·모양(06 4-0)
  }
  return res.body(new CreatedResponse(r.id()));
}
```

회원 ID를 꺼내는 부분(`currentMemberId()`)은 보안 뼈대(#80)에 맞춰요.

## 4. 어느 메서드를 쓰나

| 하려는 것 | 쓸 것 | 예 |
|---|---|---|
| 새 기록 하나 만들기 | `pipeline.create(command, store, rules)`. `command.target()`은 `null` | 하자 등록 `REGISTER` |
| 있는 기록에 행동 하나 | `pipeline.execute(command, store, rules)` | 하자 응답 `RESPOND_DEFECT` |
| 한 요청에 행동 여럿 | `pipeline.executeAll(ActionBatch.of(호실, 주체, 키) .step(...) .record(...))` | 초대 [동의하고 보여 주기] = `SHARE_CONSENT` + `INVITE` |
| 여러 행동 중 새 기록 만드는 단계 | `.creating(종류, store, rules)` | |
| 상태 칸이 없는 행동(기록만) | `.record(종류, payload)` | `SHARE_CONSENT` |
| 상태 칸은 없지만 대상이 있는 행동 | `.record(종류, done -> 대상, payload)` | `LINK_VIEW`(링크), `CONFIRM_ALL`(점검) |
| 앞 단계가 만든 ID를 다음 단계에 | `.step(종류, done -> ActionTarget.xxx(done.get(0).target().id()), store, rules)` | |
| 조건이 맞을 때만 하는 단계 | `ActionStep.of(...).when(now -> 조건)` → `.step(그 단계)`. 거짓이면 기록 없이 건너뜀 | 정산이 없을 때만 `OPEN_SETTLEMENT` |
| `seenVersionNo`·`content`·`reason`까지 주는 단계 | `ActionStep.of(...).withSeenVersionNo(..).withContent(..).withReason(..)` | |
| 호실 만들기 | `ActionBatch.newRoom(Actor.member(id).withSide(side), 키)` | `CREATE_ROOM` (09 3-1 예시) |
| 회원이 아닌 주체 | `Actor.link(linkId, side)` / `Actor.server()` / `Actor.ops(담당)` | 링크 열람, 다시 알림, 운영 명령 |

- `Actor.member(id)`의 측(TENANT/LANDLORD)은 넣지 않아요. pipeline이 `RoomGate`에서 채워요(호실 만들기만 예외).
- `server`·`ops`는 호실 단위 거절을 건너뛰어요.
- `link`는 호실 단위 거절을 거쳐요. 링크 열람은 호실이 정산 완료·합류 전 기록이면 link가 pipeline을 부르기 전에 `LINK_INVALID`로 돌려줘요(호실 상태를 드러내지 않게, 06 LINK-2, #83). 문자 그만 받기(`OPT_OUT`)는 호실 상태로 막히지 않아요.
- 결과: `create`·`execute`는 `PipelineResult`(`state()` 저장 뒤 상태, `action()` 행동 결과), `executeAll`은 `BatchResult`(`actions()` 행동마다, `first()` 첫 행동). 다시 온 요청이면 `replayed()`가 참이고 `target()`에 처음 만든 ID가 있어요.

## 5. 테스트 쓰기

### 5-1. 규칙 테스트: pipeline 없이

`XxxTransitions`는 순수 함수라 그냥 부르면 돼요. 커버리지 대상이고, 03 상태 규칙과 06 시나리오는 대부분 여기서 테스트해요.

```java
@Test
void 확인을_마친_하자에는_다시_응답할_수_없다() {
  RepairState acked = new RepairState(RepairStatus.NOT_STARTED, AckState.ACK);
  assertThatThrownBy(() -> rules.apply(acked, entry(ActionType.RESPOND_DEFECT, Map.of("answer", "ACK"))))
      .hasMessage(ErrorCode.ACK_FINAL.name());
}
```

### 5-2. 서비스 테스트: `ActionPipelineFixture`

`ActionPipeline` 생성자와 행동 기록 저장 가짜는 `action` 안에서만 보여서, 다른 도메인은 테스트 준비물 `ActionPipelineFixture`(`back/src/test/java/.../action/service/`)로 만들어요. Spring·DB 없이 진짜 pipeline이 돌아요.

```java
ActionPipelineFixture fx = new ActionPipelineFixture();
fx.gates().member(roomId, tenantId, PartySide.TENANT);   // 이 회원은 이 호실의 세입자, 막히는 것 없음

RepairService service = new RepairService(fx.pipeline(), store, rules);
service.register(roomId, tenantId, "k1", req);

assertThat(fx.actions().findByRoom(roomId))
    .extracting(ActionEntry::type)
    .containsExactly(ActionType.REGISTER);
```

| 준비물 | 하는 일 |
|---|---|
| `fx.pipeline()` | 서비스에 넣을 pipeline |
| `fx.gates()` | 호실 상태 정하기 (아래 표) |
| `fx.actions()` | 남은 행동 읽기(`findByRoom`, `findByTarget`). 실패해서 되돌린 요청의 행동은 남지 않아요 |
| `fx.followUps()` | `save`에서 딸린 행동을 남길 때 저장 구현에 넣을 것 |
| `fx.rollbacks()` | 되돌린 트랜잭션 수 |
| `new ActionPipelineFixture(clock)` | 기한 계산처럼 시각이 중요할 때 |

호실 상태는 `RoomGate(호실, 측, settled, joinedAway, mySharingStopped, ended)`로 넣어요.

| 보고 싶은 상황 | 넣는 값 | pipeline 결과 |
|---|---|---|
| 거주 중, 막히는 것 없음 | `fx.gates().member(room, memberId, side)` | 통과 |
| 당사자가 아님 | 아무것도 넣지 않음 | `NOT_FOUND` |
| 정산 완료 | `put(memberId, new RoomGate(room, side, true, false, false, true))` | `SETTLED` (예외: `ActionType`에 `AFTER_SETTLED` 표시) |
| 거주 종료 | `new RoomGate(room, side, false, false, false, true)` | `REGISTER`만 `ROOM_ENDED` |
| 합류 전 기록 | `new RoomGate(room, side, false, true, false, false)` | `JOINED_RECORD` (예외: `ON_JOINED`) |
| 내가 공유를 그만둠 | `new RoomGate(room, side, false, false, true, false)` | `SHARING_STOPPED` (예외: `WHILE_SHARING_STOPPED`) |
| 서버·운영 행동 | `fx.gates().putRoom(new RoomGate(room, null, …))` | 호실 단위 거절을 건너뜀 |

어떤 행동에 어떤 표시가 있는지는 `ActionType`(09 3-1 `ActionType` 줄)을 보세요.

### 5-3. DB까지 보는 테스트

엔티티 매핑·제약을 보려면 `@SpringBootTest` + Testcontainers예요(Docker 필요). 이때 `FakeRoomGateReader`는 빈이라 주입받아 `member(...)`·`put(...)`으로 호실 상태를 넣어요.

## 6. 자주 하는 실수

| 실수 | 무슨 일이 생기나 | 고치는 법 |
|---|---|---|
| 서비스에 `@Transactional` | 실행할 때 `IllegalStateException` "pipeline 바깥에 트랜잭션을 두지 않아요" | 지우기. 트랜잭션은 pipeline이 열어요 |
| 새 기록인데 `execute`로 열고 `save`가 새 대상을 돌려줌 | `IllegalStateException` "새 기록을 만드는 행동은 ActionStep.creating·ActionPipeline.create로" | `create` 또는 `.creating(...)` |
| `create`에 대상을 줌 | `IllegalArgumentException` "새 기록을 만드는 행동에는 대상을 주지 않아요" | `target`을 `null`로 |
| 부모 대상을 주고(`step(종류, done -> 부모, …)`) `save`가 새 자식 기록을 돌려줌 | `IllegalStateException` "save가 물어본 대상과 다른 기록을 돌려줬어요". 그냥 두면 같은 요청이 다시 올 때 `IDEMPOTENCY_KEY_REUSED`가 돼요 | 새 기록은 `.creating(...)`으로. 부모 ID는 `payload`에 |
| `creating` 단계인데 `save`가 `SavedState.unchanged()` | `IllegalStateException` "save가 만든 기록을 돌려주지 않았어요" | `SavedState.created(ActionTarget.xxx(id))` |
| `apply`에서 Repository·`Clock.now()` 부르기 | 다시 계산할 때(REP-10) 결과가 달라져요 | 시각은 `action.createdAt()`, 필요한 값은 `load`가 상태에 담기 |
| `apply`에서 정산 완료·공유 중단 다시 검사 | 같은 검사가 두 군데 생겨 어긋나요 | pipeline에 맡기기(09 3-1 순서 3) |
| 도메인이 `RoomGate`를 만들어 넘기기 | 잠그기 전 옛 값으로 판단해요 | 만들지 않아요. 조회 화면의 당사자 확인은 `roomAccessService.require(...)` |
| pipeline 밖이나 단계가 끝난 뒤(`@TransactionalEventListener`·커밋 직전 `BEFORE_COMMIT`·비동기 리스너)에서 `ActionFollowUps.add` | `IllegalStateException` "딸린 행동은 pipeline 단계 안에서만". 요청 전체가 되돌려져요(500) | `save`나 `save` 안에서 동기로 도는 `@EventListener`에서만 |
| `save` 안에서 pipeline을 다시 부름 | `IllegalStateException` "pipeline 안에서 pipeline을 다시 부르지 않아요" | 같은 요청이면 `executeAll` 단계로, 서버 기록이면 `ActionFollowUps.add` |
| 화면 키가 비었거나 `#`가 들었거나 64자를 넘음 | `VALIDATION_FAILED`(400). `ActionBatch`를 만들 때 걸려서 트랜잭션 전에 끝나요 | 화면 키는 UUID. `<키>#2`는 pipeline이 붙여요 |
| pipeline 밖(커밋 뒤·비동기 리스너, 예약 작업)에서 `ActionRequestContext.at()` | `IllegalStateException` "요청 시각은 pipeline 안에서만" | `load`·`save`나 그 안에서 동기로 도는 `@EventListener`에서 읽기 |
| 같은 키로 다른 행동·대상 | `IDEMPOTENCY_KEY_REUSED`(422) | 버튼마다 새 키 |

## 7. 지금 가짜인 것

pipeline은 진짜이고, 안쪽 부품 두 개만 가짜예요. 진짜로 바뀌어도 **내 도메인 코드는 바뀌지 않아요**.

| 부품 | 지금 | 진짜로 바뀌면 | 누가 |
|---|---|---|---|
| 행동 기록 저장 `ActionWriter` | `FakeActionWriter`(메모리, 서버를 다시 켜면 사라짐) | `record.action`에 저장, `room_seq`·해시 체인 | B |
| 호실 잠금·읽기 `RoomGateReader` | `FakeRoomGateReader`(테스트가 넣은 값) | `room_record`를 `FOR UPDATE`로 잠그고 읽음 | A (ROOM-2) |

배포 서버에서 기능을 확인하려면(07 6장 끝 기준) 두 부품이 진짜여야 해요.
