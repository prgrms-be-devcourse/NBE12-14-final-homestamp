# DB 보관 삭제

## 적용 범위

V1은 호실 통째 삭제 함수 `state.purge_room_record(uuid)`와 보호 제약을 만든다. 실제 운영 계정·접속 설정은 바꾸지 않는다. 보관 기한 판단·S3 삭제 작업자·일부 비공개 초안 삭제 함수·해시 체인 검증 API는 후속 구현이다.

V1은 부모 삭제 보호·수신 거부 UPDATE 금지·회원 확인 기록 탈퇴 전용 함수를 포함한다. 앱의 가입·인증·탈퇴 서비스 연결과 실제 운영 역할·GRANT 적용은 배포 전에 완료한다.

## 역할 분리

- 소유자 역할(Owner, NOLOGIN): 보호 테이블과 삭제 함수의 동일한 소유자. 마이그레이션 역할(Migration, LOGIN)은 객체를 직접 소유하지 않고 `SET ROLE`로 Owner가 되어 Flyway·GRANT를 실행한다(아래 역할 표). 앱·삭제 작업·Human에는 Owner 멤버십을 주지 않는다.
- 앱 역할: superuser·소유권·관리 역할 멤버십·스키마 CREATE·TRUNCATE 없음. record의 A·B에는 SELECT·INSERT만, log의 삭제 감사에는 INSERT만 부여하고 직접 DELETE 및 `purge_room_record` EXECUTE는 주지 않는다. C의 DML과 신원 정보 삭제는 필요한 범위만 부여한다. 호실 밖 약관 확인 기록의 탈퇴 전용 함수만 아래 조건으로 EXECUTE를 허용한다.
- 삭제 작업 역할: superuser·소유권·역할 관리 권한 없이, 승인된 작업을 준비할 조회·감사 기록·아웃박스 INSERT 권한과 `purge_room_record(uuid)` EXECUTE만 부여한다. A·B 직접 DELETE 권한은 없다.

### 운영 Role Bootstrap

`infra/db/bootstrap_roles.sql`은 운영 DB의 역할과 최소 선행 권한을 준비한다. 역할 이름은 현재 운영 설정에서 확정되지 않았다. 통합 테스트의 `homestamp_owner`, `homestamp_migration`, `homestamp_app`, `homestamp_purge`, `homestamp_human_read`(`DatabaseRoleBootstrapIntegrationTest`, `DatabaseGrantsIntegrationTest`의 `homestamp_human`도 같다)는 테스트 전용 이름이며 운영 이름으로 간주하지 않는다. 실행할 때 다섯 이름을 `psql` 변수로 명시한다. `human_role`은 사람 계정이 아니라 NOLOGIN Human 읽기 그룹(예: `homestamp_human_read`, decisions 2026-11-25)이다. GRANT는 이 그룹에 한 번만 주고, 사람별 LOGIN 역할은 Bootstrap이 만들지 않으며 아래 「사람별 Human 계정」 절차로 그룹에 넣고 뺀다.

| 역할 | LOGIN | 상속 및 멤버십 |
|---|---:|---|
| Owner | 아니오 | `NOINHERIT`; 객체 소유 및 Flyway가 `SET ROLE`로 사용 |
| Migration | 예 | `NOINHERIT`; Owner에 `SET TRUE`, `INHERIT FALSE`, `ADMIN FALSE`인 직접 멤버십 하나만 허용 |
| App | 예 | `NOINHERIT`; Owner 멤버십 없음 |
| Purge | 예 | `NOINHERIT`; Owner 멤버십 없음 |
| Human 읽기 그룹 | 아니오 | `NOINHERIT`; Owner 멤버십 없음, 읽기 권한만 `grants.sql`에서 부여 |
| 사람별 Human 계정 | 예, 사람마다 | Bootstrap이 만들지 않음. `NOINHERIT`, `VALID UNTIL` 필수; Human 읽기 그룹에 `INHERIT TRUE`, `SET FALSE`, `ADMIN FALSE` 멤버십 하나만, 직접 권한 없음 |

모든 역할은 `NOSUPERUSER`, `NOCREATEDB`, `NOCREATEROLE`, `NOREPLICATION`, `NOBYPASSRLS`로 생성한다. PostgreSQL 18에서 비슈퍼유저 `CREATEROLE` 실행자는 생성한 역할마다 `ADMIN`만 가진 멤버십을 받으므로 Bootstrap 수행자도 신뢰된 DB 관리자여야 한다. 이 관리자 멤버십은 `INHERIT FALSE`, `SET FALSE`이며 App·Purge·Human·Migration 역할에 부여하지 않는다. Bootstrap은 로그인 역할의 비밀번호를 만들거나 변경하지 않는다. 운영 자격증명은 별도 안전한 계정 준비 절차에서 설정한다. 공급 경로는 역할마다 다르다. App은 앱 SSM 경로(`/homestamp/prod/app/`, #165)에서 읽는다. Migration은 SSM이 아니라 호스트가 Migration 컨테이너에만 마운트하는 전용 파일(`/run/secrets/homestamp-migration/`, B-1′, #173)에서 읽는다. 웹 EC2 역할은 앱 경로만 읽기 때문이다(#189). Purge·Human 자격증명의 공급 경로와 비밀번호 최초 발급·교체·복구 절차는 아직 정하지 않았다. 비밀번호를 SQL, 셸 인자, 로그에 넣지 않는다.

실행 전용 DB 관리자는 역할 생성 권한(`CREATEROLE`) 외에도 대상 DB에 `CREATE`·`CONNECT` 권한을 부여할 수 있어야 하며, `public` 스키마 ACL을 바꿀 권한도 있어야 한다. 스크립트는 대상 데이터베이스에 Owner `CREATE`, Migration·App·Purge와 Human 읽기 그룹의 `CONNECT`(사람 계정은 그룹에서 상속)를 부여하고 `PUBLIC`의 임시 객체 생성 권한을 회수하며, `public` 스키마의 `PUBLIC` 권한을 회수한 뒤 Owner에 `USAGE, CREATE`를 준다. 따라서 App·Purge·Human은 임시 테이블도 만들 수 없다. 기존 역할 속성·역할 멤버십·직접 `public` ACL이 기대와 다르면 자동 수정하지 않고 실패한다. `public` 스키마 소유자가 Migration·App·Purge·Human 읽기 그룹이나 그 그룹의 사람 계정이면 소유권을 바꾸지 않고 실패한다(ACL 검사는 소유자의 암묵 권한을 보지 못하므로 따로 검사한다). 그룹을 만들 때 ADMIN 전용 멤버십을 받은 실행 관리자는 사람 계정으로 보지 않으므로(위 멤버십 검사와 같은 예외), 그 관리자가 `public`을 소유해도 통과한다. 사람 계정이 Human 읽기 그룹에 `INHERIT TRUE`, `SET FALSE`, `ADMIN FALSE`로 들어 있는 것만 허용하고, 다른 옵션의 그룹 멤버십은 실패한다. 이미 있는 `record`, `state`, `log` 스키마와 객체가 Owner 소유가 아니거나, `public.flyway_schema_history`가 Owner 소유가 아니면 소유권을 바꾸지 않고 중단한다. 실행은 `psql`에서 `ON_ERROR_STOP`을 켜고 트랜잭션으로 처리하므로 오류는 0이 아닌 종료와 롤백으로 이어져야 한다.

```sh
psql -X -v ON_ERROR_STOP=1 \
  -v owner_role='<owner identifier>' \
  -v migration_role='<migration login identifier>' \
  -v app_role='<app login identifier>' \
  -v purge_role='<purge login identifier>' \
  -v human_role='<NOLOGIN human read group identifier>' \
  -d '<dedicated database>' -f infra/db/bootstrap_roles.sql
```

이 명령에는 비밀번호를 전달하지 않는다. 실행자는 지정한 전용 DB가 맞는지, 기존 데이터·Flyway 이력·객체 소유자를 먼저 확인한다.

#### 사람별 Human 계정

사람 계정은 Bootstrap 밖에서 신뢰된 DB 관리자가 한 명씩 만든다. 실제 이름·비밀번호를 이 문서나 저장소, 셸 기록에 남기지 않는다. 계정 이름은 사람을 구별할 수 있되 실명이 아닌 식별자로 정한다.

1. 발급: `CREATE ROLE <사람 식별자> LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS VALID UNTIL '<만료 시각>';` 다음 `GRANT <Human 읽기 그룹> TO <사람 식별자> WITH INHERIT TRUE, SET FALSE, ADMIN FALSE;` 비밀번호는 psql `\password`처럼 화면·셸 인자·로그에 남지 않는 방법으로 설정한다(공급 경로는 미정, #41).
2. 확인: `psql -X -v ON_ERROR_STOP=1 -v human_role='<Human 읽기 그룹>' -d '<전용 DB>' -f infra/db/check_human_access.sql`. 읽기 전용 트랜잭션이라 아무것도 고치지 않고, 아래 중 하나라도 있으면 0이 아닌 종료로 실패한다.
   - 그룹이 LOGIN이거나 관리 속성·다른 역할 멤버십이 있음
   - 사람 계정이 LOGIN `NOINHERIT`가 아니거나 관리 속성이 있음, `VALID UNTIL`이 없음
   - 그룹 멤버십 옵션이 `INHERIT TRUE`·`SET FALSE`·`ADMIN FALSE`가 아님
   - 그룹 밖 멤버십(`pg_read_all_data`, Owner, App 등), 다른 역할이 사람 계정을 쓸 수 있는 멤버십
   - 사람 계정에 직접 준 테이블·컬럼·스키마·함수·DB·기본 권한, 사람 계정이 소유한 객체. 그 밖의 카탈로그(언어·타입·large object·파라미터 등)와 같은 클러스터의 다른 DB에 남은 소유·권한·정책은 `pg_shdepend`로 잡는다
   사람 추가·회수 뒤와 운영 배포 전에 실행한다. `grants.sql`은 사람 계정을 보지 않으므로 직접 권한은 이 점검으로만 잡는다. 점검 대상은 그룹의 멤버뿐이다. `PUBLIC`에 준 권한(Bootstrap·`grants.sql`이 관리)과, 그룹에서 빠졌지만 DROP되지 않은 LOGIN 역할은 보지 않으므로 회수 절차(4)를 끝까지 지킨다. 성공 메시지에는 점검한 사람 수와, 예외로 건너뛴 관리자(CREATEROLE·superuser의 ADMIN 전용 멤버십) 이름이 나온다. 사람 역할 자체를 다른 역할에 부여한 것도 같은 기준이다: CREATEROLE·superuser에게 준 ADMIN 전용(INHERIT·SET 없음)만 허용하고, 일반 LOGIN 역할의 ADMIN 전용을 포함해 나머지는 모두 실패한다.
3. 연장: 만료 전에 `ALTER ROLE <사람 식별자> VALID UNTIL '<새 만료 시각>';`
4. 회수: 아래 순서를 지킨다.
   1. `REVOKE <Human 읽기 그룹> FROM <사람 식별자>;` (권한 회수)
   2. `ALTER ROLE <사람 식별자> NOLOGIN;` (새 로그인 차단)
   3. `SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = '<사람 식별자>';` (열린 세션 종료)
   4. 남은 소유 객체가 없는지 확인한 뒤 `DROP ROLE <사람 식별자>;`

`VALID UNTIL`은 만료 뒤 **비밀번호 로그인만** 막는다. 이미 열린 세션은 끊지 않으며, 그 세션은 그룹 권한으로 계속 조회할 수 있다(PostgreSQL 18 통합 테스트에서 확인). 그래서 만료·회수 때는 세션 종료(4-3)를 따로 한다. 또 `session_user`로 누가 접속했는지는 알 수 있지만 어떤 SQL을 실행했는지는 남지 않는다. 사람별 조회 기록은 쿼리 로그와 RDS 로그 내보내기(04 「운영 접근」)로 남긴다.

최초 설정과 배포는 실행 주체와 방식이 서로 다른 네 단계다. 각 단계는 따로 성공해야 다음으로 넘어간다.

| 단계 | 실행 주체 | 방식 | 상태 |
|---|---|---|---|
| 1. Role Bootstrap | 신뢰된 DB 관리자(Migration 계정 아님) | `psql`로 `bootstrap_roles.sql` | 최초 한 번, 역할이 바뀔 때 |
| 2. Migration | Migration 로그인 → `SET ROLE` Owner | 앱 이미지 `--migrate`(Flyway) | #173 Draft |
| 3. GRANT | Migration 로그인 → `SET LOCAL ROLE` Owner | 앱 이미지 안의 JDBC 리소스 `db/grants/grants.sql`(아래 "실행 방식") | 실행 진입점 `--apply-grants`는 #173 후속, 미구현. 지금 계약은 테스트 실행기(`DatabaseGrantsIntegrationTest.GrantsJdbcExecutor`)로만 검증 |
| 4. Green 시작 | 배포 스크립트 | 2·3이 모두 종료 코드 0일 때만 | 배포 스크립트 미구현 |

Bootstrap의 `psql` 실행과 GRANT의 JDBC 실행은 다른 절차다. GRANT를 `psql`로 실행하지 않는다(`psql` 변수와 메타 명령을 쓰지 않는 SQL이고, 잠금·`set_config`를 실행기가 같은 트랜잭션에서 준비해야 한다).

Migration이나 GRANT가 실패하면 Green을 시작하지 않고 Blue를 유지한다. 다만 Blue 유지가 DB 스키마 호환성까지 보장하지는 않는다. Flyway는 버전마다 따로 커밋하므로, 실패 전에 적용된 앞 버전은 남는다. GRANT는 한 트랜잭션이라 실패하면 권한이 실행 전 상태로 돌아간다. 다만 앞 단계에서 적용된 Migration은 되돌리지 않는다.

새 빈 DB에서는 Bootstrap이 역할·DB 권한·public ACL을 준비한 뒤 이 순서를 진행한다. 기존 DB에는 이 스크립트를 바로 적용하지 않는다. 먼저 데이터 보존, Flyway 이력, 모든 기존 객체의 소유자와 역할 멤버십을 읽기 전용으로 조사한다. 기존 객체가 Owner 소유가 아니면 이 스크립트는 소유권을 이전하지 않으며, 별도 검토·백업·복구 계획이 정해질 때까지 중단한다. Testcontainers PostgreSQL 18.6 검증(`DatabaseRoleBootstrapIntegrationTest`: Bootstrap → `--migrate`와 같은 Flyway 설정 → JDBC GRANT → 역할별 실제 권한)은 로컬 동작만 보장한다. 실제 RDS 관리 계정에서의 Bootstrap 리허설, RDS CA 인증서 공급, EC2 비밀 파일 마운트, 운영 데이터 상태는 확인하지 않았다.

운영 배포 전에 다시 점검할 권한 위험:
- Purge는 인증 정보(`password_hash`·토큰 해시 등)를 포함한 모든 테이블을 SELECT한다.
- Human이 테이블 단위로 읽는 자유 서술 기록에는 사용자가 개인정보를 적을 수 있다.
- Human 조회 목적과 범위가 개인정보 처리방침과 맞는지 확인해야 한다.

호실 삭제 함수의 PUBLIC 실행 권한은 V1에서 제거한다. 운영 역할 이름을 확정한 뒤 배포 설정에서 USAGE와 호실 삭제 함수 EXECUTE를 삭제 작업 역할에만 부여한다. 앱이 소유자로 접속하면 보호 경계를 우회할 수 있으므로 운영 접속 계정도 반드시 확인한다. 사람별 접근 기록은 04 「운영 접근」을 따른다.

`room_record`는 C여도 일반 앱의 직접 삭제를 허용하지 않는다. 권한을 잘못 줬더라도 앱이 purge 플래그를 켜고 부모를 삭제해 A·B를 연쇄 삭제할 수 없어야 한다. 부모 삭제 진입점에서도 소유자와 플래그를 검사하며, 자식의 소유자 검사만으로 보호됐다고 판단하지 않는다.

### 스키마별 권한 적용

스키마 배치는 05 0장 「DB 스키마와 권한」이 원본이다. 실제 역할 이름은 배포에서 정하며 V1은 운영 역할을 만들거나 GRANT하지 않는다.

- 소유자 역할로 `ALTER DEFAULT PRIVILEGES FOR ROLE <소유자> IN SCHEMA record GRANT SELECT, INSERT ON TABLES TO <앱>`을 설정한다. 새 `state` 테이블에는 SELECT·INSERT만 기본 부여하고, UPDATE·DELETE는 분류된 기존 C 테이블에 명시적으로 부여한다. `log`에는 INSERT를 기본 부여한다. 이미 마이그레이션된 테이블에는 `GRANT ... ON ALL TABLES IN SCHEMA ...`와 객체별 C 권한을 함께 적용한다.
- `state.part_template`·`state.region`은 고정 조회 데이터이므로 앱의 INSERT·UPDATE·DELETE를 회수하고 SELECT만 남긴다. `state.sms_opt_out`은 SELECT·INSERT·DELETE만 허용한다.
- 앱에 `record`, `state`, `log` USAGE를 주고, 스키마 CREATE·public 접근·소유자 멤버십은 주지 않는다. Flyway는 소유자로 실행하며 이력은 `public`, 관리 스키마는 `public,record,state,log`다.
- 삭제 작업 역할은 필요한 `record`·`state`·`log` 조회, 감사·아웃박스 INSERT와 `state.purge_room_record(uuid)` EXECUTE만 받는다. 앱에는 이 함수 EXECUTE가 없다.
- 사람별 읽기 전용 역할(Human)은 장애 대응 조회에 필요한 범위만 읽는다. 쓰기·TRUNCATE·함수 실행·다른 역할 전환은 없다.
  - 테이블 단위 SELECT는 `grants.sql`의 명시 목록(기록·상태·감사 테이블 31개)에만 준다. 기본 권한(Default Privileges)으로는 주지 않으므로 새 테이블은 분류하기 전까지 읽을 수 없고, 분류되지 않은 테이블이 있으면 GRANT가 실패한다.
  - 민감 테이블 7개는 컬럼 단위 SELECT만 준다. 읽을 수 없는 컬럼: `member`의 `email`·`password_hash`·`display_name`·`phone`, `refresh_token`의 `token_hash`·`device_label`, `phone_verification`의 `phone`·`code_hash`, `link`의 `token_hash`, `party`의 `invite_phone`, `outbox`의 `payload`·`dedupe_key`, `cause_note`의 `expert_name`(제3자의 실제 이름). 이 컬럼은 SELECT 목록·WHERE·`SELECT *`·행 전체 참조 어디에 써도 권한 오류(SQLState `42501`)로 거부된다.
  - 새 컬럼이 Human에게 보이는지는 테이블 분류에 따라 다르다.
    - 컬럼 단위 7개 테이블: 새 컬럼은 `grants.sql`의 허용 컬럼 목록에 넣고 GRANT를 다시 실행하기 전까지 읽을 수 없다.
    - 테이블 단위 31개 테이블: PostgreSQL의 테이블 SELECT는 나중에 추가된 컬럼에도 적용된다. Migration이 컬럼을 추가하는 순간 Human은 GRANT를 다시 실행하지 않아도 그 컬럼을 읽는다.
  - Human이 읽을 수 있는 컬럼 중 이름이 인증·연락처처럼 보이는 것(`phone`·`email`·`token`·`hash`·`hmac`·`code`·`name` 등)은 `grants.sql`의 승인 목록에 있어야 하고, 없으면 GRANT가 실패한다. 이 검사는 GRANT를 실행할 때 찾아내는 안전장치일 뿐이다. 위 테이블 단위 권한 때문에 그 전에 이미 보이는 것은 막지 못하고, 이름이 평범한 민감 컬럼(`memo`·`contact` 등)은 찾지 못한다.
  - **테이블 단위 31개 테이블에 컬럼을 추가하는 Migration PR은 Human이 그 컬럼을 읽어야 하는지와 개인정보가 들어가는지를 반드시 검토한다.** 민감한 컬럼이면 아래 순서를 따른다. Migration 뒤 GRANT가 실패하는 것으로는 대신할 수 없다(실패할 때는 이미 노출된 뒤다).
    1. 그 테이블의 Human 테이블 단위 SELECT를 먼저 줄여야 하는지 판단한다.
    2. 줄여야 하면 먼저 `grants.sql`에서 그 테이블을 컬럼 단위로 옮기는 변경만 배포한다(기존 컬럼은 그대로 허용).
    3. 실제 DB에 적용됐는지 확인한다(그 테이블에 Human 테이블 단위 SELECT가 없음).
    4. 그다음 배포에서 민감 컬럼을 추가하는 Migration을 적용한다.

    두 변경을 같은 배포에 묶지 않는다. 배포 순서가 Migration 다음 GRANT라면 그 사이에 노출되기 때문이다. 권한을 줄이는 것은 App·Purge 권한과 스키마를 바꾸지 않으므로 실행 중인 Blue 앱과 충돌하지 않고, 컬럼 추가도 기존 Expand 단계로 그대로 둔다. (GRANT 실행 진입점과 배포 순서는 #173 후속이며 아직 확정되지 않았다.)
  - 남은 위험: 테이블 단위 테이블에 있는 자유 서술(`cause_note.text`, `description`·`reason`·`progress_note.text` 등, `room_record.alias`)에는 사용자가 개인정보를 적을 수 있다. 서비스 기록 확인에 필요해 지금은 열어 두고, 보안 재점검 때 다시 본다.
- 앱에는 `record.purge_member_acknowledgements(uuid, timestamptz)` EXECUTE만 예외로 준다. 함수는 PUBLIC EXECUTE를 제거하며 B 직접 DELETE는 허용하지 않는다.
- 기본 함수 EXECUTE 권한도 소유자 역할에서 `ALTER DEFAULT PRIVILEGES ... REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC`으로 회수한다. V1의 두 삭제 함수는 생성 직후 명시적으로 회수한다.
- SQL에는 스키마를 붙이며 `search_path`를 권한 경계로 믿지 않는다. 엔티티도 `@Table(schema = ...)`를 사용한다.

GRANT SQL은 `back/src/main/resources/db/grants/grants.sql` 하나다(앱 이미지에 함께 들어가고 Flyway 위치 `db/migration` 밖이라 Flyway가 실행하지 않는다). psql 메타 명령 없이 JDBC로 실행한다. 실행기는 Migration 로그인(NOINHERIT, Owner 멤버십 `SET TRUE`·`INHERIT FALSE`·`ADMIN FALSE`)으로 접속해 자동 커밋을 끄고 `SET LOCAL ROLE "<Owner>"`, `set_config('homestamp.grants.{app,purge,human}_role', ?, true)`, `pg_advisory_xact_lock(1752392040, 156)`을 같은 트랜잭션에서 실행한 뒤 DO 블록 하나를 실행하고 COMMIT한다. 어느 단계든 실패하면 ROLLBACK하므로 권한은 실행 전 상태로 남는다. DO 블록은 Owner로 전환하지 않았거나, 잠금이 없거나, Migration 로그인이 Owner를 상속·관리하면 아무것도 바꾸지 않고 실패한다. 테이블·함수 목록이 이 이미지의 Migration 결과와 다르면 실패하므로, 테이블이나 함수를 추가하는 V 파일은 같은 PR에서 이 파일의 목록과 권한도 고친다. 운영 실행 진입점(`--apply-grants`)과 역할 이름 공급은 Migration Runner(#173) 후속 작업에서 연결한다.

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
