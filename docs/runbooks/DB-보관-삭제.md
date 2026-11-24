# DB 보관 삭제

## 적용 범위

V1은 호실 통째 삭제 함수 `state.purge_room_record(uuid)`와 보호 제약을 만든다. 실제 운영 계정·접속 설정은 바꾸지 않는다. 보관 기한 판단·S3 삭제 작업자·일부 비공개 초안 삭제 함수·해시 체인 검증 API는 후속 구현이다.

V1은 부모 삭제 보호·수신 거부 UPDATE 금지·회원 확인 기록 탈퇴 전용 함수를 포함한다. 앱의 가입·인증·탈퇴 서비스 연결과 실제 운영 역할·GRANT 적용은 배포 전에 완료한다.

## 역할 분리

- 마이그레이션 역할: 보호 테이블과 삭제 함수의 동일한 소유자. 앱에 이 역할의 멤버십을 주지 않는다.
- 앱 역할: superuser·소유권·관리 역할 멤버십·스키마 CREATE·TRUNCATE 없음. record의 A·B에는 SELECT·INSERT만, log의 삭제 감사에는 INSERT만 부여하고 직접 DELETE 및 `purge_room_record` EXECUTE는 주지 않는다. C의 DML과 신원 정보 삭제는 필요한 범위만 부여한다. 호실 밖 약관 확인 기록의 탈퇴 전용 함수만 아래 조건으로 EXECUTE를 허용한다.
- 삭제 작업 역할: superuser·소유권·역할 관리 권한 없이, 승인된 작업을 준비할 조회·감사 기록·아웃박스 INSERT 권한과 `purge_room_record(uuid)` EXECUTE만 부여한다. A·B 직접 DELETE 권한은 없다.

### 운영 Role Bootstrap

`infra/db/bootstrap_roles.sql`은 운영 DB의 역할과 최소 선행 권한을 준비한다. 역할 이름은 현재 운영 설정에서 확정되지 않았다. `DatabaseGrantsIntegrationTest`의 `homestamp_owner`, `homestamp_migration`, `homestamp_app`, `homestamp_purge`, `homestamp_human`은 테스트 전용 이름이며 운영 이름으로 간주하지 않는다. 실행할 때 다섯 이름을 `psql` 변수로 명시한다. 사람 역할은 사람마다 다른 LOGIN 역할로 한 번씩 준비하고, `grants.sql`도 각 사람 역할에 적용한다.

| 역할 | LOGIN | 상속 및 멤버십 |
|---|---:|---|
| Owner | 아니오 | `NOINHERIT`; 객체 소유 및 Flyway가 `SET ROLE`로 사용 |
| Migration | 예 | `NOINHERIT`; Owner에 `SET TRUE`, `INHERIT FALSE`, `ADMIN FALSE`인 직접 멤버십 하나만 허용 |
| App | 예 | `NOINHERIT`; Owner 멤버십 없음 |
| Purge | 예 | `NOINHERIT`; Owner 멤버십 없음 |
| Human | 예, 사람별 | `NOINHERIT`; Owner 멤버십 없음, 읽기 권한만 `grants.sql`에서 부여 |

모든 역할은 `NOSUPERUSER`, `NOCREATEDB`, `NOCREATEROLE`, `NOREPLICATION`, `NOBYPASSRLS`로 생성한다. PostgreSQL 18에서 비슈퍼유저 `CREATEROLE` 실행자는 생성한 역할마다 `ADMIN`만 가진 멤버십을 받으므로 Bootstrap 수행자도 신뢰된 DB 관리자여야 한다. 이 관리자 멤버십은 `INHERIT FALSE`, `SET FALSE`이며 App·Purge·Human·Migration 역할에 부여하지 않는다. Bootstrap은 로그인 역할의 비밀번호를 만들거나 변경하지 않는다. 운영 자격증명은 별도 안전한 계정 준비 절차에서 설정하고 해당 역할별 SSM 경로에 공급한다. 비밀번호를 SQL, 셸 인자, 로그에 넣지 않는다.

실행 전용 DB 관리자는 역할 생성 권한(`CREATEROLE`) 외에도 대상 DB에 `CREATE`·`CONNECT` 권한을 부여할 수 있어야 하며, `public` 스키마 ACL을 바꿀 권한도 있어야 한다. 스크립트는 대상 데이터베이스에 Owner `CREATE`, 네 LOGIN 역할의 `CONNECT`를 부여하고 `PUBLIC`의 임시 객체 생성 권한을 회수하며, `public` 스키마의 `PUBLIC` 권한을 회수한 뒤 Owner에 `USAGE, CREATE`를 준다. 따라서 App·Purge·Human은 임시 테이블도 만들 수 없다. 기존 역할 속성·역할 멤버십·직접 `public` ACL이 기대와 다르면 자동 수정하지 않고 실패한다. 이미 있는 `record`, `state`, `log` 스키마와 객체가 Owner 소유가 아니거나, `public.flyway_schema_history`가 Owner 소유가 아니면 소유권을 바꾸지 않고 중단한다. 실행은 `psql`에서 `ON_ERROR_STOP`을 켜고 트랜잭션으로 처리하므로 오류는 0이 아닌 종료와 롤백으로 이어져야 한다.

```sh
psql -X -v ON_ERROR_STOP=1 \
  -v owner_role='<owner identifier>' \
  -v migration_role='<migration login identifier>' \
  -v app_role='<app login identifier>' \
  -v purge_role='<purge login identifier>' \
  -v human_role='<one-person read-only login identifier>' \
  -d '<dedicated database>' -f infra/db/bootstrap_roles.sql
```

이 명령에는 비밀번호를 전달하지 않는다. 실행자는 지정한 전용 DB가 맞는지, 기존 데이터·Flyway 이력·객체 소유자를 먼저 확인한다. 최초 설정 순서는 **Role Bootstrap → Migration 로그인으로 Owner `SET ROLE` 후 Flyway → `grants.sql` → App 시작**이다. Migration 또는 GRANT가 실패하면 새 App을 시작하지 않는다.

새 빈 DB에서는 Bootstrap이 역할·DB 권한·public ACL을 준비한 뒤 이 순서를 진행한다. 기존 DB에는 이 스크립트를 바로 적용하지 않는다. 먼저 데이터 보존, Flyway 이력, 모든 기존 객체의 소유자와 역할 멤버십을 읽기 전용으로 조사한다. 기존 객체가 Owner 소유가 아니면 이 스크립트는 소유권을 이전하지 않으며, 별도 검토·백업·복구 계획이 정해질 때까지 중단한다. Testcontainers PostgreSQL 18.6 검증은 로컬 동작만 보장하며 실제 RDS 관리 계정에서의 실행 권한과 운영 데이터 상태는 확인하지 않았다.

호실 삭제 함수의 PUBLIC 실행 권한은 V1에서 제거한다. 운영 역할 이름을 확정한 뒤 배포 설정에서 USAGE와 호실 삭제 함수 EXECUTE를 삭제 작업 역할에만 부여한다. 앱이 소유자로 접속하면 보호 경계를 우회할 수 있으므로 운영 접속 계정도 반드시 확인한다. 사람별 접근 기록은 04 「운영 접근」을 따른다.

`room_record`는 C여도 일반 앱의 직접 삭제를 허용하지 않는다. 권한을 잘못 줬더라도 앱이 purge 플래그를 켜고 부모를 삭제해 A·B를 연쇄 삭제할 수 없어야 한다. 부모 삭제 진입점에서도 소유자와 플래그를 검사하며, 자식의 소유자 검사만으로 보호됐다고 판단하지 않는다.

### 스키마별 권한 적용

스키마 배치는 05 0장 「DB 스키마와 권한」이 원본이다. 실제 역할 이름은 배포에서 정하며 V1은 운영 역할을 만들거나 GRANT하지 않는다.

- 소유자 역할로 `ALTER DEFAULT PRIVILEGES FOR ROLE <소유자> IN SCHEMA record GRANT SELECT, INSERT ON TABLES TO <앱>`을 설정한다. 새 `state` 테이블에는 SELECT·INSERT만 기본 부여하고, UPDATE·DELETE는 분류된 기존 C 테이블에 명시적으로 부여한다. `log`에는 INSERT를 기본 부여한다. 이미 마이그레이션된 테이블에는 `GRANT ... ON ALL TABLES IN SCHEMA ...`와 객체별 C 권한을 함께 적용한다.
- `state.part_template`·`state.region`은 고정 조회 데이터이므로 앱의 INSERT·UPDATE·DELETE를 회수하고 SELECT만 남긴다. `state.sms_opt_out`은 SELECT·INSERT·DELETE만 허용한다.
- 앱에 `record`, `state`, `log` USAGE를 주고, 스키마 CREATE·public 접근·소유자 멤버십은 주지 않는다. Flyway는 소유자로 실행하며 이력은 `public`, 관리 스키마는 `public,record,state,log`다.
- 삭제 작업 역할은 필요한 `record`·`state`·`log` 조회, 감사·아웃박스 INSERT와 `state.purge_room_record(uuid)` EXECUTE만 받는다. 앱에는 이 함수 EXECUTE가 없다.
- 앱에는 `record.purge_member_acknowledgements(uuid, timestamptz)` EXECUTE만 예외로 준다. 함수는 PUBLIC EXECUTE를 제거하며 B 직접 DELETE는 허용하지 않는다.
- 기본 함수 EXECUTE 권한도 소유자 역할에서 `ALTER DEFAULT PRIVILEGES ... REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC`으로 회수한다. V1의 두 삭제 함수는 생성 직후 명시적으로 회수한다.
- SQL에는 스키마를 붙이며 `search_path`를 권한 경계로 믿지 않는다. 엔티티도 `@Table(schema = ...)`를 사용한다.

## 호실 밖 삭제

| 대상 | 실행 주체·조건 | 함께 남기는 것 |
|---|---|---|
| `sms_opt_out` | 앱 역할. 번호 주인의 가입·번호 인증 완료와 같은 트랜잭션에서 해당 번호 HMAC의 행만 삭제. 인증 코드 발급·검증 단계에서는 삭제하지 않음 | `deletion_log`에 `target_kind = 'SMS_OPT_OUT'`, `target_id = NULL`, `phone_hmac = 지운 번호 HMAC`을 기록하고 `AUDIT_WRITE` 아웃박스를 함께 남긴다. 과거 `OPT_OUT` 행동은 유지 |
| `member_acknowledgement` | 앱이 탈퇴 처리 전용 함수를 호출. 함수는 소유자 권한으로 회원 행을 잠그고 `WITHDRAWN`인지 검사한 뒤 그 회원의 확인 기록만 삭제. 활성 회원이면 거절 | 확인 기록별 삭제 감사와 `AUDIT_WRITE` 아웃박스. 회원 개인정보 가림과 같은 트랜잭션에서 처리하며 하나라도 실패하면 롤백 |

탈퇴 전용 함수는 PUBLIC EXECUTE를 제거하고 앱에 해당 함수의 EXECUTE만 허용한다. 일반 B 삭제 권한이나 호실 삭제 함수 실행 권한으로 넓히지 않는다. 함수는 안전한 `search_path`와 명시적인 스키마 참조를 사용하고 purge 플래그는 호출 전 값으로 복구한다. 본인 인증과 탈퇴 권한은 계정 서비스가 확인한다. 함수는 `record.purge_member_acknowledgements(uuid, timestamptz)`다. 두 번째 인자는 서비스 Clock의 시각이며 NULL은 거절한다. 함수 안에서 기록별 UUID v7 삭제 감사와 `AUDIT_WRITE`를 만들고, 아웃박스 payload에는 `deletionLogId`만 담는다. 다시 호출해도 이미 지운 기록은 중복 감사하지 않는다.

## 호출 트랜잭션

1. 삭제 자격(보관 기한·탈퇴 규칙)을 확인하고 호실 행부터 잠근다. 모든 기록 작성자는 같은 호실 잠금 순서를 따른다.
2. 삭제 대상 행동마다 UUID를 발급해 `deletion_log`에 원본 ID·호실·번호·prev_hash·hash와 삭제 이유를 보존한다. 이 값 중 하나라도 원본과 다르면 함수가 거절한다.
3. 원본 키를 읽을 수 있을 때 S3_DELETE 작업과 삭제 감사 보관용 AUDIT_WRITE 작업을 아웃박스에 넣는다. 파일 삭제와 DB 삭제를 별개 트랜잭션으로 먼저 실행하지 않는다.
4. 삭제 작업 역할로 `purge_room_record`를 호출하고 커밋한다. 함수는 호실을 잠그고 행동 감사 기록을 대조한 뒤 내부에서만 purge 플래그를 켠다. 종료 시 기존 플래그 값으로 되돌린다. 오류가 나면 전체 트랜잭션을 롤백한다.
5. 아웃박스 작업자가 S3 파일 삭제와 감사 보관을 재시도한다. 함수는 아웃박스 생성 여부·삭제 기한을 검사하지 않으므로 이 단계까지 호출 서비스가 책임진다.

호실 직접 소유 FK는 CASCADE지만 `joined_into_id`는 NO ACTION이다. 다른 호실이 합치기 대상으로 참조하면 삭제를 거절하며 참조 정리 정책을 먼저 적용해야 한다. 삭제 감사·아웃박스·계정·지역·템플릿은 호실과 함께 지워지지 않는다.

## 배포 검증

일회용 PostgreSQL 테스트에서는 일반 역할의 플래그 우회 거절, 기본 함수 실행 거절, 전용 역할의 함수 실행, 감사 기록 누락·불일치 거절, 지문 수정·TRUNCATE 거절, 호실 연쇄 삭제와 다른 호실 보존을 확인한다. 운영에서도 앱 역할이 소유자가 아닌지와 함수 EXECUTE 권한 분리를 읽기 전용 권한 조회로 확인한 뒤 삭제 작업자를 연결한다.

추가할 회귀 시나리오는 앱에 잘못 부여된 부모 DELETE + purge 플래그의 우회 거절, 수신 거부 해제·감사 저장의 원자성, 활성 회원의 약관 기록 삭제 거절, 탈퇴한 회원의 확인 기록만 삭제, 다른 회원의 확인 기록·공동 호실의 공유 동의 보존이다.
