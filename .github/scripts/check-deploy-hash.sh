#!/usr/bin/env bash
# 승인 해시 확인: Terraform에 적은 승인 해시가 Git에 커밋된 배포 스크립트 바이트와 같은지 본다.
# - 서버에는 이 커밋의 파일을 그대로 설치하고, SSM 문서는 이 해시로 실행을 고정한다(배포-자동화 "코드 배포")
# - 작업 폴더가 아니라 Git 원본(git show <rev>:<path>)을 본다. 꺼내기에 실패하거나 빈 파일이면 해시를 내지 않고 실패한다
# 사용: check-deploy-hash.sh [rev]   (기본 HEAD. 저장소 루트에서 실행)
set -euo pipefail

REV="${1:-HEAD}"
TF=infra/terraform/deploy.tf
EMPTY_SHA=e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
fail=0

# variable "<이름>" 블록 안의 default = "<값>"
tf_default() {
  awk -v name="$1" '
    $0 ~ "^variable \"" name "\" \\{" { inside = 1; next }
    inside && /^}/ { exit }
    inside && match($0, /^  default += "[^"]*"/) {
      v = substr($0, RSTART, RLENGTH); sub(/^[^"]*"/, "", v); sub(/"$/, "", v); print v; found = 1; exit
    }
    END { if (!found) exit 1 }
  ' "$2"
}

check() {
  local path=$1 var=$2 tmp want got
  tmp="$(mktemp)"
  if ! git show "$REV:$path" >"$tmp" 2>/dev/null; then
    echo "실패: Git에서 $REV:$path 를 꺼내지 못했습니다"
    rm -f "$tmp"
    fail=1
    return
  fi
  if [[ ! -s $tmp ]]; then
    echo "실패: $REV:$path 가 비어 있습니다"
    rm -f "$tmp"
    fail=1
    return
  fi
  got="$(sha256sum "$tmp" | cut -d' ' -f1)"
  rm -f "$tmp"
  if [[ ! $got =~ ^[0-9a-f]{64}$ || $got == "$EMPTY_SHA" ]]; then
    echo "실패: $path 의 해시를 계산하지 못했습니다"
    fail=1
    return
  fi
  if ! want="$(tf_default "$var" "$tfsrc")"; then
    echo "실패: $TF 에 variable \"$var\" 의 default가 없습니다"
    fail=1
    return
  fi
  if [[ $want != "$got" ]]; then
    echo "실패: $var 가 $path 와 다릅니다 (Terraform ${want:-<빈 값>}, Git $got)"
    echo "      스크립트를 바꿨다면 같은 PR에서 $TF 의 default를 Git 해시로 고치고, 배포-자동화 '코드 배포' 순서대로 설치·apply 합니다"
    fail=1
    return
  fi
  echo "ok   $var = $path ($got)"
}

tfsrc="$(mktemp)"
trap 'rm -f "$tfsrc"' EXIT
if ! git show "$REV:$TF" >"$tfsrc" 2>/dev/null || [[ ! -s $tfsrc ]]; then
  echo "실패: Git에서 $REV:$TF 를 꺼내지 못했습니다"
  exit 1
fi

check infra/deploy/deploy.sh deploy_script_sha256
check infra/deploy/deploy-front.sh deploy_front_script_sha256
exit "$fail"
