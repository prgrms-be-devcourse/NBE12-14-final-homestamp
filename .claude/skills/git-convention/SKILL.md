---
name: git-convention
description: 브랜치 이름과 커밋 메시지를 팀 규칙대로 만든다. 사람이 /git-convention 또는 $git-convention으로 부를 때만 쓴다
disable-model-invocation: true
---

# git-convention

브랜치 이름이나 커밋 메시지 **초안만** 보여 준다. `git switch -c`, `git commit`은 실행하지 않는다. 사람이 확인하고 직접 한다.

## 브랜치 이름

`{타입}/{back|front|common}/{짧은-설명}` — 소문자와 `-`만 쓴다.

- 가운데: `back` 백엔드, `front` 프론트, `common` 어느 쪽에도 속하지 않는 저장소 전체 작업 (하네스, CI, 루트 설정, 문서)
- back·front가 함께 바뀌는 작은 작업은 나누지 않고 한 브랜치로 한다. 가운데는 더 많이 바뀐 쪽
- 설명은 영어 2~4단어. 예) `feat/back/admin-login`, `fix/front/login-redirect`, `chore/common/ci-setup`, `docs/common/planning-update`

## 커밋 타입

| 타입 | 쓰는 때 |
|---|---|
| `feat` | 새 기능 |
| `fix` | 버그 수정 |
| `test` | 테스트만 추가·수정 |
| `docs` | 문서만 |
| `refactor` | 동작 변화 없는 구조 개선 |
| `chore` | 설정·의존성·빌드·하네스 |

## 커밋 메시지 만드는 순서

1. `git diff --staged`만 본다. 스테이징된 게 없으면 "스테이징된 변경이 없습니다"라고 알리고 `git status --short` 결과를 보여 준 뒤 멈춘다
2. 바뀐 내용에 맞는 타입 하나를 고른다
3. `타입: 내용` 한 줄로 쓴다
   - 한국어, 50자 이내, 범위 표시 없음 (`feat(back):` ✗)
   - 무엇을 했는지 쓴다. 끝은 명사형이나 "~추가/수정/삭제"로 맺는다
   - 메시지 끝에 `Co-Authored-By` 같은 AI 도구 서명을 붙이지 않는다
4. 한 커밋에 두 가지 일이 섞였으면 (예: 기능 추가 + 상관없는 오타 수정) 어떤 파일끼리 나눌지 제안한다

## 결과 모양

```
브랜치: feat/back/admin-login        (브랜치를 물었을 때)
커밋:   feat: 관리자 로그인 API 추가
나누기: (필요할 때만) 1) A, B 파일 → feat: ...  2) C 파일 → docs: ...
```
