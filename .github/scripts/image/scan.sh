#!/usr/bin/env bash
# 로컬 Docker 이미지를 Trivy로 검사하고 evaluate.sh로 판정한다.
#   scan.sh <이미지> <결과 폴더>
# Trivy는 digest로 고정한 공식 이미지(0.76.2, 2026-03 공급망 사고 판 0.69.4~0.69.6이 아님)를 쓴다.
# Trivy가 실패하면(취약점 DB를 못 받는 등) 통과로 보지 않는다: 종료 3
set -euo pipefail
image="$1"
out="$(mkdir -p "$2" && cd "$2" && pwd)"
trivy="${TRIVY_IMAGE:-aquasec/trivy@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb}"
here="$(cd "$(dirname "$0")" && pwd)"

args=(image --quiet --scanners "vuln,secret" --format json --output /out/trivy.json --timeout 10m)
[[ -z ${TRIVY_DB_REPOSITORY:-} ]] || args+=(--db-repository "$TRIVY_DB_REPOSITORY")
if ! docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$out:/out" \
  -v "${TRIVY_CACHE_VOLUME:-homestamp-trivy-cache}:/root/.cache" "$trivy" "${args[@]}" "$image"; then
  echo "scan: Trivy를 실행하지 못했습니다(취약점 DB 받기 실패 등). 통과로 보지 않습니다"
  exit 3
fi
[[ -s $out/trivy.json ]] || { echo "scan: Trivy 결과 파일이 없습니다"; exit 3; }
"$here/evaluate.sh" "$out/trivy.json" "$here/scan-exceptions.txt"
