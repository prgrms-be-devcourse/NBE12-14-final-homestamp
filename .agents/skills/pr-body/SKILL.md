---
name: pr-body
description: PR 제목과 본문을 템플릿대로 만들고, 사람이 승인하면 push와 PR 생성(이미 열린 PR이면 본문 갱신)까지 한다. 사람이 /pr-body 또는 $pr-body로 부를 때만 쓴다
disable-model-invocation: true
---

# pr-body

PR 제목과 본문 초안을 만든다. **사람이 승인해야만** push하고 PR을 올린다.

## 입력

연결할 이슈 번호 (선택). 예) `/pr-body #12`, `/pr-body #12 드래프트로`

## 보는 것

- `git log origin/dev..HEAD` — 이 브랜치의 커밋
- `git diff --stat origin/dev...HEAD` — 바뀐 파일
- 현재 브랜치 이름
- `.github/pull_request_template.md` — 본문 칸

커밋하지 않은 변경이 있으면(`git status --short`) 초안 위에 "커밋하지 않은 변경이 있습니다" 한 줄을 붙인다.

## 이슈 번호 찾기

입력 → 커밋 메시지·브랜치 이름의 `#번호` → 이 대화에서 `issue` 스킬로 올린 이슈 번호 순으로 찾는다.
없으면 **한 번만** 묻는다. 이슈가 없다고 하면 `- 관련 이슈: 없음`으로 쓴다.

## 제목

`타입: 내용` — 한국어 한 줄, 50자 이내, 범위 표시 없음.
타입은 브랜치 이름의 타입을 따른다. 예) `feat/back/admin-login` → `feat: 관리자 로그인 추가`

## 본문

템플릿의 칸과 순서를 그대로 쓴다. 칸마다 1~3줄. 커밋 목록을 그대로 붙이지 않고 묶어서 요약한다.

| 칸 | 채우는 법 |
|---|---|
| 작업 설명 | `- 관련 이슈: closes #12` (여러 개면 `closes #12, closes #13`) + 한두 줄 설명 |
| 요구 사항과 구현 내용 | 커밋을 기능 단위로 묶어 목록으로 |
| 피드백 반영사항 | 이전 리뷰를 반영한 커밋이 있으면 적고, 없으면 `없음` |
| PR 포인트 & 궁금한 점 | 리뷰어가 꼭 볼 곳을 `파일 — 이유`로 1~3개. 궁금한 점은 비워 둔다 (사람이 채움) |
| 확인 | 체크하지 않고 그대로 둔다 (사람이 직접 체크) |

- `closes #번호`가 있으면 PR이 `dev`(기본 브랜치)에 머지될 때 이슈가 자동으로 닫히고, 이슈 화면에도 PR이 연결돼 보인다
- 훅 등록 파일(`.claude/settings.json`, `.codex/hooks.json`)이 바뀌었으면 PR 포인트에 `Codex 사용자: /hooks에서 다시 신뢰 필요` 한 줄을 넣는다

## 라벨

PR 제목의 타입과 **같은 이름의 라벨 하나**를 붙인다. 라벨은 저장소에 미리 만들어 둔 것만 쓴다(`gh label list`로 확인, 없으면 만들지 말고 알린다).

| 제목 타입 | 라벨 |
|---|---|
| `feat` | `feat` |
| `fix` | `fix` |
| `test` | `test` |
| `docs` | `docs` |
| `refactor` | `refactor` |
| `chore` | `chore` |

- **`style`**: UI 스타일·CSS만 바꾸는 일이면 타입 라벨에 **더해서** 붙인다(제목 타입은 위 여섯 개 중 하나, `style`은 커밋 타입이 아님)
- 라벨은 초안에 `라벨: feat`처럼 보여 주고 승인받는다
- 규칙 문서(`docs/rules/`)만 문장을 다듬었고 뜻이 바뀌지 않아 `docs/decisions.md`에 줄이 없으면 `no-decision`도 제안한다(CI 결정 기록 검사가 건너뜀)

## 리뷰어 정하기

바뀐 파일에서 **누구의 것을 건드렸는지** 찾아 리뷰어를 제안한다. **주인은 `docs/rules/07-WBS.md` 0장 역할 표 하나만** 기준이다(주인 표를 두 곳에 두지 않는다). 아래는 07의 패키지·폴더를 파일·테이블·기능 ID에 대응시키는 법이다.

### 1. 필요한 주인 모으기

| 바뀐 것 | 주인 찾는 법 |
|---|---|
| 백엔드 도메인 패키지, `global/`, 프론트 `front/src/features/<도메인>/`, CI, 하네스 | `.github/CODEOWNERS`. PR을 열면 GitHub가 **자동으로** 요청한다 |
| `docs/rules/05-ERD-초안.md`, `back/src/main/resources/db/migration/V*.sql` | 바뀐 **테이블 이름**으로 아래 "테이블 → 주인" 표를 본다(절 이름으로 정하지 않는다. 예: `link`는 05 2-2에 있지만 D) |
| `docs/rules/06-요구사항-명세.md` | 바뀐 기능 ID·수용 기준·API의 **앞글자**로 아래 "기능 ID → 주인" 표를 본다 |
| 여러 도메인이 쓰는 것: 공개 인터페이스(`ActionWriter`, `Correctable`, 응답 공통 처리), 공통 DTO, 프론트 `components/common` | **만든 주인 + 쓰는 쪽 주인 모두** |

**테이블 → 주인** (07 0장 패키지 기준)

| 주인 | 테이블 |
|---|---|
| A (`member` `auth` `room` `inspection`) | `member`, `refresh_token`, `phone_verification`, `member_acknowledgement`, `region`, `part_template`, `room_record`, `party`, `room_part`, `inspection`, `inspection_item`, `inspection_item_version` |
| B (`action` `repair` `correction`) | `action`, `repair_case`, `repair_content`, `cause_note`, `progress_note`, `progress_note_response`, `correction_request` |
| C (`proposal` `payment` `settlement` `photo`) | `proposal`, `proposal_line`, `payment`, `payment_version`, `settlement`, `settlement_item`, `settlement_transfer`, `settlement_transfer_version`, `photo`, `photo_visibility` |
| D (`notification` `link` `respond`) | `response_request`, `notice`, `outbox`, `in_app_notification`, `link`, `sms_opt_out`(수신 거부는 통지 규칙, 03 K) |
| **여러 주인** | `rate_counter`: 바뀐 `counter_key`를 쓰는 쪽(인증·로그인 = A, 업로드 = C, 문자 한도·다시 요청 = D) |
| **리뷰어 확인 필요** | `deletion_log`(보관 삭제·탈퇴, 07 0장에 주인 없음), 위 표에 없는 새 테이블 |

**기능 ID → 주인** (07 0장 도메인 기준)

| 앞글자 | 주인 |
|---|---|
| `ACC`·`ROOM`·`INSP` | A |
| `REP` | B |
| `MONEY`·`SET`·`PHO` | C |
| `NOTI`·`LINK` | D |
| `CONS`·`KEEP`·`DL`·`OPS`·`SLO`, 화면 ID `S-`, 07 0장에 없는 앞글자 | **리뷰어 확인 필요** (07 0장에 주인이 생기면 이 표에 옮긴다) |

### 2. 빼고 비교하기

1. 위에서 모은 주인 목록에서 **PR을 올리는 사람 자신만** 뺀다. 자기가 만든 공개 인터페이스를 바꿨어도 **쓰는 쪽 주인은 남긴다**
2. CODEOWNERS로 자동 요청될 사람은 `--reviewer`에 다시 넣지 않는다
3. "리뷰어 확인 필요"가 하나라도 있으면 리뷰어를 지어내지 않고 사람에게 묻는다

- 주인의 GitHub 아이디는 `.github/CODEOWNERS` 머리 주석에서 읽는다
- 결과 모양에 `리뷰어: @아이디 (이유)`로 보여 주고, PR 포인트에도 "○○ 테이블이 바뀌어 @아이디 확인 부탁드려요" 한 줄을 넣는다

## 결과 모양

```
제목: feat: 관리자 로그인 추가
base: dev ← feat/back/admin-login   (드래프트면 "Draft PR")
라벨: feat
리뷰어: @jinni1 (05 2-5 payment 칸 변경)   (없으면 줄 생략)

(본문 마크다운)
```

## 올리기 (사람이 "올려줘"라고 승인한 뒤에만)

1. 커밋하지 않은 변경이 있으면 올리지 않고 먼저 알린다
2. `git push -u origin <현재 브랜치>`
3. PR 생성 (드래프트로 해 달라고 했으면 `--draft`를 붙인다)
   ```
   gh pr create --base dev --head <현재 브랜치> --title "<제목>" --label <라벨>[,style] [--reviewer <아이디>,<아이디>] --body-file - <<'BODY'
   <본문>
   BODY
   ```
4. PR URL을 보여 준다

## 이미 열린 PR이 있을 때 (본문 갱신)

같은 브랜치에 열린 PR이 있으면(`gh pr view --json number,body,commits`) 새로 만들지 않고 본문을 갱신한다.

1. 지금 본문과 PR의 커밋 목록을 비교해, 본문에 아직 없는 변경을 찾는다
2. **사람이 쓴 내용은 그대로 둔다** — 궁금한 점, 체크한 체크박스, 사람이 고친 문장. 빠진 변경만 해당 칸에 줄을 더한다
3. 바뀌는 줄만 보여 준다 (`+ 추가할 줄`, `- 지울 줄`). 지울 줄은 이유도 적는다
4. 사람이 "갱신해줘"라고 승인하면: 아직 push하지 않은 커밋이 있으면 `git push` → `gh pr edit <번호> --body-file -` (heredoc으로 본문 전달). 라벨이 없거나 다르면 `--add-label <라벨>`도 함께
5. PR URL을 보여 준다

제목은 사람이 바꿔 달라고 할 때만 `gh pr edit <번호> --title`로 바꾼다.

## 지킬 것

- 승인 없이 push·PR 생성·본문 갱신을 하지 않는다. 초안을 고쳐 달라고 하면 다시 보여 주고 다시 승인을 받는다
- `main`·`dev` 브랜치에서는 올리지 않는다 (작업 브랜치에서만). 릴리스 PR(`dev → main`)은 사람이 직접 만든다
- `gh`가 없거나 로그인이 안 돼 있으면 `gh auth login`이 필요하다고 알리고, 초안만 준다
- `--force` push는 하지 않는다
- 본문 끝에 `Generated with ...`, `Co-Authored-By` 같은 AI 도구 서명을 넣지 않는다 (Claude·Codex 공통, `docs/decisions.md`)
