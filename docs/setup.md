# 처음 설정

이 저장소에서 작업하기 전에 **한 번만** 하면 된다. 1~5장은 AI 하네스(Claude Code·Codex), 6장은 IDE 서식, 7장은 로컬 실행이다.

## 1. 모두 (필수)

| 할 일 | 확인 | 왜 |
|---|---|---|
| Node.js 20 이상 설치 | `node -v` | 가드 훅이 `node`로 돈다. **없으면 훅이 안 돌아서 위험한 명령이 그대로 통과한다** |
| 저장소 루트에서 AI 실행 | — | 훅·스킬은 저장소 폴더 기준으로 읽힌다 |

훅을 고쳤다면 `node --test scripts/hooks/hooks.test.mjs`를 돌린다.

## 2. GitHub CLI (선택, 추천)

`/issue`·`/pr-body`(Codex는 `$issue`·`$pr-body`)로 AI가 이슈와 PR을 **직접 올리게** 하려면 필요하다.

```
# 설치
Windows: winget install --id GitHub.cli
macOS:   brew install gh

# 설치 후 IDE(IntelliJ 등)를 완전히 껐다 켠다 ← PATH 반영
gh auth login
  → GitHub.com / HTTPS / Git 인증 Yes / Login with a web browser
  → 브라우저에서 prgrms-be-devcourse 조직 옆 Grant 버튼이 보이면 누른다
gh auth status   # "Logged in" 확인
```

- 무료다 (GitHub API를 부르는 공식 도구)
- 없어도 스킬은 쓸 수 있다. 이슈·PR 초안만 받아서 GitHub에 직접 붙여 넣는다

## 3. Claude Code

따로 할 일은 없다. 저장소에서 `claude`를 실행하면 `CLAUDE.md`(→ `AGENTS.md`), 훅, 스킬이 자동으로 읽힌다.

확인:
- `/hooks` → `PreToolUse`에 `guard-bash`, `guard-secrets`, `guard-migration`이 보인다
- `/` 입력 → `issue`, `pr-body`, `decide` 등이 보인다

## 4. Codex

1. 저장소 폴더에서 `codex` 실행 → 이 프로젝트를 **신뢰(trust)**
2. `/hooks`에서 저장소 훅을 **신뢰**
3. 훅 등록 파일(`.codex/hooks.json`)이 바뀌면 신뢰가 풀린다 → 다시 2번 (그런 PR 본문에 안내가 있다)

확인:
- `$issue`, `$pr-body`가 불린다
- `$` 없이 말했을 때 스킬이 저절로 불리지 않는다

## 5. 훅이 도는지 확인 (공통)

AI에게 `git clean -fd 실행해줘`라고 해 본다. 실행되지 않고 "팀 규칙상 막혀 있습니다"가 나오면 정상이다.

## 6. 코드 서식 (IntelliJ, 모두 필수)

서식의 기준은 CI다(자바 `./gradlew spotlessCheck`, 프론트 `npm run format:check`). IDE는 저장할 때 같은 결과를 내도록 맞춘다. IntelliJ 기본 서식(4칸 들여쓰기)은 구글 자바 스타일(2칸)과 달라서, 그대로 두면 CI에서 떨어진다.

| 할 일 | 방법 |
|---|---|
| 자바 플러그인 | Settings → Plugins → Marketplace에서 **google-java-format** 설치. 버전 **2.0.0.1**(CI의 google-java-format 2.0.0과 같은 판), IntelliJ 2025.1 이상 |
| 켜기 | Settings → Other Settings → **google-java-format Settings** → Enable, 스타일은 Default Google Java style |
| 저장할 때 맞추기 (선택) | Settings → Tools → Actions on Save → Reformat code 켜기 |
| 롬복 | 플러그인은 IntelliJ에 들어 있다. Settings → Build, Execution, Deployment → Compiler → Annotation Processors → **Enable annotation processing**이 켜져 있는지 확인(꺼져 있으면 IDE에만 빨간 줄이 생기고 Gradle 빌드는 된다). 쓰는 규칙은 `docs/rules/09` 2장 |
| 프론트 | Settings → Languages & Frameworks → JavaScript → **Prettier** → Automatic Prettier configuration, Run on save 켜기(`front/node_modules`의 Prettier를 씀) |

확인: 커밋 전에 `cd back && ./gradlew spotlessApply`, `cd front && npm run format`을 돌려도 바뀌는 파일이 없으면 IDE 설정이 맞다.

## 7. 로컬 실행 (모두 필수)

### 준비물

| 준비물 | 확인 | 왜 |
|---|---|---|
| JDK 25 (Temurin 권장) | `java -version` | 백엔드 빌드 기준 |
| Docker Desktop (켜 둔 상태) | `docker info` | 로컬 DB와 테스트(Testcontainers)가 모두 Docker로 뜬다 |
| Node 22 권장(20.19 이상이면 됨, CI는 22) | `node -v` | 프론트(Vite 8은 20.19 이상)와 AI 훅 |

### 실행 순서

```
# 1. 백엔드 (첫 실행은 DB 이미지를 받느라 1~2분)
cd back
./gradlew bootRun          # back/compose.yml의 PostgreSQL 18이 같이 뜨고 연결 정보가 자동으로 들어간다

# 2. 프론트 (다른 터미널)
cd front
npm ci                     # 처음 한 번, package-lock.json이 바뀌었을 때
npm run dev                # http://localhost:5173, /api는 백엔드(8080)로 넘어간다
```

- **설정 파일을 만들 필요가 없다.** DB 주소·계정은 Spring Boot가 `compose.yml`에서 읽어 넣는다(`spring-boot-docker-compose`). `application-local.yml`은 만들지 않는다(`.gitignore`·비밀값 훅이 막는 파일이라 팀이 같은 설정을 나눌 수 없다).
- **데이터는 남는다.** 앱을 꺼도 DB 데이터는 Docker 볼륨(`homestamp_homestamp-db`)에 남는다. 처음부터 다시 하려면 `cd back && docker compose down -v`(볼륨까지 지움, 되돌릴 수 없음).
- **DB 컨테이너가 남아 있을 수 있다.** 앱을 강제로 끄면(터미널을 닫는 등) DB 컨테이너가 계속 떠 있다. 다음 `bootRun`이 그대로 다시 쓰니 문제는 없고, 끄고 싶으면 `cd back && docker compose stop`(데이터는 남음).
- **포트는 실행할 때마다 바뀐다.** 각자 PC의 5432와 부딪히지 않게 고정하지 않았다. IntelliJ Database 창이나 DBeaver로 열 때는 `cd back && docker compose port postgres 5432`로 확인한다(DB·계정·비밀번호 모두 `homestamp`). 이 비밀번호는 **로컬 전용**이라 공개 저장소에 있어도 된다: DB는 내 PC 안(`127.0.0.1`)에서만 접속을 받고 진짜 데이터가 없다. 운영 DB 비밀번호는 Parameter Store에만 둔다(`docs/rules/04` 6장).
- **테스트는 이 DB를 쓰지 않는다.** `./gradlew test`·`build`는 Testcontainers가 따로 DB를 띄운다(CI와 같음).

### 지금 알아 둘 것

- **프로필은 기본 `local`이다.** 다른 프로필로 띄우려면 환경 변수 `SPRING_PROFILES_ACTIVE`를 주거나(`SPRING_PROFILES_ACTIVE=test ./gradlew bootRun`) `--args='--spring.profiles.active=test'`를 붙인다.
- **API 문서(Swagger UI)는 로컬에서만 켜진다.** `bootRun`이 local 프로필로 떠서 http://localhost:8080/swagger-ui/index.html 에 열린다(운영에서는 꺼짐, `docs/rules/04` API 문서). 문서 주소는 local·test 프로필에서만 로그인 없이 열리고, `prod`가 켜지면 열리지 않는다.
- **로그인이 필요한 API는 개발용 토큰으로 부른다.** 서버는 실제 액세스 JWT만 받는다(`Bearer <회원 UUID>`는 이제 401). 로그인 API(`POST /api/auth/login`)가 생기기 전에는 아래처럼 토큰을 만들어 요청 머리에 넣는다. 토큰이 없거나 맞지 않으면 `401 LOGIN_REQUIRED`다. 가입·로그인 같은 공개 API(06 4-1)와 가입 전 링크(`POST /api/public/**`), `/actuator/health`는 토큰 없이 열린다.
  ```
  cd back
  ./gradlew -q localAccessToken --args=0190b6c6-0000-7000-8000-000000000001   # 토큰 한 줄이 나온다(24시간)
  curl -H "Authorization: Bearer <위 토큰>" http://localhost:8080/api/...
  ```
  - 토큰은 local·test 고정 키(`application.yml`)로 서명한다. 서버가 `local`(기본)이나 `test` 프로필로 떠 있어야 받고, `prod`나 프로필 없이 띄운 서버는 받지 않는다. 이 도구는 test 소스에 있어 운영 jar에 들어가지 않는다.
  - 회원이 DB에 있는지는 보지 않는다. 회원 정보를 읽는 API는 `state.member`에 그 UUID의 행이 있어야 한다.
  - 테스트 코드에서는 이 도구 대신 `SecurityTestSupport.loginAs(회원 UUID)`를 쓴다(`mvc.perform(get(...).with(loginAs(id)))`, 같은 키로 만든 토큰).
  - 리프레시 쿠키는 주지 않는다. 재발급·로그아웃까지 확인하려면 로그인 API를 기다린다.
- **테이블은 아직 없다.** V1 마이그레이션(1일차) 전에는 Flyway 기록 테이블만 생긴다.

## 문제가 생기면

| 증상 | 해결 |
|---|---|
| `gh: command not found` | IDE를 완전히 껐다 켠다. 급하면 전체 경로로 실행 (Windows: `"/c/Program Files/GitHub CLI/gh.exe"`) |
| 훅이 안 막는다 | `node -v` 확인 → Claude는 `/hooks`, Codex는 훅 신뢰 상태 확인 → AI 세션을 새로 시작 |
| 막으면 안 되는 명령이 막힌다 | 직접 터미널에서 실행하고, 훅 오탐으로 이슈를 올린다 |
| IDE에서 맞췄는데 `spotlessCheck`가 떨어진다 | google-java-format 플러그인 버전이 2.0.0.x인지 확인. 그래도 다르면 `./gradlew spotlessApply`로 맞추고, `docs/rules/04` 17장 "서식 기준"의 cleanup 시험 결과를 확인 |
| google-java-format 플러그인이 시작할 때 오류를 낸다 | 플러그인 안내에 있는 `--add-exports` 줄을 Help → Edit Custom VM Options에 넣고 IntelliJ를 다시 켠다 |
| `bootRun`이 `Failed to configure a DataSource`로 떨어진다 | Docker Desktop이 켜져 있는지(`docker info`) 확인. IntelliJ에서 실행했다면 작업 폴더가 `back/`인지 확인(`compose.yml`을 그 폴더에서 찾는다) |
| `bootRun`이 Docker Compose 오류로 떨어진다 | `cd back && docker compose up postgres`로 직접 띄워 오류 메시지를 본다. 이미지 받기 실패면 네트워크, 볼륨 오류면 `docker compose down -v`로 지우고 다시 |
| 화면에서 `/api` 요청이 404·연결 거부 | 백엔드(`bootRun`)가 떠 있는지, 프론트를 `npm run dev`로 띄웠는지 확인(프록시는 개발 서버에서만) |
