# AI 하네스 처음 설정

이 저장소에서 Claude Code나 Codex를 쓰기 전에 **한 번만** 하면 된다.

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

## 문제가 생기면

| 증상 | 해결 |
|---|---|
| `gh: command not found` | IDE를 완전히 껐다 켠다. 급하면 전체 경로로 실행 (Windows: `"/c/Program Files/GitHub CLI/gh.exe"`) |
| 훅이 안 막는다 | `node -v` 확인 → Claude는 `/hooks`, Codex는 훅 신뢰 상태 확인 → AI 세션을 새로 시작 |
| 막으면 안 되는 명령이 막힌다 | 직접 터미널에서 실행하고, 훅 오탐으로 이슈를 올린다 |
