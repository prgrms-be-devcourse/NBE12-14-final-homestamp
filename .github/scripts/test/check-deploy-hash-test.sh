#!/usr/bin/env bash
# check-deploy-hash.sh 시험: 가짜 저장소에서 정상 해시는 통과, 틀린 해시·빈 값·꺼내기 실패·빈 파일은 실패
set -uo pipefail

SCRIPT="$(cd "$(dirname "$0")/.." && pwd)/check-deploy-hash.sh"
pass=0 failn=0
ok() { echo "ok   $1"; pass=$((pass + 1)); }
bad() { echo "FAIL $1"; failn=$((failn + 1)); }

R="$(mktemp -d)"
trap 'rm -rf "$R"' EXIT
cd "$R" || exit 1
git init -q
git config user.email t@example.invalid
git config user.name t
mkdir -p infra/deploy infra/terraform
printf '#!/bin/sh\necho back\n' >infra/deploy/deploy.sh
printf '#!/bin/sh\necho front\n' >infra/deploy/deploy-front.sh
hb="$(sha256sum infra/deploy/deploy.sh | cut -d' ' -f1)"
hf="$(sha256sum infra/deploy/deploy-front.sh | cut -d' ' -f1)"

tf() { # $1 = back default, $2 = front default
  cat >infra/terraform/deploy.tf <<EOF
variable "deploy_script_sha256" {
  description = "가짜"
  type        = string
  default     = "$1"
  validation {
    condition     = can(regex("^([0-9a-f]{64})?\$", var.deploy_script_sha256))
    error_message = "가짜"
  }
}

variable "deploy_front_script_sha256" {
  description = "가짜"
  type        = string
  default     = "$2"
}
EOF
}
commit() { git add -A && git commit -qm "$1"; }
run() { bash "$SCRIPT" >"$R/out" 2>&1; echo $?; }
expect_rc() { # 이름, 기대(0 또는 1), 출력에 있어야 할 문구
  local rc
  rc="$(run)"
  if [[ $2 == 0 && $rc == 0 ]] || [[ $2 == 1 && $rc != 0 ]]; then ok "$1: 종료 $rc"; else bad "$1: 종료 $rc"; cat "$R/out"; fi
  [[ -z ${3:-} ]] || { grep -qF "$3" "$R/out" && ok "$1: 이유" || bad "$1: 이유 ($3)"; }
}

tf "$hb" "$hf"
commit 정상
expect_rc "정상 해시" 0 "ok   deploy_front_script_sha256"

tf "$hb" "$(printf 'a%.0s' $(seq 1 64))"
commit 틀린해시
expect_rc "화면 해시 틀림" 1 "deploy_front_script_sha256 가"

tf "" "$hf"
commit 빈값
expect_rc "백엔드 해시 빈 값" 1 "<빈 값>"

tf "$hb" "$hf"
printf '#!/bin/sh\necho back v2\n' >infra/deploy/deploy.sh
commit 스크립트만
expect_rc "스크립트만 바꾸고 해시는 그대로" 1 "deploy_script_sha256 가"

# 작업 폴더가 아니라 Git 원본을 본다: 커밋 안 한 수정은 결과에 영향이 없다
tf "$(sha256sum infra/deploy/deploy.sh | cut -d' ' -f1)" "$hf"
commit 해시맞춤
printf 'local edit\n' >>infra/deploy/deploy.sh
expect_rc "커밋 안 한 수정은 보지 않음" 0
git checkout -q -- infra/deploy/deploy.sh

git rm -q infra/deploy/deploy-front.sh
commit 파일없음
expect_rc "Git에 파일 없음(꺼내기 실패)" 1 "꺼내지 못했습니다"

: >infra/deploy/deploy-front.sh
tf "$(sha256sum infra/deploy/deploy.sh | cut -d' ' -f1)" e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
commit 빈파일
expect_rc "빈 파일(빈 입력 해시를 정상으로 보지 않음)" 1 "비어 있습니다"

git rm -q infra/terraform/deploy.tf
commit tf없음
expect_rc "deploy.tf 없음" 1 "deploy.tf 를 꺼내지 못했습니다"

echo "passed=$pass failed=$failn"
((failn == 0))
