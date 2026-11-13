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

## 6. 코드 서식 (IntelliJ, 모두 필수)

서식의 기준은 CI다(자바 `./gradlew spotlessCheck`, 프론트 `npm run format:check`). IDE는 저장할 때 같은 결과를 내도록 맞춘다. IntelliJ 기본 서식(4칸 들여쓰기)은 구글 자바 스타일(2칸)과 달라서, 그대로 두면 CI에서 떨어진다.

| 할 일 | 방법 |
|---|---|
| 자바 플러그인 | Settings → Plugins → Marketplace에서 **google-java-format** 설치. 버전 **2.0.0.1**(CI의 google-java-format 2.0.0과 같은 판), IntelliJ 2025.1 이상 |
| 켜기 | Settings → Other Settings → **google-java-format Settings** → Enable, 스타일은 Default Google Java style |
| 저장할 때 맞추기 (선택) | Settings → Tools → Actions on Save → Reformat code 켜기 |
| 프론트 | Settings → Languages & Frameworks → JavaScript → **Prettier** → Automatic Prettier configuration, Run on save 켜기(`front/node_modules`의 Prettier를 씀) |

확인: 커밋 전에 `cd back && ./gradlew spotlessApply`, `cd front && npm run format`을 돌려도 바뀌는 파일이 없으면 IDE 설정이 맞다.

## 문제가 생기면

| 증상 | 해결 |
|---|---|
| `gh: command not found` | IDE를 완전히 껐다 켠다. 급하면 전체 경로로 실행 (Windows: `"/c/Program Files/GitHub CLI/gh.exe"`) |
| 훅이 안 막는다 | `node -v` 확인 → Claude는 `/hooks`, Codex는 훅 신뢰 상태 확인 → AI 세션을 새로 시작 |
| 막으면 안 되는 명령이 막힌다 | 직접 터미널에서 실행하고, 훅 오탐으로 이슈를 올린다 |
| IDE에서 맞췄는데 `spotlessCheck`가 떨어진다 | google-java-format 플러그인 버전이 2.0.0.x인지 확인. 그래도 다르면 `./gradlew spotlessApply`로 맞추고, `docs/rules/04` 17장 "서식 기준"의 cleanup 시험 결과를 확인 |
| google-java-format 플러그인이 시작할 때 오류를 낸다 | 플러그인 안내에 있는 `--add-exports` 줄을 Help → Edit Custom VM Options에 넣고 IntelliJ를 다시 켠다 |
