#!/usr/bin/env bash
# deploy.yml preflight: AWS에 닿기 전에 입력과 Environment production의 보호 규칙을 확인한다(읽기만, gh api).
# 환경: DIGEST(sha256:<64자>), REPO(owner/name), GH_TOKEN(actions: read)
# 막는 조건(하나라도 없으면 1):
#   - 승인자 규칙(required_reviewers)에 승인자가 있고 자기 승인 금지(prevent_self_review)가 켜져 있음
#   - Admin이 보호 규칙을 건너뛰지 못함(can_admins_bypass == false. 기본값은 true라 꼭 꺼야 한다)
#   - 배포 브랜치 규칙이 "선택한 브랜치"이고 main 하나뿐
set -euo pipefail
err() {
  echo "::error::$1"
  exit 1
}
[[ "${DIGEST:-}" =~ ^sha256:[0-9a-f]{64}$ ]] || err "digest 형식이 아닙니다"
[[ "${REPO:-}" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || err "저장소 이름 형식이 아닙니다"
env_json="$(gh api "repos/$REPO/environments/production")" || err "Environment production이 없습니다(런북 '처음 설정')"
jq -e '[.protection_rules[]? | select(.type == "required_reviewers" and ((.reviewers // []) | length) > 0)] | length > 0' <<<"$env_json" >/dev/null ||
  err "production에 승인자(required reviewers)가 없습니다"
jq -e '[.protection_rules[]? | select(.type == "required_reviewers" and .prevent_self_review == true)] | length > 0' <<<"$env_json" >/dev/null ||
  err "production 승인자 규칙에 자기 승인 금지(Prevent self-review)가 꺼져 있습니다"
jq -e '.can_admins_bypass == false' <<<"$env_json" >/dev/null ||
  err "production에서 Admin이 보호 규칙을 건너뛸 수 있습니다(Allow administrators to bypass 끄기)"
jq -e '.deployment_branch_policy.custom_branch_policies == true' <<<"$env_json" >/dev/null ||
  err "production에 배포 브랜치 규칙(선택한 브랜치: main)이 없습니다"
branches="$(gh api "repos/$REPO/environments/production/deployment-branch-policies" --jq '[.branch_policies[] | "\(.type // "branch"):\(.name)"] | sort | join(",")')" ||
  err "배포 브랜치 규칙을 읽지 못했습니다"
[[ "$branches" == "branch:main" ]] || err "production 배포 브랜치는 main 하나여야 합니다($branches)"
echo "preflight: 통과(승인자·자기 승인 금지·Admin 우회 금지·배포 브랜치 main)"
