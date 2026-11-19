# ERD 초안

> `06-요구사항-명세.md`의 기능·API가 저장하는 값과 `03-상태와-규칙.md`의 상태 기계 12개(A~L)·불변식 21개를 테이블과 제약으로 옮긴 **첫 초안**이에요. 테이블의 종류·뜻·제약과 **행동 종류 전체 목록(2-3)**은 이 문서에만 있고, 06 2장 추적표가 기능마다 여기를 가리켜요. **정확한 칸 이름·타입은 `back/src/main/resources/db/migration/`의 Flyway V 파일이 기준**이에요(실제 DB가 그 파일로 만들어져서). 칸을 더하거나 바꾸면 새 V 파일과 이 문서를 같은 PR에서 고쳐요.
> 데이터 모델의 원칙(왜 이렇게 나눴나)은 `04-인프라-아키텍처.md`의 「4-0. 데이터 모델 원칙」에 있어요. 결정의 이유는 `../decisions.md`.

---

## 0. 읽는 법

### 테이블 세 종류

| 표시 | 종류 | 수정 | 설명 |
|---|---|---|---|
| **A** | 행동 기록 | 안 됨 | 누가, 언제, 무엇을 눌렀나. 원본 |
| **B** | 사실 행 | 안 됨 | 사람이 입력한 내용. 고치면 이전 행을 가리키는 새 행(판) |
| **C** | 상태 행 | 상태·기한·version·"지금 판" 칸만 | 지금 어디까지 왔나. 행동 기록에서 다시 계산할 수 있는 사본. 그 밖의 칸은 만들 때 한 번만 쓴다 |

### DB 스키마와 권한

| 스키마 | 들어가는 테이블 | 앱 권한 |
|---|---|---|
| `record` | A 행동 기록과 B 사실 행(약관 확인 포함). 상태의 원본인 `action`도 여기 둔다 | SELECT·INSERT만 |
| `state` | C 상태 행과 고정 조회 데이터(`part_template`, `region`) | C는 필요한 DML, 고정 데이터는 SELECT만 |
| `log` | B 삭제 감사 `deletion_log` | INSERT만(기존 기록 조회는 삭제 작업 역할) |
| `public` | Flyway 이력 `flyway_schema_history` | 앱 접근 없음 |

V1은 테이블·FK·트리거·함수에 스키마를 명시한다. JPA 엔티티를 만들 때 `@Table(schema = "record"/"state"/"log")`를 명시한다. 스키마 분리는 A·B·C 분류를 바꾸지 않는다. `deletion_log`는 감사 목적의 B라 `log`에 두며, `member_acknowledgement`의 탈퇴 삭제만 제한된 함수로 허용한다. 운영 역할·기본 권한·예외 권한은 `../runbooks/DB-보관-삭제.md` 「역할 분리」를 따른다.

### 모든 테이블의 공통 칸

| 칸 | 내용 |
|---|---|
| `id` | UUID v7(일반 엔티티는 Hibernate가 저장할 때 생성, 앱·화면은 넣지 않음. 탈퇴 함수의 감사·아웃박스는 DB `uuidv7()`로 생성). **예외: 서로를 가리키는 머리·판 네 쌍**(`repair_case`↔`repair_content`, `inspection_item`↔`inspection_item_version`, `settlement_transfer`↔`settlement_transfer_version`, `payment`↔`payment_version`)은 앱이 UUID v7을 먼저 만들어 생성자로 넣는다. 머리의 `current_*_id`가 NOT NULL이고 판은 머리 ID가 필요해, 머리와 첫 판을 한 트랜잭션에 만들려면 두 ID를 저장 전에 알아야 한다(지연 FK는 참조 검사만 커밋까지 미룬다) |
| `room_record_id` | 호실 기록. 계정·템플릿·법정동(`region`)·삭제 기록·아웃박스·횟수 세기(`rate_counter`)를 뺀 모든 테이블. 보관 삭제와 탈퇴를 호실 기록 하나 기준으로 처리하려고. `in_app_notification`은 호실과 무관한 서비스 안내(번호 인증 해제 등)면 NULL |
| `created_at` | 서버 시각(`timestamptz`). 고정 데이터(`part_template`, `region`)와 `rate_counter`에는 없음 |
| `version` | C 테이블만. 낙관적 잠금. `rate_counter`와 `sms_opt_out`은 예외: 각각 원자적 증감과 행의 존재 여부로 상태를 관리한다 |

### 호실 경계와 삭제 제약

호실 소속 테이블 사이의 참조는 부모의 `UNIQUE(id, room_record_id)`와 자식의 `(parent_id, room_record_id)` 복합 FK로 묶는다. 현재·확인 판을 가리키는 지연 FK도 포함하며, 이 FK는 머리 ID까지 묶어(판의 `UNIQUE(id, 머리_id, room_record_id)`) 같은 호실의 다른 머리 판을 가리키지 못하게 한다. 같은 건 안에서만 이어지는 참조도 그 건까지 묶는다: 판·원인 설명의 `previous_id`, 머리의 현재·합의 제안(수리 건·정산 항목·보증금), 제안의 `counter_of_id`·`replaces_id`(대상 칸마다 FK), 진행 상황의 `answers_request_id`(같은 수리 건의 요청), 고친 내용의 `retry_of_id`(같은 `target_key`·`field`). `in_app_notification`은 응답 요청이 있으면 호실도 필수다(서비스 안내만 NULL 허용). 계정·고정 데이터 참조와 호실 합치기의 `joined_into_id`는 이 규칙의 대상이 아니다.

직접 소유 관계인 `room_record_id → room_record.id`는 `ON DELETE CASCADE`다. 호실끼리의 `joined_into_id`는 NO ACTION으로 남겨 다른 호실까지 지우지 않으며, 다른 호실이 합치기 대상으로 참조 중이면 삭제를 거절한다. 단, 보관 만료로 지울 때는 그 호실을 가리키는 합류 전 기록을 먼저 지운다(합류 전 기록은 쓴 사람의 탈퇴와 합류한 기록의 보관 종료 중 먼저 오는 때 지움, 03 B). `deletion_log`와 아웃박스는 호실 FK를 두지 않아 함께 지워지지 않는다.

A·B 테이블은 UPDATE와 TRUNCATE를 항상 거절한다(행 트리거 + BEFORE TRUNCATE 문장 트리거). DELETE는 **실행 역할이 테이블 소유자이고 `app.purge = on`일 때만** 통과한다. 플래그만 켠 앱 역할은 거절한다. 호실 통째 삭제는 PUBLIC 실행 권한을 제거한 `purge_room_record(uuid)` SECURITY DEFINER 함수로 모으고, 별도 삭제 작업 역할에만 EXECUTE를 준다. 함수 소유자는 보호 테이블 소유자와 같아야 하며 앱은 그 역할을 상속할 수 없다. 호출 전 모든 행동의 삭제 감사 기록이 원본과 일치해야 한다. 운영 권한과 호출 순서는 `../runbooks/DB-보관-삭제.md`를 따른다. 일부 비공개 초안만 지우는 함수는 아직 구현하지 않았다.

이 보호는 부모에서 시작한 연쇄 삭제에도 적용해야 한다. 앱에 `room_record` DELETE 권한이 잘못 부여돼도 purge 플래그만 켜서 A·B를 지울 수 없어야 한다. `room_record` 자체의 삭제도 소유자 권한과 purge 플래그가 함께 있을 때만 허용한다. V1의 부모 DELETE 트리거와 일반 역할 회귀 테스트로 검사한다.

호실 밖의 B인 `member_acknowledgement`는 탈퇴 처리 전용 삭제 경로를 둔다. 앱에는 직접 DELETE나 호실 삭제 함수 실행 권한을 주지 않고, 탈퇴한 회원의 확인 기록만 지우는 제한된 함수의 EXECUTE만 예외로 허용한다. 함수는 회원 행을 잠그고 `WITHDRAWN` 상태를 검사한 뒤, 확인 기록별 삭제 감사와 `AUDIT_WRITE` 아웃박스를 보존하고 삭제한다. 회원 가림·확인 기록 삭제·감사 보존은 같은 탈퇴 트랜잭션이며 실패하면 함께 롤백한다. `record.purge_member_acknowledgements(uuid, timestamptz)`로 처리하며 시각은 호출 서비스의 Clock에서 받는다.

### 원칙의 예외: 신원·연락처 칸

A·B·C 원칙은 **기록**을 지키려는 것이다. 사람의 신원·연락처는 기록이 아니라 개인정보라서 오히려 **고치고 지울 수 있어야** 한다(정정·삭제 권리, 탈퇴, 번호 변경, 수신 거부).

| 예외 칸 | 규칙 |
|---|---|
| `member`의 이메일·비밀번호 해시·이름·번호·설정, `party.invite_phone`, `refresh_token`, `phone_verification` | 고치고 지울 수 있다. 바꾼 사실은 행동 기록에 남기되 **값은 남기지 않는다**("초대 번호 변경", "이름 변경"). 기록 테이블은 이 칸들을 복사하지 않고 ID로만 가리킨다(불변식 1) |

전화번호는 국내 휴대폰 번호를 숫자만 있는 같은 형식으로 정규화한 뒤 저장·비교한다(예: `010-1234-5678` → `01012345678`). `member.phone`, `party.invite_phone`, 인증 대상, 수신 거부·발송 한도 HMAC 입력에 같은 정규화 규칙을 적용한다. 화면 표시의 하이픈과 저장 형식은 구분하며 정규화 함수·검증 위치는 구현하면서 정한다.

### 두 가지 반복 모양

이 두 모양만 익히면 나머지 테이블은 같은 방식으로 읽을 수 있어요.

**① 고칠 수 있는 것 = C 머리 + B 판**

```
repair_case (C)  current_content_id ──▶ repair_content (B) 판 #3 ──previous──▶ #2 ──▶ #1
```

수리 건, 지불, 점검 항목, 정산 송금이 이 모양이에요. 머리(C)는 두 곳을 가리켜요: **지금 모습**(가장 최근 판, `current_*_id`)과 **같이 확인한 모습**(상대가 확인한 판, `confirmed_*_id`). 같이 확인하기 전에 고치면 새 판을 만들고 두 가리킴을 함께 옮겨요(아직 확인 전이라 같이 확인한 모습은 비어 있음). 같이 확인한 뒤 고치면(무엇이든) 판을 바로 만들지 않고 **고친 내용(칸 + 새 값 + 기준 판)**과 확인 요청(기록당 하나)을 남겨요. 상대가 확인하면 **지금 도장 찍힌 판에 그 칸만 합쳐** 새 판을 만들고 두 가리킴을 함께 옮겨요(하나를 확인해도 다른 고친 내용이 딸려 가지 않게). 계산은 같이 확인한 모습(없으면 지금 모습)만 읽어요(03-상태 G).

**② 무엇에 대한 것인가 = 종류별 외래 키 + "정확히 하나" CHECK**

```sql
repair_case_id  uuid NULL REFERENCES repair_case,
payment_id      uuid NULL REFERENCES payment,
inspection_id   uuid NULL REFERENCES inspection,
...
CHECK (num_nonnulls(repair_case_id, payment_id, inspection_id, ...) = 1)
```

행동 기록, 사진, 제안, 응답 요청, 고친 내용이 이 모양이에요. DB가 대상이 실제로 있는지 검사해요.

---

## 1. 전체 그림

```mermaid
erDiagram
    member ||--o{ party : "연결"
    region ||--o{ room_record : "법정동"
    room_record ||--|{ party : "측 자리 2개"
    room_record ||--o{ room_part : "부위"
    part_template ||--o{ room_part : "복사"
    room_record ||--o{ link : ""
    room_record ||--o{ action : "행동 기록"

    room_record ||--o{ repair_case : ""
    repair_case ||--|{ repair_content : "판"
    repair_case ||--o{ cause_note : "원인 설명"
    repair_case ||--o{ progress_note : "진행 상황"
    repair_case ||--o{ payment : ""
    payment ||--|{ payment_version : "판"

    room_record ||--o{ inspection : "입주·퇴거 각 1"
    inspection ||--o{ inspection_item : ""
    inspection_item ||--|{ inspection_item_version : "판"

    room_record ||--o| settlement : "0 또는 1"
    settlement ||--o{ settlement_item : ""
    settlement ||--o{ settlement_transfer : "반환·추가"
    settlement_transfer ||--|{ settlement_transfer_version : "판"

    proposal ||--|{ proposal_line : ""
    repair_case ||--o{ proposal : "부담"
    settlement_item ||--o{ proposal : "금액·제외"
    settlement ||--o{ proposal : "보증금"

    response_request ||--o{ notice : "통지·재통지"
    notice ||--o{ outbox : ""

    room_record ||--o{ photo : "붙는 곳 여덟 중 하나"
    photo ||--o| photo_visibility : "숨김·지움"
    correction_request }o--|| room_record : ""
    member ||--o{ member_acknowledgement : "약관·안내·14세"
    member ||--o{ in_app_notification : ""
```


---

## 2. 테이블

### 2-1. 계정

| 테이블 | 종류 | 칸 | 제약·메모 |
|---|---|---|---|
| `member` 회원 | C | `email`, `password_hash`, `display_name`, `phone`(인증 번호), `phone_verified_at`, `sms_enabled`, `status`(ACTIVE/WITHDRAWN), `withdrawn_at` | `UNIQUE INDEX(lower(email)) WHERE email IS NOT NULL`, `UNIQUE(phone) WHERE phone IS NOT NULL`(불변식 8). 다른 계정이 같은 번호를 인증하면 이전 계정의 `phone`을 NULL로. 탈퇴하면 email·이름·번호·해시를 지우고(NULL) 행은 남김(행동 기록이 가리키므로) |
| `refresh_token` | C | `member_id`, `family_id`, `token_hash`, `device_label`, `expires_at`, `revoked_at`, `replaced_by_id` | `UNIQUE(token_hash)`. 폐기 뒤 10초 유예는 `revoked_at`과 `replaced_by_id`로(불변식 12) |
| `phone_verification` | C | `phone`, `purpose`(SIGNUP/RESET/FIND_ID/CHANGE), `code_hash`, `expires_at`, `attempt_count`, `verified_at`, `consumed_at`(인증 표를 쓴 시각) | 짧게 쓰고 지움(하루 뒤 정리). 횟수 제한은 여기서 셈. 인증 표(06 4-1)는 `purpose`가 같은 API에만 쓰고, 바꾸는 일(가입·재설정·번호 변경)에는 **한 번만**: 그 작업이 성공하는 트랜잭션에서 `UPDATE … SET consumed_at = now() WHERE id = ? AND consumed_at IS NULL`(0줄이면 `CODE_EXPIRED`). 아이디 찾기는 `consumed_at`을 채우지 않음(06 ACC-1f·5a). **같은 인증 진행 = (`phone`, `purpose`)**: 새 요청은 **번호 잠금**(`pg_advisory_xact_lock`, 번호 HMAC으로 만든 키, 목적과 상관없음) 안에서 다시 받기 간격(같은 번호·목적)과 하루 횟수(같은 번호, 목적 구분 없음, `app.zone` 날짜의 `created_at`으로 셈)를 확인하고 `sms-total`을 센 뒤(`rate_counter`), 같은 번호·목적의 인증 전(`verified_at IS NULL`) 이전 행의 `expires_at`을 지금으로 바꾸고 새 행을 만든다(동시 요청에도 유효한 행 하나, 목적이 다른 요청이 동시에 와도 하루 횟수를 넘지 않음, 06 ACC-1g·1h). **`CODE_REPLACED`**: 확인하려는 행보다 나중에 만든 같은 번호·목적의 행이 있으면(바뀐 행), 또는 넣은 번호가 맞지 않는데 같은 진행의 바뀐 이전 행 번호와 같으면 `attempt_count`를 올리지 않고 `CODE_REPLACED`로 답한다. 만료와 바뀜은 칸이 아니라 나중 행이 있는지로 가른다. 인증에 성공하면 같은 행의 `expires_at`을 인증 시각 + `verify.ticket.ttl`로 바꾼다(06 ACC-1l). 다시 받기 간격은 같은 번호·목적의 마지막 행 `created_at`으로 센다. 솔라피가 실패로 답한 발송의 행은 지워 하루 횟수·간격에 세지 않는다(06 ACC-1j). 칸은 늘리지 않는다 |
| `member_acknowledgement` 약관·안내 확인 기록(공유 동의는 여기가 아니라 행동 기록 `SHARE_CONSENT`) | B | `member_id`, `kind`(AGE_14_CONFIRMED/TERMS_AGREED/PRIVACY_NOTICE_ACK), `text_version`(예: `2026-11-12`), `agreed_at` | 문구는 판 번호가 붙은 정적 페이지. 내용이 바뀌면 새 판에 다시 확인. B 분류와 탈퇴 시 삭제를 유지한다. 회원 행은 가려 남기고 이 확인 기록은 0장의 탈퇴 전용 삭제 경로에서 지운다(호실 삭제 함수의 대상이 아님). **기록 공유 동의는 여기 두지 않고 `action`의 `SHARE_CONSENT`(payload에 문구 판)로**: 호실 기록과 함께 보관·삭제되어 공유된 기록이 남는 동안 동의 증빙도 남는다 |
| `sms_opt_out` 수신 거부 | C | `phone_hmac`(PK), `created_at` | 현재 발송 금지 목록. 거부 사실은 `OPT_OUT` 행동에 남긴다. 번호 주인이 가입·번호 인증을 완료하는 트랜잭션에서 앱이 해당 행을 지우고 `deletion_log.phone_hmac`과 `AUDIT_WRITE` 아웃박스를 함께 보존한다. 인증 코드 발급·검증만으로는 해제하지 않는다. UPDATE 없이 추가·삭제하며 `version`은 두지 않는다 |

### 2-2. 호실과 참여

| 테이블 | 종류 | 칸 | 제약·메모 |
|---|---|---|---|
| `part_template` 부위 템플릿 | 고정 | `zone`, `name`, `sort_order`, `optional` | Flyway 기본 데이터, 6구역 28개 |
| `region` 법정동 | 고정 | `code`(법정동 코드 `char(10)`, PK), `sido`, `sigungu`(NULL 허용: 세종특별자치시는 시·군·구가 없음), `dong`, `active` | Flyway 기본 데이터(별도 V 파일): 행정표준코드관리시스템 법정동코드 전체자료에서 지금 있는 읍·면·동만(리·행정동 제외). 없어진 동은 지우지 않고 `active = false`(가리키는 호실 기록이 있을 수 있어서). 검색용 인덱스 `(dong)`, 단계 선택은 코드 앞자리(시·도 2자리, 시·군·구 5자리)로 |
| `room_record` 호실 기록 | C | `region_code`(`REFERENCES region(code)`, NOT NULL, 주소는 이 칸 하나. 이름은 `region`에서 읽음), `alias`, `move_in_date`, `status`(LIVING/ENDED/SETTLED), `created_by_side`, `counterpart_first_connected_at`, `ended_at`, `settled_at`, `autoclose_due_at`, `delete_due_at`, `inactivity_notice_due_at`(마지막 당사자 행동 시각은 이 행에 두지 않고 `action`의 `(room_record_id, created_at)` 인덱스로 계산. 모든 행동이 이 행의 version을 올리면 같은 호실의 다른 건끼리 충돌함) | `counterpart_first_connected_at`이 NULL이면 탈퇴 때 통째 삭제. **`joined_into_id`, `joined_at`**: 합류 전 기록이면 합류한 호실 기록을 가리킴(새 행동 없음, 예외는 `ERASE_PHOTO`, 쓴 사람만 읽기, 삭제는 그 기록을 따라감), `CHECK(joined_into_id <> id)`, 합류한 기록은 다시 합류할 수 없음(서비스 검사). `status`가 ENDED·SETTLED이면 `ended_at` 필수, SETTLED이면 `settled_at`도 필수(DB CHECK). 기한 칸은 사건이 생길 때 저장. `move_in_date`는 고치기가 반영될 때만 바뀜(같이 확인한 뒤 고친 값은 내가 고친 내용 `correction_request`에) |
| `party` 당사자(측 자리) | C | `side`(TENANT/LANDLORD), `member_id`, `invite_phone`, `status`(EMPTY/NUMBER_ONLY/INVITED/CONNECTED/SHARING_STOPPED/REJECTED/WITHDRAWN), `connected_at`, `sharing_stopped_at`(공유 그만두기 시각, 그 사람이 다시 공유하면 NULL) | `UNIQUE(room_record_id, side)`, `UNIQUE(room_record_id, member_id)`(불변식 13). 자기 번호 초대 금지는 서비스 검사. `invite_phone`은 문자를 보내야 해서 평문(RDS 저장소 암호화), 연결·수신 거부·탈퇴 때 NULL. CONNECTED·SHARING_STOPPED이면 `member_id` 필수, SHARING_STOPPED이면 `sharing_stopped_at` 필수(DB CHECK). 수신 중단 때 번호를 지울 수 있도록 NUMBER_ONLY·INVITED의 번호 필수 CHECK는 두지 않는다. 번호 삭제 뒤 자리 상태 전이는 A·D의 수신 중단 기능 구현에서 정하고 테스트한다(03 K, 06 NOTI-8a) |
| `room_part` 호실 부위 | C | `template_id`, `zone`, `name`, `sort_order`, `enabled`, `hidden_at` | 지우지 않고 숨기기만 |
| `link` 링크 | C | `kind`(INVITE/SETTLEMENT_INVITE), `party_id`(받는 자리), `repair_case_id` / `inspection_id`(공유한 기록, 없으면 호실 초대 화면), `token_hash`, `issued_by_member_id`, `invalidated_at`, `invalidated_reason`, `failed_view_count` | `UNIQUE(token_hash)`. `invalidated_at`과 `invalidated_reason`은 함께 NULL이거나 함께 존재한다. 무효 사유는 REINVITED/CONNECTED/RESIDENCE_ENDED/ISSUER_WITHDRAWN/SHARING_STOPPED/SETTLED(03 C). 처음 무효가 된 뒤 시각과 사유를 모두 바꾸지 못한다(불변식 7). SETTLEMENT_INVITE이면 `repair_case_id`·`inspection_id`는 모두 NULL(DB CHECK) |

### 2-3. 행동 기록

| 테이블 | 종류 | 칸 | 제약·메모 |
|---|---|---|---|
| `action` 행동 기록 | **A** | `actor_member_id`(서버 행동은 NULL), `actor_side`, `actor_key`(회원 ID 또는 `server`), `type`, `idempotency_key`, `request_id`(한 요청으로 남긴 행동을 묶는 값. 요청마다 서버가 UUID v7 하나를 만들어 그 요청의 모든 행동(딸린 서버 행동 포함)에 넣는다. FK 없음, 인덱스, V3), `room_seq`·`prev_hash`·`hash`(해시 체인, 호실마다, **NOT NULL, 첫 마이그레이션부터**), `content_digest`(당시 본 판의 내용·사진 지문을 묶은 SHA-256, bytea 32바이트, 내용 대상이 없으면 NULL), `seen_version_no`(확인할 때 본 모습의 번호 = 판 테이블의 `no`, 정산 끝내기 확인 `CONFIRM_FINISH`면 본 정산 모습 번호 `settlement.snapshot_no`, 06 `seenVersionNo`), payload에 `while_open_objection`(그 순간 보류·이견이 열려 있었으면 NEED_MORE/DISPUTE: 강행 표시), 대상 칸(`party_id`, `link_id`, `room_part_id`(부위 추가·숨김), `notice_id`(다시 보내기·전달 안 됨 표시), `repair_case_id`, `payment_id`, `inspection_id`, `inspection_item_id`, `settlement_id`, `settlement_item_id`, `settlement_transfer_id`, `proposal_id`, `response_request_id`, `correction_request_id`, `cause_note_id`, `progress_note_id`, `photo_id`), `reason`, `payload`(jsonb, 작은 부가 값만) | `actor_key`는 회원 ID, `server`, 링크 열람이면 `link:<링크 ID>`(가입 전 사람, `actor_member_id`는 NULL), 운영 명령이면 `ops:<담당>`(작업 대장과 짝). `UNIQUE(actor_key, idempotency_key)`(한 요청의 여러 행동은 `<키>#2`…, 주체가 다른 딸린 행동은 `<request_id>#n`이라 두 회원이 같은 키를 써도 서버 행동끼리 부딪히지 않음), `UNIQUE(room_record_id, room_seq)`(호실 기록 행을 잠그고 다음 번호. 작업자도 호실마다 트랜잭션을 나눔. 해시 입력 모양은 1주차에 테스트로 고정). `CHECK(num_nonnulls(대상 칸들) <= 1)`(0이면 호실 기록 자체: 이사함, 정산 끝내기). 트리거: UPDATE 거절, DELETE는 소유자 권한과 `app.purge`가 함께 있을 때만(호실 기록 통째 삭제, 탈퇴 때 초안 지우기 2-10). 대상 칸마다 부분 인덱스. 이름·번호를 복사하지 않음(불변식 1) |

내용 지문과 행동 해시는 나눠 계산한다. `content_digest`는 당시 본 판의 내용과 그 판에 포함된 사진 지문을 정해진 입력 형식으로 묶은 SHA-256이고, `action.hash`는 이전 해시·행동 정보·요청 ID(`request_id`)·대상 ID·판 번호·`content_digest`를 포함해 계산한다. 같은 판의 내용 지문은 재사용할 수 있지만, 실제 내용을 확인할 때는 본문·사진 지문으로 다시 계산해 비교한다. **행동 체인 검증만으로 본문까지 검증했다고 보지 않는다.** 내용 대상이 없는 행동만 NULL이며, 어떤 행동에 어떤 판을 연결하는지는 해시 계산 구현에서 입력 형식과 함께 테스트로 고정한다. DB는 지문의 길이만 검사하며 계산·내용 대상 행동의 필수 여부는 앱이 보장한다. 지문은 암호화나 익명화가 아니므로 개인정보 위험이 없어지는 것은 아니다.

`type` **전체 목록**(이 목록은 여기에만 있다). `apply`의 분기와 API 끝 단어가 이 목록을 따른다. 새 행동은 이 표와 새 V 파일의 `ck_action_type`을 같은 PR에서 고치고, 그 행동을 쓰는 기능의 06 2장 추적표 ERD 칸에도 이름을 적는다.

| 영역 | 행동 종류 |
|---|---|
| 호실·참여 | `CREATE_ROOM`, `EDIT_ROOM_INFO`(내 쪽·주소·별칭, 처음 알리기·초대 전만), `ADD_PART`, `HIDE_PART`, `TOGGLE_PART`, `INVITE`, `CHANGE_INVITE_PHONE`, `REJECT_INVITE`, `CONNECT`, `JOIN`, `SHARE_CONSENT`, `STOP_SHARING`, `RESUME_SHARING`, `MOVE_OUT`, `REQUEST_END`, `CONFIRM_END`, `REJECT_END`("아직 살고 있어요", 사유, 06 4-3 `/move-out/reject`), `CONFIRM_LIVING` |
| 링크·통지 | `LINK_VIEW`(`link:<ID>`), `OPT_OUT`, `RESEND_NOTICE`, `RENOTIFY`(서버), `MARK_UNCONFIRMED`(서버), `MARK_UNDELIVERED`(서버) |
| 수리 | `REGISTER`, `SUPPLEMENT`, `RESPOND_DEFECT`, `START_REPAIR`, `REQUEST_CLOSE`, `CONFIRM_CLOSE`, `NOT_YET`, `CLOSE_ALONE`, `REOPEN`, `ADD_PHOTO`, `ASK_PROGRESS`, `NOTE_PROGRESS`, `RESPOND_SITUATION`, `NOTE_CAUSE`, `RESPOND_CAUSE` |
| 고치기 | `CORRECT`, `RESPOND_CORRECTION`, `ACCEPT_ALL_CORRECTIONS`, `RESUBMIT_CORRECTION`, `WITHDRAW_CORRECTION` |
| 돈·제안 | `RECORD_PAYMENT`, `RESPOND_AMOUNT`, `SUPPLEMENT_PAYMENT`, `PROPOSE`, `AGREE`, `DISPUTE_PROPOSAL`, `COUNTER`, `WITHDRAW` |
| 점검 | `START_INSPECTION`, `ADD_ITEM`, `ADD_PART_PHOTO`, `REQUEST_INSPECTION_CONFIRM`, `RESPOND_ITEM`, `SUPPLEMENT_ITEM`, `CONFIRM_ALL`, `LOCK`(서버), `FINISH_INSPECTION` |
| 정산 | `OPEN_SETTLEMENT`, `INCLUDE_CANDIDATE`, `ADD_SETTLEMENT_ITEM`, `PROPOSE_EXCLUDE`, `RECORD_TRANSFER`, `RESPOND_TRANSFER`, `PROPOSE_FINISH`, `CONFIRM_FINISH`, `NOT_YET_FINISH`, `FINISH_ALONE`, `CLOSE_FINISH_REQUEST`(서버, 정산이 바뀌어 닫힘), `AUTOCLOSE`(서버) |
| 사진 | `HIDE_PHOTO`, `SHOW_PHOTO`, `MARK_PHOTO_WRONG`, `UNMARK_PHOTO_WRONG`(올린 사람), `ERASE_PHOTO`(올린 사람 또는 `ops:<담당>`) |
| 계정(호실 밖) | 회원 변경은 행동 기록이 아니라 신원 칸 예외라 값 없이 내부 기록만: 이름 변경, 번호 변경, 탈퇴 |

`RESPOND_*` 행동은 값 `ACK` / `NEED_MORE` / `DISPUTE`(보류·이견은 사유 필수)를 가진다. 보류·이견 뒤에는 같은 행동으로 `ACK`를 다시 남길 수 있고(정산 완료 전), `ACK` 뒤의 응답은 거절한다(`ACK_FINAL`).

### 2-4. 공통 장치: 돈 안건 장치와 기다림 타이머

| 테이블 | 종류 | 칸 | 제약·메모 |
|---|---|---|---|
| `proposal` 제안 | B | 대상 칸(`repair_case_id` 부담 / `settlement_item_id` 정산 항목 / `settlement_id` 보증금), `proposed_by_side`, `counter_of_id`(역제안이면 상대의 이전 제안), `replaces_id`(답 전에 [바꾸기]로 바꾼 내 이전 안), `round`, `no_cost`, `exclude`, `reason`(정산 항목 [바꾸기]의 사유, 06 SET-2d `REASON_REQUIRED`) | `CHECK(num_nonnulls(대상) = 1)`. 공통 칸과 대상 칸만 둔다(기능별 내용은 그 기능의 테이블에. 정산 항목의 금액·방향은 제안 줄, 사유는 `reason`). B라 상태 칸이 없다. **어느 안이 기다리는 중인지는 안건 머리(C)가 정한다**: `repair_case.current_proposal_id`, `settlement_item.current_proposal_id`, `settlement.current_deposit_proposal_id`. 가리키는 칸이 하나라 **한 안건에 기다리는 안은 구조상 하나**다(제안 행에 부분 유니크를 걸지 않음, 동시 요청은 호실 기록 행 잠금으로 한 줄로 섬). **기다리는 안** = 머리가 가리키고 그 안건의 `PROPOSAL` 응답 요청이 WAITING 또는 UNCONFIRMED인 안. 규칙(03-상태 1-2·E): ① **미확인도 정산 완료 전까지는 기다리는 안**이다. 합의는 그대로 쓰이고 "바꾸자는 안 있음"이 함께 보이며, 상대는 늦게라도 동의할 수 있다(06 MONEY-3d) ② 답 전에 바꾸면(미확인 안 포함) 새 제안 행(`replaces_id` = 이전 안)을 만들고 머리를 옮긴다. 응답 요청은 안건을 가리키므로 같은 요청에 그대로 묶이고(미확인이었으면 WAITING으로 돌아감) 문자는 다시 가지 않으며 기한만 다시 정한다(06 SET-2e) ③ 상대의 안이 기다리는 중에 낸 안은(합의 뒤 바꾸자는 안 포함) 그 안에 대한 **다른 안**(역제안, `counter_of_id`)이다. 머리를 옮기고 이전 안의 요청은 ANSWERED, 다른 안에 새 요청 ④ 바탕 금액이 같이 확인돼 바뀌어 **합의가 풀리면**(03-상태 E "지불 합계 변경") 같은 트랜잭션에서 머리의 `current_*`·`agreed_*` 가리킴을 비우고 기다리던 요청은 `CLOSED`(`closed_reason = AMOUNT_CHANGED`, "금액이 바뀌어 닫힘")로 닫는다. 이전 합의는 제안 행과 `AGREE` 행동으로 참고 표시. `exclude = true`이면 `settlement_item_id` 필수(DB CHECK). |
| `proposal_line` 제안 줄 | B | `proposal_id`, `line_kind`(TENANT/LANDLORD/THIRD_PARTY 부담 · DEDUCT/ADD 정산 · DEPOSIT 보증금), `third_party_label`, `amount` | `amount bigint CHECK (amount >= 0)`. `third_party_label`은 THIRD_PARTY 줄에서만 허용한다(DB CHECK). 부담 줄의 합 = 전체 금액은 서비스 검사. 비율 칸 없음 |
| `response_request` 응답 요청 | C | `kind`(DEFECT_CONFIRM/SITUATION_CHECK/CORRECTION_CONFIRM(기록당 하나, 대상은 기록: 수리 건·지불·점검·송금. 대기 중인 고친 내용이 0개가 되면 ANSWERED)/AMOUNT_CONFIRM/PROPOSAL/CLOSE_CONFIRM/INSPECTION_CONFIRM/RECEIPT_CONFIRM/SETTLE_FINISH/END_REQUEST/PROGRESS_CHECK), 대상 칸(`repair_case_id`, `payment_id`, `inspection_id`, `inspection_item_id`, `settlement_item_id`, `settlement_id`, `settlement_transfer_id`, `progress_note_id`, 없으면 호실 기록. `PROPOSAL`은 제안이 아니라 **안건**(수리 건 부담·정산 항목·정산 보증금)을 가리킨다: 기다리는 안이 바뀌어도 같은 요청), `requested_by_side`, `first_notified_at`(첫 통지가 전달된 시각 = min(문자 전달됨, 링크 열람, 회원의 알림 열람). 문자를 끈 회원은 서비스 안 알림 생성 시각. 연결·다시 공유를 마친 화면의 요청 목록 표시도 알림 열람이다. 06 NOTI-3b~3d, 03 1-4), `renotify_due_at`, `unconfirmed_due_at`, `status`(WAITING/ANSWERED/UNCONFIRMED/WITHDRAWN(요청한 쪽이 거둠)/CLOSED), `closed_reason`·`closed_at`(CLOSED일 때만, 아래), `solo_reason`(UNCONFIRMED/OPTED_OUT/COUNTERPART_WITHDRAWN(상대 탈퇴)/SHARING_STOPPED/SMS_UNDELIVERED/NOT_CONNECTED(초대했지만 연결 전, 화면 "아직 연결하지 않았어요")/NO_COUNTERPART(번호 없음·초대 전)) | `CHECK(num_nonnulls(대상) <= 1)`. 작업자는 이 테이블만 본다: 부분 인덱스 `(renotify_due_at) WHERE status = 'WAITING'`와 `(unconfirmed_due_at) WHERE status = 'WAITING'`, `FOR UPDATE SKIP LOCKED`. "혼자 진행할 수 있나" 함수도 이 행을 본다. 요청 종류마다 "기한이 지나면 혼자 진행이 열리나"를 코드의 성질로 둔다(칸 없음, 확인 카드의 두 칸 나누기에 씀). 같은 기록의 고친 칸이 더해지거나 기다리는 안이 바뀌면 같은 트랜잭션에서 기한을 다시 정한다. `PROPOSAL`은 안건마다 기다리는 요청 하나: 부분 유니크 `(COALESCE(repair_case_id, settlement_item_id, settlement_id)) WHERE kind = 'PROPOSAL' AND status IN ('WAITING', 'UNCONFIRMED')`(미확인도 기다리는 안이라서, 2-4 `proposal`. NULL이 섞인 복합 유니크는 걸리지 않아 한 값으로 합침, ID가 UUID라 세 칸이 겹치지 않음). **닫힌 이유**: `CHECK((status = 'CLOSED') = (closed_reason IS NOT NULL))`, `closed_at`도 같이 채움(나중에 더하면 이미 닫힌 행은 이유를 알 수 없어 첫 마이그레이션부터). 값과 화면 말: `SHARING_STOPPED`("공유 중단으로 닫힘", 03-상태 B), `COUNTERPART_WITHDRAWN`("상대 탈퇴로 닫힘", 03-상태 L), `SETTLED`("정산 완료로 닫힘", 03-상태 G), `CLOSED_BY_BOTH`("처리 완료로 닫힘", 06 REP-7l), `SETTLEMENT_CHANGED`("정산 내용이 바뀌어 닫혔어요", 06 SET-5b), `AMOUNT_CHANGED`("금액이 바뀌어 닫힘", 03-상태 E), `ROOM_ENDED`("거주 종료로 닫힘": 거주 종료가 되는 트랜잭션에서 WAITING인 `DEFECT_CONFIRM`·`PROGRESS_CHECK`·`SITUATION_CHECK`, 03-상태 K "거주 종료 뒤", 06 ROOM-9a). `SETTLE_FINISH`는 요청 행에 정산 모습을 따로 두지 않는다: 정산을 바꾸는 행동이 같은 트랜잭션에서 `settlement.snapshot_no`를 올리고 기다리는 끝내기 요청을 `CLOSED`(`SETTLEMENT_CHANGED`)로 닫는다. 상대의 확인(`CONFIRM_FINISH`)은 본 번호(`seen_version_no`)가 지금 `snapshot_no`와 다르면 `VERSION_CONFLICT`(03-상태 1-4, 06 SET-5e) **일반 요청은 같은 호실·같은 종류·같은 대상의 WAITING이 하나**: 대상 칸 전체를 `NULLS NOT DISTINCT`로 비교하는 부분 유니크(대상 칸이 전부 NULL이면 호실 자체). PROPOSAL은 WAITING·UNCONFIRMED에서 안건당 하나, INSPECTION_CONFIRM은 WAITING에서 점검 × 요청 방향마다 하나라는 기존 예외를 유지한다. SITUATION_CHECK는 `progress_note_id`가 필수이고 다른 대상 칸은 비운다. |

### 2-5. 수리와 돈

| 테이블 | 종류 | 칸 | 제약·메모 |
|---|---|---|---|
| `repair_case` 수리 건 | C | `current_content_id`, `confirmed_content_id`(같이 확인한 모습), `status`(NOT_STARTED/IN_PROGRESS/CLOSE_WAIT/DONE)(수리 진행, 하자 인정과 따로), `closed_by`(BOTH/TENANT/LANDLORD), `defect_ack`(PENDING/ACK/NEED_MORE(보류)/DISPUTE/UNCONFIRMED), `money_status`(UNDECIDED/PENDING/AGREED/DISPUTE/NO_COST), `current_proposal_id`(답 기다리는 제안, 2-4), `agreed_proposal_id`, `previous_case_id`(재발), `created_by_side`, `solo`, `first_shown_at` | 상태 칸은 `apply` 결과로만 바뀜. `first_shown_at`이 NULL이면 탈퇴 때 지우는 초안(2-10) |
| `repair_content` 수리 건 내용 판 | B | `repair_case_id`, `no`(판 번호, 1부터), `room_part_id`, `title`(필수), `description`(NULL 허용), `previous_id`, `created_by_side` | 고치면 새 판. `UNIQUE(repair_case_id, no)`. 처리 완료(양측 확인)된 수리 건은 새 판을 만들지 않음(`CLOSED_BY_BOTH`, 06 REP-7k) |
| `cause_note` 원인 설명 | B | `repair_case_id`, `side`, `text`, `basis`(GUESS/SEEN/EXPERT/HEARD), `expert_name`, `previous_id` | 고치면(문장이든 근거 종류든) **언제나 새 행**(`previous_id`로 이전 설명), 고친 내용(`correction_request`)을 쓰지 않음(06 REP-8c). 상대 응답(ACK/NEED_MORE/DISPUTE)은 행동 기록(`cause_note_id`)이고 그 행에만 붙음. 같은 이전 설명의 다음 수정본은 하나만 허용한다(`previous_id`가 있을 때 유일). 이미 수정된 설명에서 다시 고치려 하면 `VERSION_CONFLICT`로 거절하고 최신 설명을 다시 보게 한다 |
| `progress_note` 진행 상황 | B | `repair_case_id`, `side`, `text`, `answers_request_id`(진행 상황 묻기에 대한 답이면), `asks_check`("상황이 바뀌었어요" 확인 요청이면 true. 상대 응답은 작은 C 행 `progress_note_response`(맞아요 / 보류 / 이견)와 응답 요청 `SITUATION_CHECK`) | 수리 상태·돈 상태와 무관. 고치려면 새 행. 사진은 `photo.progress_note_id`. 행동 `ASK_PROGRESS`(응답 요청 `PROGRESS_CHECK`), `NOTE_PROGRESS`. "오래 멈춘 건"은 저장하지 않고 조회로 계산(마지막 당사자 행동 시각) |
| `progress_note_response` 상황 변화 응답 | C | `progress_note_id`, `side`(응답한 측), `answer`(ACK/NEED_MORE/DISPUTE), `reason` | `asks_check = true`인 진행 상황에만. `UNIQUE(progress_note_id)`(지금 답 하나, 바뀐 이력은 행동 `RESPOND_SITUATION`). 보류·이견 → ACK는 정산 완료 전까지, ACK 뒤는 거절(`ACK_FINAL`). 06 4-6 `progress-notes/{id}/respond` |
| `payment` 지불 | C | `repair_case_id`, `current_version_id`, `confirmed_version_id`, `confirm_strength`(ONE_SIDE/EVIDENCE/BOTH), `response`(NONE/ACK/NEED_MORE/DISPUTE), `first_shown_at` | 한 수리 건에 지불 여러 개, 지불 하나는 한 수리 건에만. 나누기 기준 금액은 `response = ACK`인 지불의 확인된 판 금액의 합 |
| `payment_version` 지불 판 | B | `payment_id`, `no`(판 번호), `payer_kind`(TENANT/LANDLORD/THIRD_PARTY), `payer_label`(제3자면 이름 대신 관계: 가족·윗집·보험사), `payee_kind`(VENDOR/COUNTERPART/THIRD_PARTY), `payee_label`, `amount`, `paid_on`(사용자 입력 날짜), `recorded_by_side`(보낸 쪽/받은 쪽), `evidence_kind`, `previous_id` | `amount >= 0`. `UNIQUE(payment_id, no)`. 중복 검사(같은 수리 건·방향·금액 30일)는 서비스. 증빙이 있었는지는 붙은 사진 카드로 판단(탈퇴로 파일이 지워져도 카드는 남음) |
| `correction_request` 고친 내용 | C(만든 뒤에는 상태만 바뀜) | (같이 확인한 뒤 고칠 때 생김. **칸 하나 + 새 값 + 기준 판**: `field`, `new_value`(jsonb), `base_version_id`(그때 도장 찍힌 판), 칸마다 확인 전은 하나(아래 부분 UNIQUE), 다시 고치면 이전 것은 `SUPERSEDED`), 대상 칸(`repair_case_id`, `payment_id`, `inspection_item_id`, `settlement_transfer_id`, 없으면 호실 기록의 입주일), `target_key`(대상 종류 + ID를 합친 NOT NULL 문자열, 칸마다 하나 제약에 씀), `retry_of_id`(거절된 고친 내용을 근거를 더해 다시 보낼 때 이전 행), `requested_by_side`, `reason`, `status`(WAITING/ACCEPTED/REJECTED/UNCONFIRMED/WITHDRAWN/SUPERSEDED/CLOSED), `closed_reason`·`closed_at`(CLOSED일 때만: `SHARING_STOPPED`/`COUNTERPART_WITHDRAWN`/`SETTLED`/`CLOSED_BY_BOTH`, 값과 화면 말은 2-4 `response_request`와 같음) | 미리 만든 판을 두지 않는다(통째 반영 방지). 상대가 확인하면 **그때의 도장 찍힌 판 + 이 칸만** 합쳐 새 판을 만들고 두 가리킴을 함께 옮김. 기준 판 이후 같은 칸이 바뀌었으면 합치지 않고 다시 확인. 부분 UNIQUE는 `(target_key, field) WHERE status = 'WAITING'`. 대상이 아닌 것: 처리 완료(양측 확인)된 수리 건의 내용(`CLOSED_BY_BOTH`), 원인 설명(고치면 새 `cause_note`). 처리 완료(양측)가 되는 트랜잭션에서 그 수리 건 내용의 WAITING 고친 내용과 그 응답 요청을 `CLOSED`(`CLOSED_BY_BOTH`)로(지불 고친 내용은 그대로, 06 REP-7l). `CHECK((status = 'CLOSED') = (closed_reason IS NOT NULL))`. `REJECTED`는 보류·이견의 공통 처리 상태이며 응답 종류가 아니다. 구분과 사유는 이 요청을 가리키는 `RESPOND_CORRECTION` 행동의 `payload.answer`(NEED_MORE/DISPUTE)와 `reason`에 보존하고 조회한다. 응답이 바뀌면 새 행동을 남기며 조회는 마지막 응답을 쓴다. 별도 `answer` 칼럼은 두지 않는다 |

### 리뷰에서 확인한 칸의 용도

| 칸 | 결정과 근거 |
|---|---|
| `payment.confirm_strength` | 유지. 03 F의 한쪽 기록/증빙 있음/양측 확인 표시와 06 추적표 MONEY-2에 사용한다. 상대 확인이면 BOTH, 확인 전 증빙 카드가 있으면 EVIDENCE, 그 밖은 ONE_SIDE이며 보류·이견은 `response`로 별도 표시한다. 파일이 지워져도 증빙 카드는 남는다 |
| `repair_case.solo` | 유지. 06 ROOM-7의 혼자 남긴 기록 표시를 위한 상태 사본이다. 상대 연결 시 확인 요청이 생기는 규칙은 03 1-1을 따른다 |
| `repair_case.confirmed_content_id` | 유지. 03 G의 도장 찍힌 모습을 가리키며, 확인 전 고친 내용이 있어도 공동 화면의 기준 판을 잃지 않게 한다 |
| `link.failed_view_count` | 유지. 06 LINK-2 추적표의 링크 화면 보호용 횟수다. 링크를 식별할 수 있는 요청의 실패만 이 칸에 기록하고, 없는 토큰의 반복 조회는 04의 IP별 제한으로 처리한다. 실패 횟수만으로 링크를 무효화하는 규칙은 추가하지 않는다 |
| `photo.sort_order` | 유지. 같은 대상에 추가할 때 기존 순서 다음부터 제출 순서대로 번호를 정하고, 등록 뒤에는 순서 변경 기능을 두지 않는다 |
| `settlement.opened_at` | 제거. 정산은 열 때 생성하므로 공통 `created_at`으로 같은 사실을 표현한다 |
| `repair_case.related_case_id` | 제거. 현재 요구사항에 별개 하자 사이의 연결 기능이 없다. 재발 연결인 `previous_case_id`는 유지한다 |

### 2-6. 점검

| 테이블 | 종류 | 칸 | 제약·메모 |
|---|---|---|---|
| `inspection` 점검 | C | `kind`(MOVE_IN/MOVE_OUT), `author_side`, `status`(DRAFT/REQUESTED/LOCKED), `late`, `locked_at`, `lock_reason`(ALL_RESOLVED/NO_ITEMS), `first_shown_at` | `UNIQUE(room_record_id, kind)`(먼저 저장된 측이 기준). 퇴거 점검의 잠김은 거주 종료와 별개 |
| `inspection_item` 점검 항목 | C | `inspection_id`, `current_version_id`, `confirmed_version_id`, `status`(PENDING/ACK/NEED_MORE/DISPUTE/UNCONFIRMED, 수리 건 `defect_ack`와 같은 값이라 06 API의 응답 값 ACK·NEED_MORE·DISPUTE를 바꾸지 않고 씀), `added_by_side`, `added_after_lock`(잠긴 뒤 추가), `added_after_end` | 항목 = 적은 하자만. 빠뜨린 하자 더하기는 새 기록(누구든, 더하지 않은 쪽이 확인). 확인 요청 `INSPECTION_CONFIRM`은 점검 × 요청 방향마다 하나(부분 유니크 `(inspection_id, requested_by_side) WHERE WAITING`), 두 방향이 모두 끝나야 잠김. 적지 않은 부위는 행이 없음("기록 없음") |
| `inspection_item_version` 점검 항목 판 | B | `inspection_item_id`, `no`(판 번호), `room_part_id`, `description`, `previous_id` | `UNIQUE(inspection_item_id, no)` |

부위 사진(하자 아님)은 따로 테이블을 두지 않고 `photo`의 단계(MOVE_IN/MOVE_OUT) + `inspection_id`로 남겨요.

### 2-7. 정산

| 테이블 | 종류 | 칸 | 제약·메모 |
|---|---|---|---|
| `settlement` 정산 | C | `deposit_status`(NOT_ENTERED/PENDING/AGREED/DISPUTE, 화면 말은 "보증금 확인 전"/"둘 다 확인". "미확인"과 헷갈리지 않게 UNCONFIRMED라 부르지 않음), `current_deposit_proposal_id`(답 기다리는 제안), `agreed_deposit_proposal_id`(지금 합의, 바꾸자는 안이 와도 그대로), `snapshot_no`(**정산 모습 번호**, 1부터. 06 SET-5e "정산 모습 3번 → 4번". `version`처럼 바뀌는 칸) | `UNIQUE(room_record_id)`. **정산 모습 번호**: 항목·송금·보증금 제안은 다른 행이라 바뀌어도 `settlement.version`이 오르지 않으므로 따로 센다. 정산에 무엇이든 더해지거나 바뀌면(항목 추가·제외 제안, 금액 제안·응답·이견, 수리 건에서 온 금액이 바뀌어 항목이 확인 대기가 됨, 보증금 제안·응답, 송금 기록·응답·고치기, 누가 했든, 03-상태 1-4) 그 행동의 트랜잭션에서 `UPDATE settlement SET snapshot_no = snapshot_no + 1`(이 행을 잠금). 화면은 이 번호를 받아 끝내기 확인 때 `seenVersionNo`로 돌려보낸다 |
| `settlement_item` 정산 항목 | C | `settlement_id`, `source`(REPAIR/MANUAL), `repair_case_id`, `room_part_id`, `category`, `status`(PENDING/AGREED/DISPUTE/EXCLUDED), `current_proposal_id`(답 기다리는 제안, 2-4), `agreed_proposal_id` | 출처·부위·종류는 만들 때 한 번만 씀(바꾸려면 이 항목을 제외 제안하고 새 항목을 넣음). **금액·방향은 제안 줄, 사유는 제안(`proposal.reason`)에**(정산 항목의 [바꾸기] = 새 제안, 고치기 대상 아님). 합의된 항목만 계산(불변식 6). `UNIQUE(settlement_id, repair_case_id) WHERE source = 'REPAIR' AND status <> 'EXCLUDED'`로 유효 수리 건 후보 중복을 막는다. 제외한 항목은 이력으로 두고 새 후보를 넣을 수 있으며 수동 항목은 제한하지 않는다. `room_part_id`는 MANUAL일 때만 쓰고 REPAIR이면 수리 건의 부위를 따른다. |
| `settlement_transfer` 정산 송금 | C | `settlement_id`, `direction`(REFUND/EXTRA), `current_version_id`, `confirmed_version_id`, `recorded_by_side`, `response`(NONE/ACK/NEED_MORE/DISPUTE, 그 기록을 남기지 않은 쪽의 응답: 보낸 쪽 기록이면 받은 쪽, [받았어요(혼자)]면 보낸 쪽), `same_as_transfer_id`(받은 쪽이 실제 받은 금액으로 남긴 [받았어요(혼자)]가 보낸 쪽 기록과 같은 송금이면 그 기록, 합계에서 두 번 세지 않음), `receipt_confirmed_at` | 지불 기록과 같은 "C 머리 + B 판". 받은 쪽 응답은 응답 요청(RECEIPT_CONFIRM). 여러 번. 송금 합계는 **상대가 확인한(`response = ACK`) 판만** 더하고, 같은 송금으로 묶인 두 기록은 한 번만 센다 |
| `settlement_transfer_version` 정산 송금 판 | B | `settlement_transfer_id`, `no`(판 번호), `amount`, `paid_on`, `previous_id` | 금액·날짜를 고치면 새 판(고치기). 여러 번. `UNIQUE(settlement_transfer_id, no)` |

정산 후보(수리 건에서 온 금액)와 "합의된 항목으로 계산한 금액"은 **저장하지 않고 계산**해요.

### 2-8. 사진

| 테이블 | 종류 | 칸 | 제약·메모 |
|---|---|---|---|
| `photo` 사진 | B | 대상 칸(`repair_case_id`, `inspection_item_id`, `inspection_id`(부위 사진), `payment_id`, `cause_note_id`, `settlement_item_id`, `progress_note_id`, `correction_request_id`(다시 요청에 더한 근거)), `redacted_from_photo_id`(개인정보 때문에 지운 원본 자리에 올린 가린 사본이면 그 원본 행, 같은 사진이라는 증명은 아님), `room_part_id`(**부위 사진만**, 다른 사진은 NULL), `stage`(MOVE_IN/BEFORE/AFTER/MOVE_OUT/EVIDENCE/BASIS/PROGRESS), `uploaded_by_side`, `device_taken_at`(참고), `original_key`, `thumb_key`, `original_sha256`, `thumb_sha256`(S3 체크섬, 지문값), `size_bytes`, `sort_order`(같은 대상 안의 사진 표시 순서, 만들 때 고정) | `CHECK(num_nonnulls(대상) = 1)`, `CHECK((inspection_id IS NOT NULL) = (room_part_id IS NOT NULL))`. **부위는 사진에 복사하지 않는다**: 사진은 B라 복사해 두면 대상의 부위를 고쳐도 옛 부위로 남는다(06 REP-7a·7i). 사진의 부위는 조회 때 대상에서 읽는다: 수리 건·점검 항목 사진은 그 대상의 앞에 보이는 판(같이 확인한 모습, 없으면 지금 모습)의 `room_part_id`, 지불·원인 설명·진행 상황 사진은 그 수리 건의 것, 정산 항목 사진은 수동 항목의 `settlement_item.room_part_id` 또는 REPAIR 항목이 가리키는 수리 건의 부위, 고친 내용 사진은 그 고친 내용의 대상의 것. 부위 사진(`inspection_id`)은 대상에 부위가 없어 요청의 부위를 `room_part_id`에 둔다. 나란히 보기(06 INSP-3b)는 판 테이블의 `room_part_id`(인덱스 `repair_content(room_part_id)`, `inspection_item_version(room_part_id)`)와 부위 사진의 `(room_record_id, room_part_id, stage)`로 모은다. 검사를 통과한 뒤에만 행이 생김(불변식 14). `UNIQUE(original_key)`(같은 업로드 표로 두 번 확정 금지). `stage`는 서버가 제출 행동과 대상에서 정함(화면이 보내지 않음). **등록·추가 행동과 같은 트랜잭션에서만 생김**(제출 전에는 행이 없어 화면에서 자유롭게 뺌). 키는 `photos/{room_record_id}/…`(보관 삭제가 접두어로 지움). 개인정보·탈퇴·다른 사람 요청으로 지울 때는 행을 남기고 파일만(`photo_visibility`), 행까지 지우는 것은 호실 기록 통째 삭제(보관 기한 끝, `app.purge`)뿐 |
| `photo_visibility` 사진 공개 상태 | C | `photo_id`(PK), `hidden_on_link`, `marked_wrong_at`(잘못 올린 사진 표시, 뗄 수 있음), `erased_at`(파일을 지운 시각), `erase_reason`(PRIVACY/WITHDRAWAL/THIRD_PARTY) | 행동 "링크에서 숨김 / 다시 보이기", "개인정보 사진 지우기"로만 바뀜. `erased_at`은 한 번 채워지면 되돌릴 수 없음(`apply`), 채워지면 어떤 주소도 발급하지 않고 그 자리에 "지운 사진 · 시각" 표시(화면의 고정 표시 컴포넌트). 파일은 아웃박스로 S3에서 지우고 `photo` 행(지문값 포함)은 흔적으로 남음 |

### 2-9. 통지와 작업

| 테이블 | 종류 | 칸 | 제약·메모 |
|---|---|---|---|
| `notice` 통지 | C | `response_request_id`(있으면), `link_id`, `recipient_party_id`, `kind`(INVITE/REQUEST/RENOTIFY/SETTLEMENT_INVITE/INFO), `channel`(SMS/IN_APP), `status`(REQUESTED/SENT/DELIVERED/VIEWED/FAILED), `sent_at`, `delivered_at`, `viewed_at`, `failed_at`, `provider_message_id`(솔라피 messageId), `batch_lead_id`(같은 문자 한 통으로 나간 통지 묶음의 **대표 통지**: 작업자가 처음 집은 통지. 혼자 나가면 자기 자신. 보낼 때 채움), `next_poll_at`(전달 결과 조회 시각), `scheduled_for`(보낼 예정 시각: 모으기 2분, 21~08시 보류, 한도 미룸. 화면 "내일 아침에 보낼게요") | 통지 상태 화면은 여기서(한 줄 표시, 03-상태 K). **문자 모으기**: 같은 받는 사람·같은 호실의 회원 문자를 한 통으로 보내면 여러 `notice`가 같은 `batch_lead_id`·`provider_message_id`를 갖고 전달 결과·열람이 함께 바뀐다(통지는 요청마다 하나라 불변식 4가 그대로). **솔라피 `customFields`에는 통지 ID 하나만**(06 4-11 ①): `batch_lead_id`를 넣는다. 한 통에 통지가 하나면 그 통지 ID, 모은 문자면 대표 통지 ID다(04 4-2의 "묶음 ID"가 이것, 번호·이름은 넣지 않음). 하루 한도는 `rate_counter`(보낸 문자 한 통마다)로 센다. 전달 결과는 조회 API로 채움(부분 인덱스 `(next_poll_at) WHERE status = 'SENT'`), 보내고 3일이 지나도 결과가 없으면 FAILED. 미확인은 DELIVERED 두 건일 때만. `channel = IN_APP`(문자를 끈 회원, 인증이 풀린 계정)은 만들 때 바로 DELIVERED(`delivered_at` = 생성 시각). 회원이 문자 링크(`/r/…`)나 서비스 안 알림을 열면 `viewed_at`(= "알림 확인", 전달로 봄). FAILED면 보낸 측이 한 번 다시 보내기(같은 응답 요청의 새 `notice`), 그것도 FAILED면 응답 요청의 `solo_reason = SMS_UNDELIVERED`. 타이머 시작점은 min(`delivered_at`, `viewed_at`)(문자를 끈 회원은 서비스 안 알림 생성 시각). 링크 열람이 있으면 결과가 없어도 전달로 봄 |
| `outbox` 아웃박스 | C | `kind`(SMS/S3_DELETE/AUDIT_WRITE/ORPHAN_CHECK), `dedupe_key`(UNIQUE, NULL 허용. 고아 파일 확인은 `orphan-check:<원본 키>`), `payload`(jsonb: 통지 ID, S3 키 등. **번호를 복사하지 않음**, 보낼 때 읽음), `status`(PENDING/SENDING/DONE/FAILED), `attempts`, `next_attempt_at` | 부분 인덱스 `(next_attempt_at) WHERE status = 'PENDING'`, `SKIP LOCKED`. 21~08시 보류는 `next_attempt_at`으로. **SENDING**(문자만, 04 4-2): 업체에 보내기 직전에 그 줄(모은 문자면 함께 잠근 줄 모두)을 `SENDING`으로 먼저 커밋한다. 앱이 켜질 때 `SENDING`으로 남은 줄은 다시 보내지 않고, 조회 API에서 `customFields`의 통지 ID(`notice.batch_lead_id`)로 결과를 찾아 맞춘다. 찾지 못하면 FAILED로 두고 사람이 [다시 보내기]("자동으로 두 번 보내지 않는다"). 통지 상태(03-상태 K)에는 SENDING을 두지 않는다(화면은 "발송 요청됨" 그대로) |
| `in_app_notification` 서비스 안 알림 | C | `member_id`, `kind`(RESPONSE/INFO/SERVICE), `response_request_id`, 이동할 대상, `group_key`(하루 묶음 알림이면, 예: `erase-digest:<호실>:<날짜>`), `group_count`, `read_at`, `expires_at`(30일) | `UNIQUE(member_id, group_key) WHERE group_key IS NOT NULL`(같은 날 같은 묶음은 한 행에 개수만 더함: 사진 지우기 알림 등). 묶음 알림이면 `group_key`와 `group_count`가 함께 존재하고 개수는 1 이상이며, 일반 알림이면 둘 다 NULL이다. 탈퇴하면 바로 삭제 |
| `rate_counter` 횟수 세기 | C | `counter_key`(예: `upload:<회원>`, `rerequest:<기록 종류>:<ID>`(다시 요청 7일, 8일 보관), `rerequest:invite:<당사자 자리 ID>`(같은 번호로 초대 다시 보내기 7일)), `window_date`, `count` | `UNIQUE(counter_key, window_date)`, `INSERT … ON CONFLICT DO UPDATE`로 하나씩 셈. 저장하지 않는 것(업로드 표)의 하루 한도를 셀 때 씀. **문자 발송 한도도 여기서 센다**: 실제로 문자 한 통을 보낼 때마다(모은 문자도 한 통) `sms:<번호 HMAC>`·`sms-member:<회원>`·`sms-total` 세 줄. 인증 문자는 `sms-total` 한 줄만 센다(06 0-1 "하루 문자 한도를 넘으면"). 인증 요청의 트랜잭션 안에서 커밋 전에 "한도 미만일 때만 +1"을 한 문장으로 하고(`INSERT … ON CONFLICT DO UPDATE SET count = count + 1 WHERE count < 한도`), 바뀐 줄이 없으면 `DAILY_LIMIT`이다(번호가 달라 잠금이 겹치지 않아도 한도를 넘지 않게). 솔라피가 실패로 답해도 되돌리지 않는다. 이틀 지나면 지움(`rerequest:` 키는 8일). 호실 기록 ID 없음(계정·번호 단위) |
| `deletion_log` 삭제 기록 | B | `target_kind`, `target_id`(UUID 대상, 번호 HMAC 대상은 NULL), `reason`, `phone_hmac`(수신 거부일 때), `chain_room_id`·`room_seq`·`prev_hash`·`action_hash`(지운 행동일 때만 모두 필수, 두 해시는 bytea 32바이트) | 외래 키 없음(호실 삭제 뒤에도 보존). `target_id` 또는 `phone_hmac` 중 하나 이상 필수(DB CHECK). ACTION이면 `target_id`도 필수. `UNIQUE(chain_room_id, room_seq) WHERE target_kind = 'ACTION'`. ACTION이 아니면 체인 칸은 모두 NULL. S3 `audit/deletions/`에도 같은 내용(아웃박스). 호실 기록 칸을 `room_record_id`라 부르지 않아 연쇄 삭제에서 제외한다. |

### 2-10. 탈퇴 때 초안 지우기

상대가 연결된 적 있는 호실에서 탈퇴하면 공동 기록은 가려서 남기고, **상대에게 보인 적 없는 초안은 행동 기록까지 지운다**(06 KEEP-4b, 03-상태 6-4, 불변식 1). 상대가 연결된 적 없는 호실은 통째 삭제라 여기에 해당하지 않는다(06 KEEP-4a).

| 무엇 | 규칙 |
|---|---|
| 초안 | 탈퇴하는 측이 만든 기록 가운데 **탈퇴하는 순간 상대가 볼 수 없는 것 전부**(03 3-1 보이는 범위 함수로 판단, 아래는 예): ① `repair_case`·`payment`·`inspection` 중 `first_shown_at IS NULL`인 것(작성 중 점검, 보내지 않은 초안 포함) ② 누가 공유를 그만뒀든, 불변식 18의 기준 시각(두 측 `sharing_stopped_at` 중 이른 쪽) 뒤에 남긴 `progress_note`·`photo`(이미 보인 기록에 붙은 사진도 사진 자체의 작성 시각으로 본다) ③ 같은 기준 시각 뒤에 남긴 지불·송금·정산 항목·제안·고친 내용·새 판(머리나 다른 기록이 가리키면 아래 "애매하면 남긴다"). 다시 공유하면 그전 기록이 모두 보이고 한 번 보인 기록은 다시 숨겨지지 않으므로, 지난 중단·재개 이력 없이 "지금 안 보임 = 한 번도 안 보임"이다. 거주 종료 행동(`MOVE_OUT`·`REQUEST_END`·`CONFIRM_END`·`REJECT_END`)은 양쪽에 보이므로 대상이 아니다. 공개와 실제 열람은 구별한다(보였으면 열지 않았어도 남긴다). `first_shown_at`은 실제로 보이게 된 시각에 채운다: 만들 때 상대가 연결돼 있고 공유 중이면 그 시각, 공유 중단 중에 만들었으면 다시 공유할 때, 연결 전이면 연결할 때. **목록은 탈퇴 트랜잭션에서 확정**해 바로 숨기고 삭제 작업은 그 목록만 지운다(탈퇴 뒤 자리 상태가 바뀌어도 다시 판단하지 않는다). **애매하면 남긴다**: 다른 기록(정산 항목 후보 등)이 가리키면 보인 것으로 보고 가려서 남기고, 판별에 실패하면 지우지 않고 가려서 남긴 뒤 삭제 기록에 실패를 남긴다(감지·재시도는 06 0장 탈퇴 파기 운영 검토안) |
| 지우는 것 | 초안과 딸린 행 전부: 머리(C), 판(B), 원인 설명·진행 상황, 제안·제안 줄, 고친 내용, 응답 요청·통지(있으면), 사진 카드(파일은 아웃박스 `S3_DELETE`), **그 초안을 대상으로 한 행동 행**. 한 트랜잭션에서 소유자 권한을 가진 전용 경로로 처리한다(부분 삭제 함수는 후속 구현). 외래 키 때문에 행동 행 → 딸린 행 → 머리 순서로 지운다. 보인 적이 없어 상대의 행동은 없다 |
| 남기는 것 | 보인 기록과 그 행동 전부(공동 기록, 회원 한 줄만 가림). 다른 행동 행은 고치지 않아 `prev_hash`도 그대로다 |
| 해시 체인 | 삭제된 번호는 `deletion_log`의 `prev_hash`가 직전 행동 또는 삭제 자리의 `action_hash`와 같은지 먼저 검사하고, 현재 자리의 `action_hash`로 다음 번호를 검증한다. 연속 삭제 41 → 42도 이 비교를 생략하지 않는다. 번호마다 삭제 기록은 하나이며 근거가 없거나 링크가 맞지 않으면 끊김으로 본다. 이는 검증 로직의 계약이며 현재 구현은 저장 제약까지다. |

한계는 지금과 같다: DB를 다룰 수 있는 사람은 행동을 지우고 삭제 기록을 꾸밀 수 있어 관리자 위조까지 증명하지는 못한다(04 해시 체인).

---

## 3. 불변식은 어디서 지키나

| # | 불변식 | DB | 서비스(`apply`) + 테스트 |
|---|---|---|---|
| 1 | 행동 기록 수정·삭제 없음(지우는 것은 통째 삭제와 상대에게 보인 적 없는 초안만), 이름·번호 복사 없음 | 트리거(DELETE는 소유자 권한과 `app.purge`가 함께 있을 때만), `@Immutable` | 행동 기록에 이름·번호 칸이 없음. 초안 지우기는 빈 번호를 `deletion_log`에 남겨 체인 검증이 이어짐(2-10) |
| 2 | 합의됨은 같은 안에 양 측 행동이 있을 때만 | | `apply`: `agreed_proposal_id`는 상대 측 AGREE가 같은 `proposal_id`를 가리킬 때만 |
| 3 | 처리 완료(양측 확인)는 한쪽의 종료 행동(종료 요청 또는 혼자 종료) + 다른 쪽의 종료 확인 | | `apply` |
| 4 | 미확인은 통지 두 건 전달 + 시간 경과 | | 작업자: 같은 응답 요청의 `notice`가 DELIVERED(또는 VIEWED) 두 건이고 기한이 지났을 때만 `UNCONFIRMED`. 가짜 발송기로 전달됨·최종 실패·결과 없음 세 경우 테스트 |
| 5 | 링크 열람자는 기록의 내용과 상태를 바꿀 수 없음(예외: 열람 기록, 문자 그만 받기) | | 링크 토큰 인증은 읽기 API와 `links/stop`만. 남는 행동은 `LINK_VIEW`·`OPT_OUT`뿐 |
| 6 | 정산 계산은 합의된 항목만 | | 계산 쿼리가 `status = 'AGREED'`만 |
| 7 | 무효 링크로는 볼 수 없음(사진 주소 5분) | 무효 칸은 되돌리지 않음(트리거: `invalidated_at`이 채워진 뒤 바꾸기 거절) | 서명 함수 |
| 8 | 한 번호는 한 계정 | `UNIQUE(phone)` | |
| 9 | 거주 종료 뒤 새 수리 건 없음 | | `apply` |
| 10 | 시각은 서버가 | `created_at DEFAULT now()` 대신 앱 `Clock`(테스트용) | |
| 11 | 저장된 사진에 위치 정보(GPS 태그) 없음 | | 업로드 확정 검사 |
| 12 | 폐기된 리프레시 토큰 재사용 시 묶음 폐기 | `UNIQUE(token_hash)` | 서비스 |
| 13 | 한 계정은 한 호실에서 한 측 | `UNIQUE(room_record_id, member_id)` | 자기 번호 초대 검사 |
| 14 | 사진은 원본·썸네일이 검사를 통과해야, 제출 트랜잭션에서만 | `original_key`, `thumb_key` NOT NULL | 제출 처리(검사 → 복사 → 행 생성) |
| 15 | 양 측이 확인한 사실은 혼자 바꾸지 못함 | | `apply`: 확인된 판은 상대 확인 없이 머리를 옮기지 않음 |
| 16 | 사진은 올린 측만 지우고(예외: 다른 사람의 요청은 운영 명령), 지운 사진은 되돌릴 수 없음 | `photo` 행은 트리거로 보호(남음) | `apply`: `ERASE_PHOTO`는 올린 측 또는 `ops:<담당>`(운영 명령)만, `erased_at`이 있으면 거절·주소 발급 안 함 |
| 17 | 카드 없는 정식 사진 파일은 반드시 지워지고, 카드 있는 파일은 지우지 않음 | `outbox.dedupe_key` UNIQUE | 등록: 복사 전 확인 줄 저장, 카드 트랜잭션에서 줄 삭제. 확인 작업: 줄 `FOR UPDATE` → 카드 재확인 → 삭제 |
| 18 | 상대가 공유를 그만둔 뒤 남긴 기록은 나에게 안 보임(보이는 범위 전체는 03-상태 3-1 표) | `party.sharing_stopped_at` | 권한 검사 함수 한 곳: 두 측 중 **어느 쪽이든** 공유를 그만둔 시각(둘 다면 이른 쪽) 이후의 상대 측 행동은 뺀다. 그만둔 사람에게도, 남은 사람에게도 같은 규칙(양방향). 그만둔 뒤의 상태는 C 행을 그대로 보여 주지 않고 **그 시각까지의 행동으로 다시 계산**해 보여 준다(03 6-6의 9, "○월 ○일 기준"). 예외: 거주 종료 행동(`MOVE_OUT`·`REQUEST_END`·`CONFIRM_END`·`REJECT_END`)과 `room_record.status`(ENDED·SETTLED)는 빼지 않는다. 요청을 만드는 곳(`notification`)은 공유를 그만둔 측에 응답 요청을 만들지 않는다(END_REQUEST만 예외). 이미 유효하게 기다리는 END_REQUEST도 공유 중단만으로 닫지 않고 기존 전달 이력·기한을 보존한다. 공유 중단과 응답·종료의 경합은 잠금 뒤 요청 상태·공개 범위를 다시 검사한다. 둘 다 그만뒀다가 한쪽만 다시 공유하면 남은 그만둔 시각이 기준이 되어, 그 전 기록은 서로 보이고 그 뒤 기록은 안 보인다 |
| 19 | 정산 끝내기는 상대가 본 정산 모습과 지금이 같을 때만 완료 | | 정산을 바꾸는 행동이 같은 트랜잭션에서 `settlement.snapshot_no`를 올리고 기다리는 끝내기 요청을 닫음. 확인(`CONFIRM_FINISH`)의 `seen_version_no`가 지금 `snapshot_no`와 다르면 `VERSION_CONFLICT` |
| 20 | 연결 전 상대에게 돈 확인 요청이 생기지 않고 링크에 돈 내용이 없음 | | 요청을 만드는 곳 한 곳(`notification`)에서 "돈 요청이고 `party.status <> CONNECTED`면 만들지 않음", 링크 응답은 `has_money`만 |
| 21 | 점검은 모든 항목에 쓰지 않은 쪽의 답 또는 미확인이 있을 때만 잠김 | `INSPECTION_CONFIRM` 부분 유니크(점검 × 방향) | `apply`: 두 방향 요청이 모두 끝나고 모든 항목이 네 상태 중 하나일 때만 LOCKED |

---

## 4. 한 장면이 테이블에 남는 모습

시나리오 1의 앞부분(세입자가 하자를 올리고 집주인에게 알림)을 예로 들어요.

| 단계 | A 행동 기록 | B 사실 행 | C 상태 행 | 작업 |
|---|---|---|---|---|
| 하자 등록 + 알리기 | `REGISTER`, `INVITE`, `SHARE_CONSENT` | `repair_content` 판 #1, `photo` 2장 | `repair_case`(NOT_STARTED, `defect_ack` PENDING, UNDECIDED), `party`(INVITED), `link`, `response_request`(DEFECT_CONFIRM, WAITING), `notice`(REQUESTED) | `outbox`(SMS) |
| (등록 전) 사진 확인 줄 | | | | `outbox`(ORPHAN_CHECK, 사진마다, 복사보다 먼저 커밋) |
| 문자 발송 | | | `notice`(SENT, `sent_at`, `next_poll_at`) | 작업자가 발송 |
| 전달 결과 조회 | | | `notice`(DELIVERED, `delivered_at`), `response_request`(`first_notified_at`, `renotify_due_at` = 전달 + 48h) | 1분·5분·15분마다 조회 |
| 집주인 링크 열람 | `LINK_VIEW` | | `notice`(VIEWED) | |
| 가입·연결 | `CONNECT`, `SHARE_CONSENT`(집주인) | | `party`(CONNECTED), `room_record.counterpart_first_connected_at`, `link`(무효) | |
| 확인 | `RESPOND_DEFECT`(ACK, 본 판 번호) | | `repair_case`(`defect_ack` ACK, 수리 진행은 그대로), `response_request`(ANSWERED) | 세입자에게 서비스 안 알림 |

한 줄마다 **A·B·C와 아웃박스가 한 트랜잭션**이에요.

---

## 5. 남은 것

| 항목 | 어디서 |
|---|---|
| 2026-11-16 설계 반영 | V1에 수신 거부 C 분류·탈퇴 전용 삭제 함수·부모 삭제 보호·링크 제약·원인 설명 수정 분기 제한·불필요 칼럼 제거를 반영한다. 운영 역할·GRANT 적용과 서비스의 인증·권한·감사 트랜잭션 연결은 후속 구현이다 |
| 하루 한 번 어긋남 검사 | 여유가 있을 때(선택) |

숫자(문자 하루 한도, 사진 장수·크기, 하루 업로드 한도)는 `06-요구사항-명세.md` 설정값 표에 정해져 있고, 해시 체인 칸은 첫 마이그레이션에 넣는다(검증 API·화면은 Should).
