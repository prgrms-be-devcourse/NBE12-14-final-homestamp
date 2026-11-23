# Postman 컬렉션 (로컬 API 시험)

`homestamp-local.postman_collection.json`은 로컬(`local` 프로필)에서 API를 손으로 시험하는 컬렉션이다. 서버는 `cd back && ./gradlew bootRun`으로 띄운다(`docs/setup.md` 7장).

## 처음 한 번

1. Postman → **Import** → 이 파일
2. 회원 한 명을 DB에 둔다. 가입 API는 휴대폰 인증이 필요해서 로컬에서는 행을 바로 넣는 게 빠르다
   ```sql
   INSERT INTO state.member (id, status, created_at, version)
   VALUES ('0190b6c6-0000-7000-8000-000000000001', 'ACTIVE', now(), 0);
   ```
3. 개발용 토큰을 만든다(24시간, `docs/setup.md` 7장 "개발용 토큰")
   ```
   cd back
   ./gradlew -q localAccessToken --args=0190b6c6-0000-7000-8000-000000000001
   ```
4. 컬렉션 **Variables**에서 `bearer_token_15h5`의 **Current value**에 그 토큰(`eyJ...`, `Bearer` 없이)을 넣고 저장한다

## 변수

| 변수 | 값 | 채우는 법 |
|---|---|---|
| `baseUrl` | `http://localhost:8080` | 그대로 |
| `bearer_token_15h5` | 개발용 토큰 | 직접(만료되면 다시 만들어 넣음) |
| `roomId` | 호실 ID | **호실 만들기**를 보내면 자동 |
| `partId` | 부위 ID | 부위 조회 API가 아직 없어서 DB에서 찾아 직접 넣는다: `SELECT id, zone, name FROM state.room_part WHERE room_record_id = '<roomId>';` |
| `repairCaseId` | 수리 건 ID | **하자 등록**을 보내면 자동 |

- **Initial value는 비워 둔다.** 파일로 내보내면 Initial value가 들어가서 저장소에 올라간다. 토큰과 각자 로컬 DB의 ID는 Current value에만 둔다(`baseUrl`만 예외).
- 요청 머리에 `Idempotency-Key`를 적지 않으면 컬렉션 스크립트가 요청마다 새 UUID를 붙인다. 같은 키로 다시 보내기를 시험할 때만 그 요청에 키를 직접 적는다(두 번째 응답에 `Idempotent-Replayed: true`, 06 4-0).
- 주소에 `//`가 보이면(예: `/api/rooms//repair-cases`) 변수가 비어 있다는 뜻이다. 이때 응답은 401 `LOGIN_REQUIRED`로 나와서 토큰 문제처럼 보인다.

## 폴더와 주인

폴더는 도메인별이고 주인은 `docs/rules/07-WBS.md` 0장(`.github/CODEOWNERS`)과 같다. 각자 자기 폴더에 API를 더하고, 이 파일을 다시 내보내 PR로 올린다.

| 폴더 | 주인 | 06 |
|---|---|---|
| 01 auth · member | A | 4-1 |
| 02 room | A | 4-3 |
| 03 repair | B | 4-6 |
| 04 inspection | A | 4-7 |
| 05 payment · proposal · settlement | C | 4-6·4-8 |
| 06 photo | C | 4-5 |
| 07 notification · link | D | 4-2·4-4·4-9 |

- 토큰(Authorization)과 `Idempotency-Key` 스크립트는 **컬렉션**에만 둔다. 요청은 Inherit로 두거나 같은 `{{bearer_token_15h5}}`를 쓴다.
- 응답에서 ID를 저장하는 스크립트는 그 ID를 돌려주는 요청의 **Post-response**(Tests)에 둔다.
- 내보낼 때는 **Collection v2.1**, 파일 이름은 그대로(`homestamp-local.postman_collection.json`). 올리기 전에 토큰(`eyJ`)·실제 ID가 파일에 없는지 본다.
