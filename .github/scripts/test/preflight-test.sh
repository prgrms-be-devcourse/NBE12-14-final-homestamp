#!/usr/bin/env bash
# preflight.sh 시험: 가짜 gh로 Environment 응답을 흉내 낸다(GitHub 호출 없음). macOS bash 3.2·Linux bash 5 모두.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
target="$here/../preflight.sh"
W="$(mktemp -d)"
trap 'rm -r "$W"' EXIT
mkdir -p "$W/bin"
cat >"$W/bin/gh" <<'GH'
#!/usr/bin/env bash
# gh api <경로> [--jq 식]: 경로에 맞는 시험 응답을 낸다
[[ $1 == api ]] || exit 99
case "$2" in
  */environments/production) [[ -f $MOCK_DIR/env.json ]] || { echo "HTTP 404" >&2; exit 1; }; json="$(cat "$MOCK_DIR/env.json")" ;;
  */environments/production/deployment-branch-policies) json="$(cat "$MOCK_DIR/branches.json")" ;;
  *) exit 99 ;;
esac
if [[ ${3:-} == --jq ]]; then jq -r "$4" <<<"$json"; else printf '%s\n' "$json"; fi
GH
chmod +x "$W/bin/gh"

D=sha256:$(printf 'a%.0s' $(seq 1 64))
passed=0; failed=0
ok() { passed=$((passed + 1)); echo "ok   $1"; }
bad() { failed=$((failed + 1)); echo "FAIL $1"; }
env_json() { # 승인자수 자기승인금지 admin우회 선택브랜치
  local rev='[]'
  [[ $1 == 0 ]] || rev='[{"type":"User","reviewer":{"login":"a"}}]'
  printf '{"name":"production","can_admins_bypass":%s,"protection_rules":[{"type":"wait_timer","wait_timer":0},{"type":"required_reviewers","prevent_self_review":%s,"reviewers":%s}],"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":%s}}\n' \
    "$3" "$2" "$rev" "$4" >"$W/env.json"
}
branches() { printf '{"branch_policies":[%s]}\n' "$1" >"$W/branches.json"; }
run() { # 이름 기대코드 [기대 출력 조각]
  PATH="$W/bin:$PATH" MOCK_DIR="$W" DIGEST="${T_DIGEST-$D}" REPO="${T_REPO-o/r}" bash "$target" >"$W/out" 2>&1
  local rc=$?
  if [[ $rc == "$2" ]] && { [[ -z ${3:-} ]] || grep -qF "$3" "$W/out"; }; then ok "$1 ($rc)"; else bad "$1 (기대 $2, 실제 $rc)"; cat "$W/out"; fi
}
main='{"name":"main","type":"branch"}'

env_json 1 true false true; branches "$main";  run "규칙이 모두 맞음" 0 "통과"
env_json 1 false false true;                   run "자기 승인 금지 꺼짐 → 멈춤" 1 "자기 승인 금지"
env_json 1 true true true;                     run "Admin 우회 허용(기본값) → 멈춤" 1 "Admin"
printf '{"name":"production","protection_rules":[{"type":"required_reviewers","prevent_self_review":true,"reviewers":[{"type":"User"}]}],"deployment_branch_policy":{"custom_branch_policies":true}}\n' >"$W/env.json"
run "can_admins_bypass가 응답에 없음 → 멈춤(꺼졌다고 보지 않음)" 1 "Admin"
env_json 0 true false true;                    run "승인자가 없음 → 멈춤" 1 "승인자"
printf '{"name":"production","can_admins_bypass":false,"protection_rules":[],"deployment_branch_policy":{"custom_branch_policies":true}}\n' >"$W/env.json"
run "승인자 규칙이 없음 → 멈춤" 1 "승인자"
env_json 1 true false false;                   run "배포 브랜치 규칙 없음 → 멈춤" 1 "배포 브랜치 규칙"
env_json 1 true false true; branches "$main,{\"name\":\"dev\",\"type\":\"branch\"}"; run "main 말고 dev도 → 멈춤" 1 "main 하나"
branches '{"name":"main","type":"tag"}';       run "main 태그(브랜치 아님) → 멈춤" 1 "main 하나"
branches "$main"; rm -f "$W/env.json";         run "Environment 없음 → 멈춤" 1 "Environment production이 없습니다"
env_json 1 true false true
T_DIGEST="sha256:abc" run "digest 형식 아님 → 멈춤" 1 "digest"
T_DIGEST='sha256:'"$(printf 'a%.0s' $(seq 1 64))"'; rm -rf /' run "digest에 명령 → 멈춤" 1 "digest"
T_REPO='o/r;id' run "저장소 이름에 명령 → 멈춤" 1 "저장소"

echo "passed=$passed failed=$failed"
[[ $failed == 0 ]]
