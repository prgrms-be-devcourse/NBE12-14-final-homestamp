# HomeStamp (임대인·세입자 집 관리)

Java 25 · Spring Boot 4.1 · PostgreSQL · Flyway | React 19 · Vite · TypeScript · Tailwind 4 · shadcn(Base UI)

이 파일은 Claude·Codex가 함께 읽는 팀 규칙의 원본이다. 규칙은 여기에만 쓴다 (`CLAUDE.md`는 `@AGENTS.md` 한 줄).

## 저장소 구조
- `back/` Spring Boot (Gradle). 패키지 루트 `com.chilsuitjo.back`
- `front/` React (Vite). import alias `@/` = `front/src/`
- `.agents/skills/` 스킬 원본 · `.claude/`, `.codex/` 도구별 설정 · `scripts/hooks/` 훅 스크립트
- `docs/` 읽는 순서·주제별 기준 문서는 `docs/README.md` · 규칙 문서 `docs/rules/` · 결정 기록 (`docs/decisions.md`) · AI 실수 기록 (`docs/failure-memory.md`) · 처음 설정 (`docs/setup.md`)

## 자주 쓰는 명령
- 백엔드: `cd back && ./gradlew build` / `./gradlew test` (Windows는 `gradlew.bat`)
- 프론트: `cd front && npm run dev` / `npm run build` / `npm run lint`
- 훅 테스트: `node --test scripts/hooks/hooks.test.mjs` (훅을 고치면 꼭 돌린다)

## 팀 규칙
- 브랜치: `{타입}/{back|front|common}/{설명}` 소문자와 `-`만
  - 예) `feat/back/admin-login`, `chore/common/harness-setup`
  - `common`은 back·front 어느 쪽에도 속하지 않는 저장소 전체 작업 (하네스, CI, 루트 설정, 문서). 예) `docs/common/rules-update`
  - back·front가 함께 바뀌는 작은 작업은 한 브랜치로 하고, 가운데는 더 많이 바뀐 쪽
- 커밋: `타입: 내용` (한국어 한 줄, 50자 이내, 범위 표시 없음)
  - 타입 = `feat` `fix` `test` `docs` `refactor` `chore`
  - 한 커밋에는 한 가지 일만
- 브랜치·커밋은 `git-convention` 스킬로, 이슈는 `issue` 스킬로, PR은 `pr-body` 스킬로 만든다 (모두 사람이 부를 때만)
- 이슈는 `.github/ISSUE_TEMPLATE/task.md`를 따르고, PR 본문의 `closes #번호`로 이슈와 연결한다
- PR 본문은 `.github/pull_request_template.md`를 따른다
- 자바는 구글 자바 스타일. 포매터가 맞춘다 (Spotless 설정 후 커밋 전에 `./gradlew spotlessApply`)

## AI가 지킬 것
- 작업을 시작하기 전에 `docs/failure-memory.md`를 읽는다. 같은 실수를 반복하면 기록을 제안하고, 사람이 승인한 뒤에만 추가한다
- 문서를 근거로 답할 때는 근거 위치(`파일` + 장·표 이름)를 함께 적는다. 문서의 일부만 읽었으면 읽은 범위를 밝힌다
- 문서끼리 내용이 다르면 한쪽을 조용히 고르지 않는다. 어디가 다른지 알리고, `docs/README.md` "문서끼리 다르면"의 기준 문서를 따랐다고 적는다
- `git commit`, `git push`, 이슈·PR 생성은 사람이 확인한 뒤에만 한다. `issue`·`pr-body` 스킬은 초안을 먼저 보여 주고 승인을 받은 뒤에 올린다
- 커밋 메시지·이슈·PR 본문에 AI 도구 서명(`Co-Authored-By: Claude ...`, `Generated with ...` 등)을 넣지 않는다 (Claude·Codex 공통)
- 비밀값 파일(`.env*`, `*.pem`, `*.key`, `application-prod*`, `application-local*`)은 읽거나 고치지 않는다
- 이미 `dev`에 들어간 Flyway 마이그레이션(`V*.sql`)은 고치지 않는다. 바꿀 게 있으면 새 V 파일을 만든다
- 되돌리기 어려운 명령(`git push --force`, `git reset --hard`, `git clean -f`, `DROP`·`TRUNCATE`, `flyway clean`)은 실행하지 않는다
- 스타일(들여쓰기·줄바꿈)은 포매터 몫이다. 리뷰와 수정은 로직과 이 문서의 규칙에 집중한다
- 스킬은 `.agents/skills/`에서만 고친다. `.claude/skills/`는 복사본이라 직접 고치지 않는다
- 정한 것은 `docs/decisions.md`에 남긴다 (`decide` 스킬)

## 패키지·데이터 원칙·코드 규칙
규칙은 아래 원본에만 있다. 여기에 옮겨 적지 않는다.
- 패키지·부르는 방향·이름 규칙: `docs/rules/09-프로젝트-구조.md`
- 테이블 종류(A 행동 기록·B 사실 행·C 상태 행)와 고칠 수 있는 칸: `docs/rules/05-ERD-초안.md` 0장
- 늘 지키는 원칙: `docs/decisions.md` "늘 지키는 원칙"
