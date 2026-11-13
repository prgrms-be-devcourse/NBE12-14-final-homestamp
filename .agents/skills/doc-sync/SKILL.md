---
name: doc-sync
description: PR 전에 바뀐 코드에 맞춰 규칙 문서에서 고칠 곳을 제안한다. 사람이 부를 때만 쓴다
disable-model-invocation: true
---

# doc-sync

코드는 바뀌었는데 문서는 그대로인 곳을 찾는다. **문서를 직접 고치지 않는다.** 고칠 곳만 제안한다.

## 보는 것

- `git diff origin/dev...HEAD`와 `git diff HEAD` — 바뀐 코드
- 바뀐 코드와 관련된 문서
  - 루트 `AGENTS.md`, 그 도메인 폴더에 `AGENTS.md`가 있으면 그것도
  - `docs/rules/03-상태와-규칙.md` — 상태 전이 표, 역할별 권한 표
  - `docs/rules/05-ERD-초안.md` — 테이블·칸 (엔티티·마이그레이션이 바뀌었을 때)
  - `docs/rules/06-요구사항-명세.md` — API 목록, 오류 코드, 설정값 (컨트롤러·예외·설정이 바뀌었을 때)
  - `docs/rules/09-프로젝트-구조.md` — 패키지, 부르는 방향, 이름 규칙 (패키지가 바뀌었을 때)
  - 하네스가 바뀌었으면 `.agents/skills/`, `scripts/hooks/`, `docs/setup.md`

## 찾는 것

- 새 상태 값이나 전이가 생겼는데 상태 전이표에 없음
- 공개 인터페이스(다른 도메인이 쓰는 클래스·메서드, API 경로)가 바뀌었는데 문서는 옛날 것
- 명령, 경로, 브랜치 이름이 바뀌었는데 `AGENTS.md`·스킬에 옛날 값이 남음 (예: `origin/main` → `origin/dev`)
- 문서에 적힌 규칙과 코드가 반대로 동작함

## 하지 않는 것

- 문서 고치기 (사람이 고친다)
- 코드 리뷰 (그건 `code-review`)

## 결과 모양

```
AGENTS.md "AI가 지킬 것" 3번째 줄 — "main" → "dev" — guard-migration이 origin/dev 기준으로 바뀜 (scripts/hooks/guard-migration.mjs:11)
```

- 한 줄에 하나, `문서 위치 — 이렇게 바꾸기 — 이유(코드 파일:줄)`
- 없으면 `고칠 곳 없음` 한 줄
