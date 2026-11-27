#!/usr/bin/env bash
# 화면 이미지의 보안 검사. 둘 다 통과해야 발행한다(판정은 evaluate.sh, 정책은 백엔드와 같음).
#   scan-front.sh <front 폴더> <로컬 이미지> <결과 폴더>
# 1. npm 의존성: package-lock.json을 Trivy fs로(개발 의존성 포함, 빌드 도구도 공급망이다). 마지막 이미지에는 lockfile이 없어 이미지 검사로는 못 본다
# 2. 이미지: 실제로 발행할 scratch 이미지의 /site를 비밀값 검사(검사한 이미지 ID가 그 이미지인지 확인)
# Trivy가 실패하면(취약점 DB를 못 받는 등) 통과로 보지 않는다: 종료 3. 종료는 evaluate.sh와 같음(10 취약점·11 비밀값)
set -euo pipefail
front="$(cd "${1:?front 폴더}" && pwd)"
image="${2:?이미지}"
out="$(mkdir -p "${3:?결과 폴더}" && cd "$3" && pwd)"
trivy="${TRIVY_IMAGE:-aquasec/trivy@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb}"
here="$(cd "$(dirname "$0")" && pwd)"
db=()
[[ -z ${TRIVY_DB_REPOSITORY:-} ]] || db=(--db-repository "$TRIVY_DB_REPOSITORY")
run() { docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$out:/out" \
  -v "${TRIVY_CACHE_VOLUME:-homestamp-trivy-cache}:/root/.cache" "$trivy" "$@"; }

# lockfile만 따로 둔다(로컬 node_modules나 다른 파일을 읽지 않게)
mkdir -p "$out/deps-src"
cp "$front/package.json" "$front/package-lock.json" "$out/deps-src/"
id="$(docker image inspect -f '{{.Id}}' "$image")" || { echo "scan-front: 이미지가 없습니다"; exit 3; }

echo "== npm 의존성(package-lock.json, 개발 의존성 포함)"
if run fs --quiet --scanners vuln --include-dev-deps --list-all-pkgs ${db[@]+"${db[@]}"} \
  --format json --output /out/trivy-deps.json --timeout 10m /out/deps-src && [[ -s $out/trivy-deps.json ]]; then
  set +e
  REQUIRE_OS=0 REQUIRE_JAVA=0 REQUIRE_NPM=1 "$here/evaluate.sh" "$out/trivy-deps.json" "$here/scan-exceptions.txt"
  deps=$?
  set -e
else
  echo "scan-front: Trivy를 실행하지 못했습니다(의존성, 취약점 DB 받기 실패 등). 통과로 보지 않습니다"
  deps=3
fi

echo "== 이미지(/site 비밀값)"
if run image --quiet --scanners secret ${db[@]+"${db[@]}"} --format json --output /out/trivy-image.json --timeout 10m "$image" &&
  [[ -s $out/trivy-image.json ]]; then
  set +e
  REQUIRE_OS=0 REQUIRE_JAVA=0 EXPECT_IMAGE_ID="$id" "$here/evaluate.sh" "$out/trivy-image.json" "$here/scan-exceptions.txt"
  img=$?
  set -e
else
  echo "scan-front: Trivy를 실행하지 못했습니다(이미지). 통과로 보지 않습니다"
  img=3
fi

# 둘 다 0이어야 통과. 비밀값(11)을 가장 먼저 알린다
rc=$deps
((img == 0 || (rc != 0 && img != 11))) || rc=$img
echo "scan-front: 의존성=$deps 이미지=$img → $rc"
exit "$rc"
