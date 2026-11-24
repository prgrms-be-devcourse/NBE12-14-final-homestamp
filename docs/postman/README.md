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

가입한 회원으로 하려면 2~4 대신 아래 "가입·로그인"을 따른다. **로그인**을 보내면 `bearer_token_15h5`가 그 회원의 토큰(15분)으로 바뀐다.

### 상대 측으로 시험할 때 (하자 응답 등)

하자 응답처럼 등록한 쪽의 **상대**가 부르는 API는 두 번째 회원이 필요하다. 초대·연결 API가 아직 없어서 DB에서 바로 연결한다.

1. 두 번째 회원을 넣는다
   ```sql
   INSERT INTO state.member (id, status, created_at, version)
   VALUES ('0190b6c6-0000-7000-8000-000000000002', 'ACTIVE', now(), 0);
   ```
2. **호실 만들기**를 보낸 뒤, 비어 있는 상대 자리(만든 사람의 반대 측)에 그 회원을 연결한다
   ```sql
   UPDATE state.party
   SET member_id = '0190b6c6-0000-7000-8000-000000000002', status = 'CONNECTED', connected_at = now()
   WHERE room_record_id = '<roomId>' AND member_id IS NULL;
   ```
3. `./gradlew -q localAccessToken --args=0190b6c6-0000-7000-8000-000000000002`로 토큰을 만들어 `counterpart_token`의 Current value에 넣는다

순서 예: 하자 등록(나) → 하자 응답 `NEED_MORE`(상대) → 보충하고 다시 확인 요청(나). 사이사이 **수리 건 상세**를 보내면 지금 누를 수 있는 버튼(`availableActions`)이 바뀌는 것을 볼 수 있다(상대 측으로 보려면 인증을 `counterpart_token`으로 바꾼다). 같은 수리 건에 보충은 7일에 한 번이라, 다시 시험하려면 하자를 새로 등록한다.

하자 등록·보충의 `uploadTickets`는 실제 업로드(#112) 전이라 가짜 사진 창구가 아무 글자나 받는다. 본문의 `{{$guid}}`가 보낼 때마다 새 값을 만든다. 하자 등록은 사진이 한 장 이상 있어야 한다(없으면 `PHOTO_REQUIRED`).

## 가입·로그인 (01 auth · member)

순서대로 보낸다: **인증 문자 보내기** → (`verificationCode` 넣기) → **인증번호 확인** → **가입** → **로그인** → **토큰 재발급** / **로그아웃**

요청끼리 값은 변수로 넘어간다. 직접 넣는 것은 `verificationCode` 하나다.

| 요청 | 쓰는 변수 | 채우는 변수 |
|---|---|---|
| 인증 문자 보내기 | `signupPhone` | 보내기 전 `signupPhone`·`signupEmail`을 새로 만들고 `verificationCode`·`verificationTicket`을 비움, 201이면 `phoneVerificationId` |
| 인증번호 확인 | `phoneVerificationId`(주소), `verificationCode`(본문) | 200이면 `verificationTicket` |
| 가입 | `signupEmail`, `verificationTicket` | - |
| 로그인 | `signupEmail`(비밀번호는 `homestamp123`) | 200이면 `bearer_token_15h5`, `refreshToken` |

### 인증번호 알아내기

로컬은 문자를 보내지 않는다(가짜 발송기). 발급한 번호는 서버 메모리에만 있고 DB에는 해시(`code_hash`)만 남는다. 아래 둘 중 하나로 번호를 정하고, 컬렉션 **Variables**의 `verificationCode` Current value에 넣은 뒤 **인증번호 확인**을 보낸다. `<phoneVerificationId>`는 Variables에 들어 있는 값이다. 번호는 5분 안에 써야 한다(지나면 `CODE_EXPIRED`, 다시 보낸다).

- **A. 발급한 번호 찾기**: 해시를 읽어 local 고정 키(`application.yml`의 `app.keys.verify-code`)로 6자리를 모두 맞춰 본다. 저장소 루트의 Git Bash에서 (DB는 읽기만 한다)
  ```bash
  ID=<phoneVerificationId>
  HASH=$(cd back && docker compose exec -T postgres psql -U homestamp -d homestamp -tAc "SELECT code_hash FROM state.phone_verification WHERE id = '$ID'")
  node -e "const c=require('crypto');const[id,h]=process.argv.slice(1);for(let i=0;i<1e6;i++){const s=String(i).padStart(6,'0');if(c.createHmac('sha256','local-test-only-verify-code-key').update(id+':'+s).digest('hex')===h){console.log(s);break}}" "$ID" "$HASH"
  ```
  나온 6자리가 발급한 번호다. 아무것도 안 나오면 `ID`가 틀렸거나 그 행이 없다
- **B. 번호를 `123456`으로 바꾸기**: DB의 해시를 바꾼다. 그 뒤 `verificationCode`에 `123456`을 넣는다
  ```sql
  CREATE EXTENSION IF NOT EXISTS pgcrypto;  -- 처음 한 번
  UPDATE state.phone_verification
     SET code_hash = encode(hmac(id::text || ':123456', 'local-test-only-verify-code-key', 'sha256'), 'hex')
   WHERE id = '<phoneVerificationId>';
  ```

### 겹치지 않는 이메일·번호

- **인증 문자 보내기**가 보낼 때마다 `signupPhone`(`010-xxxx-xxxx`)과 `signupEmail`(`seoyun+<시각>@example.com`)을 새로 만든다. 가입·로그인은 그 `signupEmail`을 쓴다. 그래서 가입할 때마다 문자 보내기부터 다시 하면 `EMAIL_TAKEN`·`RATE_LIMITED`에 걸리지 않는다
- 같은 번호로는 60초 안에 다시 받을 수 없고(`RATE_LIMITED`) 하루 5번까지다(`DAILY_LIMIT`). 이 오류를 일부러 시험할 때는 본문의 `{{signupPhone}}`을 고정 번호로 바꿔 두 번 보낸다
- 이미 가입한 회원으로 로그인만 할 때는 **로그인** 본문의 `{{signupEmail}}`을 그 이메일로 바꾼다
- 인증 표(`verificationTicket`)는 15분, 가입에 한 번만 쓴다. 같은 표로 다시 가입하면 `CODE_EXPIRED`다
- 리프레시 쿠키는 `Secure`라서 `http://localhost`에서는 Postman이 다시 보내지 않을 수 있다. 그래서 **로그인**·**토큰 재발급**이 쿠키 값을 `refreshToken`에 저장하고, **토큰 재발급**은 `Cookie` 머리로 직접 보낸다. `Origin: http://localhost:5173`(local `app.origin`)도 머리에 있다. 빠지면 `LOGIN_REQUIRED`다
- **로그아웃**은 `refreshToken`을 비운다. 그 뒤 재발급은 `LOGIN_REQUIRED`다(지운 쿠키를 다시 보내면 `REFRESH_REUSED`)
- 공개 API(인증·가입·로그인·재발급)는 요청의 Authorization을 **No Auth**로 둔다

## 변수

| 변수 | 값 | 채우는 법 |
|---|---|---|
| `baseUrl` | `http://localhost:8080` | 그대로 |
| `bearer_token_15h5` | 액세스 토큰 | 직접(개발용 토큰, 만료되면 다시 만들어 넣음) 또는 **로그인**·**토큰 재발급**을 보내면 자동 |
| `signupPhone` | 인증받을 휴대폰 번호 | **인증 문자 보내기**를 보낼 때마다 새로 자동 |
| `signupEmail` | 가입·로그인 이메일 | **인증 문자 보내기**를 보낼 때마다 새로 자동 |
| `phoneVerificationId` | 휴대폰 인증 ID | **인증 문자 보내기**를 보내면 자동 |
| `verificationCode` | 인증번호 6자리 | 직접(위 "인증번호 알아내기") |
| `verificationTicket` | 인증 표 | **인증번호 확인**을 보내면 자동 |
| `refreshToken` | 리프레시 토큰 | **로그인**·**토큰 재발급**을 보내면 자동, **로그아웃**하면 비움 |
| `roomId` | 호실 ID | **호실 만들기**를 보내면 자동 |
| `partId` | 부위 ID | 부위 조회 API가 아직 없어서 DB에서 찾아 직접 넣는다: `SELECT id, zone, name FROM state.room_part WHERE room_record_id = '<roomId>';` |
| `repairCaseId` | 수리 건 ID | **하자 등록**을 보내면 자동 |
| `counterpart_token` | 상대 측 회원의 개발용 토큰 | 직접(위 "상대 측으로 시험할 때") |

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
