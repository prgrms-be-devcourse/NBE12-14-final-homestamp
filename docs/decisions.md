| 날짜 | 결정 | 이유 |
|---|---|---|
| 2026-11-12 | 커밋에 Claude `Co-Authored-By` 줄을 붙이지 않는다 (`.claude/settings.json`의 `attribution.commit`을 빈 값으로) | 커밋 기록을 깔끔하게 유지하려고 |
| 2026-11-12 | (2026-11-12 결정을 바꿈) 커밋뿐 아니라 이슈·PR 본문에도 AI 도구 서명(`Co-Authored-By`, `Generated with ...`)을 넣지 않는다. Claude는 `attribution` 설정, Codex는 `AGENTS.md`·스킬 규칙으로 지킨다 | 커밋 기록을 깔끔하게 유지하고, Codex 사용자도 같은 규칙으로 이슈·PR을 올리게 하려고 |
