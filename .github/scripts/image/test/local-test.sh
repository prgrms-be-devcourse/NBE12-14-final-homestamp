#!/usr/bin/env bash
# image-publish 스크립트 시험. GHCR·AWS 없음. 빈 작업 폴더를 인자로 준다.
#   mkdir -p /tmp/hs-img-work && .github/scripts/image/test/local-test.sh /tmp/hs-img-work
# - 판단(gate)·판정(evaluate)은 호스트에서, 발행(publish)·검사(scan)는 Linux 컨테이너에서 호스트 Docker 소켓으로
# - 인증이 걸린 시험용 레지스트리(127.0.0.1:15100)를 GHCR 대신 쓴다
# - 이름이 hs-img-ci-test 로 시작하는 컨테이너·이미지·볼륨만 만들고 끝나면 지운다
# macOS 기본 bash 3.2에서도 돈다. SKIP_BACK=1이면 백엔드 컴파일 실패 시험(1분 남짓)을 건너뛴다
set -uo pipefail
W="${1:?빈 작업 폴더를 주세요}"
[ -d "$W" ] && [ -z "$(ls -A "$W")" ] || { echo "빈 폴더가 아닙니다: $W" >&2; exit 2; }
W="$(cd "$W" && pwd -P)"
HERE="$(cd "$(dirname "$0")/.." && pwd -P)"
ROOT="$(cd "$HERE/../../.." && pwd -P)"
T=hs-img-ci-test
REGISTRY_IMAGE=registry:3@sha256:82f6ff4a7f982960c8aa51617fea3aabc485df6e55cce80d22deb1de36f1bc3c
HTTPD_IMAGE=httpd:2.4-alpine@sha256:32bd0639bcc4917ef1fd9363a1d9dd2dd9c498565118b062a10d51e43a5c3a24
ALPINE=alpine:3.22@sha256:49340b346798875b5592e5d420241f2f9cfba5f47e62ebb1446a1b34734e898f
REG=127.0.0.1:15100
REPO=$REG/$T/back
R1=1111111111111111111111111111111111111111
R2=2222222222222222222222222222222222222222
passed=0
failed=0
ok() { passed=$((passed + 1)); echo "ok   $1"; }
bad() { failed=$((failed + 1)); echo "FAIL $1"; }
expect() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (기대 $3, 실제 $2)"; fi; }

cleanup() {
  ids="$(docker ps -aq --filter "name=^$T-")"
  [ -z "$ids" ] || docker rm -f $ids >/dev/null 2>&1
  imgs="$( (docker image ls -q --filter "reference=$T-*"; docker image ls -q --filter "reference=$REPO") | sort -u)"
  [ -z "$imgs" ] || docker image rm -f $imgs >/dev/null 2>&1
  docker volume rm $T-trivy-cache >/dev/null 2>&1
  true
}
trap cleanup EXIT
cleanup

echo "== 발행 허용 판단(gate)"
g() { EVENT_NAME=$1 REF=$2 PUBLISH_ENABLED=$3 IMAGE_REPOSITORY=$4 "$HERE/gate.sh" | head -n 1; }
expect "PR에서는 발행 안 함(변수가 켜져 있어도)" "$(g pull_request refs/heads/main true ghcr.io/o/homestamp-back)" publish=false
expect "PR 이벤트 + main ref 흉내도 안 함" "$(g pull_request_target refs/heads/main true ghcr.io/o/homestamp-back)" publish=false
expect "main 아닌 브랜치 push" "$(g push refs/heads/dev true ghcr.io/o/homestamp-back)" publish=false
expect "태그 push" "$(g push refs/tags/v0.1 true ghcr.io/o/homestamp-back)" publish=false
expect "main인데 허용 변수 없음(지금 상태)" "$(g push refs/heads/main '' '')" publish=false
expect "허용 변수가 TRUE(대문자)" "$(g push refs/heads/main TRUE ghcr.io/o/homestamp-back)" publish=false
expect "저장소 이름 없음" "$(g push refs/heads/main true '')" publish=false
expect "저장소 이름 대문자" "$(g push refs/heads/main true ghcr.io/O/Homestamp)" publish=false
expect "ghcr 아님" "$(g push refs/heads/main true docker.io/o/homestamp-back)" publish=false
expect "main + push + 허용 + 이름" "$(g push refs/heads/main true ghcr.io/prgrms-be-devcourse/homestamp-back)" publish=true
expect "main + 수동 실행 + 허용 + 이름" "$(g workflow_dispatch refs/heads/main true ghcr.io/prgrms-be-devcourse/homestamp-back)" publish=true

echo "== 검사 판정(evaluate, Trivy 결과 모양)"
fx() { # 이름 os java 취약점JSON 비밀값JSON
  printf '{"Metadata":{"OS":{"Family":"%s"}},"Results":[{"Target":"os","Class":"os-pkgs","Type":"ubuntu","Vulnerabilities":%s,"Secrets":%s}%s]}' \
    "$2" "$4" "$5" "$([ "$3" = 1 ] && echo ',{"Target":"Java","Class":"lang-pkgs","Type":"jar"}')" >"$W/$1.json"
}
v() { printf '{"VulnerabilityID":"%s","PkgName":"p","InstalledVersion":"1","FixedVersion":"%s","Severity":"%s"}' "$1" "$2" "$3"; }
ev() { "$HERE/evaluate.sh" "$W/$1.json" "${2:-}" >"$W/$1.out" 2>&1; echo $?; }
fx clean ubuntu 1 "[$(v CVE-1 2 MEDIUM)]" '[]';                     expect "MEDIUM만 → 통과" "$(ev clean)" 0
fx high-fixed ubuntu 1 "[$(v CVE-2 2 HIGH)]" '[]';                  expect "HIGH 고친 판 있음 → 막음" "$(ev high-fixed)" 10
fx high-unfixed ubuntu 1 "[$(v CVE-3 '' HIGH)]" '[]';               expect "HIGH 고친 판 없음 → 경고만" "$(ev high-unfixed)" 0
grep -q '경고 HIGH CVE-3' "$W/high-unfixed.out" && ok "경고가 출력에 남음" || bad "경고 출력"
fx crit-fixed ubuntu 1 "[$(v CVE-4 2 CRITICAL)]" '[]';              expect "CRITICAL 고친 판 있음 → 막음" "$(ev crit-fixed)" 10
fx crit-unfixed ubuntu 1 "[$(v CVE-5 '' CRITICAL)]" '[]';           expect "CRITICAL 고친 판 없음 → 막음" "$(ev crit-unfixed)" 10
fx secret ubuntu 1 '[]' '[{"RuleID":"aws-access-key-id","Category":"AWS"}]'; expect "비밀값 → 막음" "$(ev secret)" 11
fx no-os '' 1 '[]' '[]';                                            expect "OS를 못 읽음 → 믿지 않음" "$(ev no-os)" 3
fx no-java ubuntu 0 '[]' '[]';                                      expect "Java 대상 없음 → 믿지 않음" "$(ev no-java)" 3
printf 'not json' >"$W/broken.json";                                expect "JSON 아님 → 믿지 않음" "$(ev broken)" 3
printf 'CVE-4 2999-12-31 시험용 예외\n' >"$W/exc-valid.txt";        expect "만료 전 예외는 통과" "$(ev crit-fixed "$W/exc-valid.txt")" 0
printf 'CVE-4 2000-01-01 지난 예외\n' >"$W/exc-old.txt";            expect "만료된 예외는 다시 막음" "$(ev crit-fixed "$W/exc-old.txt")" 10

echo "== 준비(시험 레지스트리·runner)"
docker run --rm --entrypoint htpasswd "$HTTPD_IMAGE" -Bbn ci testpass >"$W/htpasswd" 2>/dev/null
docker run -d --name $T-registry -p "$REG:5000" -v "$W/htpasswd:/auth/htpasswd:ro" \
  -e REGISTRY_AUTH=htpasswd -e REGISTRY_AUTH_HTPASSWD_REALM=test -e REGISTRY_AUTH_HTPASSWD_PATH=/auth/htpasswd \
  "$REGISTRY_IMAGE" >/dev/null
docker build -q -t $T-runner:local "$HERE/test/runner" >/dev/null || { echo "runner 빌드 실패" >&2; exit 1; }
for _ in 1 2 3 4 5 6 7 8 9 10; do [ "$(curl -s -o /dev/null -w '%{http_code}' http://$REG/v2/)" = 401 ] && break; sleep 1; done
expect "레지스트리가 인증을 요구함" "$(curl -s -o /dev/null -w '%{http_code}' http://$REG/v2/)" 401

mk() { # 태그 플랫폼 revision [추가 파일]
  local d="$W/ctx-$1"
  mkdir -p "$d"
  printf 'FROM --platform=%s %s\nLABEL org.opencontainers.image.revision=%s\n' "$2" "$ALPINE" "$3" >"$d/Dockerfile"
  if [ -n "${4:-}" ]; then cp "$4" "$d/extra" && printf 'COPY extra /opt/extra\n' >>"$d/Dockerfile"; fi
  docker build -q --platform "$2" -t "$T-$1:local" "$d" >/dev/null || bad "시험 이미지 $1 빌드"
}
mk good linux/amd64 $R1
mk arm linux/arm64 $R1
mk other linux/amd64 $R2
mk nolabel linux/amd64 0000000000000000000000000000000000000000
dd if=/dev/urandom of="$W/big.bin" bs=1m count=150 2>/dev/null
mk big linux/amd64 $R2 "$W/big.bin"
# 가짜 AWS 키(시험 때만 만든다. 저장소에는 키 모양 문자열을 두지 않는다)
key="AKIA$(LC_ALL=C tr -dc 'A-Z2-7' </dev/urandom | head -c 16)"
printf 'aws_access_key_id = %s\naws_secret_access_key = %s\n' "$key" "$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 40)" >"$W/leak.txt"
mk leak linux/amd64 $R1 "$W/leak.txt"

# runner 안: 호스트 Docker, 자기만의 docker 설정(로그인 정보가 호스트 설정에 남지 않음), 가짜 docker 래퍼
mkdir -p "$W/bin"
cat >"$W/bin/docker" <<'EOF'
#!/usr/bin/env bash
# 시험용 래퍼: push를 기록하고, 플래그에 따라 실패·덮어쓰기를 흉내 낸다
if [[ $1 == push ]]; then
  echo "$*" >>"$WRAP_LOG"
  [[ -e $WRAP_FLAGS/push-fail ]] && { echo "mock push 실패" >&2; exit 1; }
  /usr/local/bin/docker "$@" || exit $?
  if [[ -e $WRAP_FLAGS/tamper ]]; then # 올린 직후 누군가 같은 태그를 다른 이미지로 덮어씀
    /usr/local/bin/docker tag hs-img-ci-test-other:local "${@: -1}" && /usr/local/bin/docker push -q "${@: -1}" >/dev/null
  fi
  exit 0
fi
exec /usr/local/bin/docker "$@"
EOF
chmod 0755 "$W/bin/docker"
mkdir -p "$W/flags"
inr() { # runner 안에서 명령
  docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v "$W:$W" -v "$ROOT:$ROOT:ro" \
    -e PATH="$W/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" -e WRAP_LOG="$W/push.log" -e WRAP_FLAGS="$W/flags" \
    -e IMAGE_REPOSITORY="$REPO" -e TRIVY_CACHE_VOLUME=$T-trivy-cache $T-runner:local bash -c "$1"
}
login='printf testpass | docker login '$REG' -u ci --password-stdin >/dev/null 2>&1'
pub() { # 로컬 이미지 revision [로그인 안 함]
  local pre="$login &&"
  [ "${3:-}" = nologin ] && pre=""
  inr "$pre LOCAL_IMAGE=$T-$1:local REVISION=$2 '$HERE/publish.sh'" >"$W/pub.out" 2>&1
  echo $?
}
digest_of() { grep -oE '^digest=sha256:[0-9a-f]{64}$' "$W/pub.out" | cut -d= -f2; }
pushes() { wc -l <"$W/push.log" 2>/dev/null | tr -d ' ' || echo 0; }

echo "== 발행(publish)"
expect "GHCR 인증 실패: 비밀번호 틀림" "$(inr "printf wrong | docker login $REG -u ci --password-stdin >/dev/null 2>&1; echo \$?")" 1
expect "GHCR 인증 실패: 로그인 없이 발행 → 레지스트리 조회 실패(23)" "$(pub good $R1 nologin)" 23
expect "잘못된 아키텍처(arm64) → 20" "$(pub arm $R1)" 20
expect "커밋 라벨 다름 → 21" "$(pub nolabel $R1)" 21
expect "REVISION 형식 아님 → 2" "$(pub good abc)" 2
touch "$W/flags/push-fail"
expect "Push 실패 → 24" "$(pub good $R1)" 24
rm -f "$W/flags/push-fail"
expect "Push 실패 뒤 태그가 생기지 않음" "$(inr "$login && docker buildx imagetools inspect --raw $REPO:sha-$R1 >/dev/null 2>&1; echo \$?")" 1

expect "정상 발행 → 0" "$(pub good $R1)" 0
d1="$(digest_of)"
expect "digest 형식" "$(echo "$d1" | grep -cE '^sha256:[0-9a-f]{64}$')" 1
expect "레지스트리 태그의 실제 manifest digest와 같음" "$(inr "$login && docker buildx imagetools inspect --raw $REPO:sha-$R1 | sha256sum | cut -c1-64")" "${d1#sha256:}"
expect "digest로 받으면 linux/amd64·커밋 라벨" "$(inr "$login && docker buildx imagetools inspect $REPO@$d1 --format '{{json .Image}}' | jq -r '\"\(.os)/\(.architecture) \(.config.Labels[\"org.opencontainers.image.revision\"])\"'")" "linux/amd64 $R1"
kind="$(inr "$login && docker buildx imagetools inspect --raw $REPO@$d1 | jq -r .mediaType")"
echo "     (이 Docker의 Push 형식: $kind)"
case "$kind" in *image.manifest* | *image.index*) ok "manifest 형식 확인" ;; *) bad "manifest 형식 $kind" ;; esac
expect "목록이면 linux/amd64·unknown/unknown만" "$(inr "$login && docker buildx imagetools inspect --raw $REPO@$d1 | jq -r '[.manifests[]?.platform | \"\\(.os)/\\(.architecture)\" | select(. != \"linux/amd64\" and . != \"unknown/unknown\")] | length'")" 0
grep -q "^image=$REPO@$d1$" "$W/pub.out" && ok "배포 입력용 image=저장소@digest 출력" || bad "image 출력"

before="$(pushes)"
expect "재실행(같은 커밋) → 0" "$(pub good $R1)" 0
expect "재실행은 같은 digest" "$(digest_of)" "$d1"
expect "재실행은 Push하지 않음" "$(pushes)" "$before"
grep -q '이미 발행됐습니다' "$W/pub.out" && ok "재실행 안내" || bad "재실행 안내"

# 중복 태그: sha-R2 태그에 다른 커밋 이미지가 이미 있음
inr "$login && docker tag $T-good:local $REPO:sha-$R2 && docker push -q $REPO:sha-$R2 >/dev/null" >/dev/null 2>&1
expect "같은 태그가 다른 커밋 이미지를 가리킴 → 22(덮어쓰지 않음)" "$(pub other $R2)" 22
expect "기존 태그 그대로" "$(inr "$login && docker buildx imagetools inspect --raw $REPO:sha-$R2 | sha256sum | cut -c1-64")" "${d1#sha256:}"

R3=3333333333333333333333333333333333333333
mk good3 linux/amd64 $R3
touch "$W/flags/tamper"
expect "올린 직후 태그가 바뀜 → digest 확인 실패(25)" "$(pub good3 $R3)" 25
rm -f "$W/flags/tamper"

echo "== 빌드 중 취소(Push 도중 SIGTERM) → 재실행"
inr "$login && (LOCAL_IMAGE=$T-big:local REVISION=$R2 IMAGE_REPOSITORY=$REG/$T/big '$HERE/publish.sh' & p=\$!; sleep 1; kill -TERM \$p; wait \$p; echo killed=\$?)" >"$W/cancel.out" 2>&1
state="$(inr "$login && docker buildx imagetools inspect --raw $REG/$T/big:sha-$R2 >/dev/null 2>&1 && echo tagged || echo none")"
case "$state" in none | tagged) ok "취소 뒤 태그는 없거나 완전함($state)" ;; *) bad "취소 뒤 상태 $state" ;; esac
expect "취소 뒤 재실행 → 0" "$(inr "$login && LOCAL_IMAGE=$T-big:local REVISION=$R2 IMAGE_REPOSITORY=$REG/$T/big '$HERE/publish.sh' >/dev/null 2>&1; echo \$?")" 0

echo "== 검사(scan, 실제 Trivy)"
sc() { inr "TRIVY_DB_REPOSITORY=${2:-} REQUIRE_JAVA=0 '$HERE/scan.sh' $T-$1:local '$W/scan-$1' >'$W/scan-$1.out' 2>&1; echo \$?"; }
expect "Trivy DB를 받지 못함 → 통과로 보지 않음(3)" "$(sc good 127.0.0.1:1/none/trivy-db)" 3
# 깨끗한 이미지: 고정한 alpine은 나중에 고친 판이 나온 취약점으로 막힐 수 있어(2026-11-26 OpenSSL CVE-2026-124709),
# 시험 때 OS 패키지를 최신으로 올린 이미지로 본다. 막는 정책은 위·아래 시험과 evaluate 시험이 따로 확인한다
mkdir -p "$W/clean" && printf 'FROM %s\nRUN apk upgrade --no-cache >/dev/null\n' "$ALPINE" >"$W/clean/Dockerfile"
docker build -q -t "$T-clean:local" "$W/clean" >/dev/null
expect "깨끗한 시험 이미지(OS 패키지 최신) → 0" "$(sc clean)" 0
expect "가짜 AWS 키가 든 이미지 → 11" "$(sc leak)" 11
grep -q 'aws-access-key-id' "$W/scan-leak.out" && ok "규칙 이름이 보고에 남음" || bad "비밀값 보고"
grep -qF "$key" "$W/scan-leak.out" && bad "보고 출력에 키 원문이 나옴" || ok "보고 출력에 키 원문 없음"
expect "Java 대상이 없는데 REQUIRE_JAVA=1 → 3" "$(inr "'$HERE/scan.sh' $T-good:local '$W/scan-j' >/dev/null 2>&1; echo \$?")" 3

echo "== 빌드 실패"
mkdir -p "$W/badbase" && printf 'FROM nonexistent.invalid/none:1\n' >"$W/badbase/Dockerfile"
docker build -q "$W/badbase" >/dev/null 2>&1; expect "Docker 빌드 실패(기반 이미지 없음) → 0이 아님" "$([ $? -ne 0 ] && echo fail)" fail
if [ "${SKIP_BACK:-0}" != 1 ]; then
  mkdir -p "$W/back" && (cd "$ROOT/back" && tar cf - --exclude build --exclude .gradle .) | (cd "$W/back" && tar xf -)
  f="$(find "$W/back/src/main/java" -name 'BackApplication.java' | head -n 1)"
  printf '\nclass Broken { int x = ; }\n' >>"$f"
  docker build -q -t $T-broken:local "$W/back" >"$W/broken.out" 2>&1
  expect "백엔드 컴파일 실패 → 이미지 빌드 실패" "$([ $? -ne 0 ] && echo fail)" fail
  grep -q 'gradlew bootJar' "$W/broken.out" && ok "실패 원인이 컴파일 단계" || bad "실패 원인"
else
  echo "skip 백엔드 컴파일 실패(SKIP_BACK=1)"
fi

echo "passed=$passed failed=$failed"
[ "$failed" -eq 0 ]
