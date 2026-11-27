#!/usr/bin/env bash
# 화면 이미지(front/Dockerfile·check-front.sh·scan-front.sh·front-image-publish.yml) 시험. GHCR·AWS 없음. 빈 작업 폴더를 인자로 준다.
#   mkdir -p /tmp/hs-front-work && .github/scripts/image/test/front-test.sh /tmp/hs-front-work
# - 실제 front/Dockerfile로 linux/amd64 이미지를 빌드한다(빌드 단계는 이 기계의 플랫폼, 마지막 이미지만 amd64)
# - 검사(Trivy)·확인·발행은 Linux runner 컨테이너에서 호스트 Docker 소켓으로. GHCR 대신 시험용 레지스트리(127.0.0.1:15101)
# - 이름이 hs-front-ci-test 로 시작하는 컨테이너·이미지·볼륨만 만들고 끝나면 지운다
set -uo pipefail
W="${1:?빈 작업 폴더를 주세요}"
[ -d "$W" ] && [ -z "$(ls -A "$W")" ] || { echo "빈 폴더가 아닙니다: $W" >&2; exit 2; }
W="$(cd "$W" && pwd -P)"
HERE="$(cd "$(dirname "$0")/.." && pwd -P)"
ROOT="$(cd "$HERE/../../.." && pwd -P)"
T=hs-front-ci-test
REGISTRY_IMAGE=registry:3@sha256:82f6ff4a7f982960c8aa51617fea3aabc485df6e55cce80d22deb1de36f1bc3c
REG=127.0.0.1:15101
REPO=$REG/$T/front
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

echo "== 워크플로 경로: 화면·백엔드 따로, 공통 스크립트는 둘 다"
paths() { awk '/^  (pull_request|push):/{on=1; next} /^  [a-z_]+:/{on=0} on && /^      - /{print $2}' "$ROOT/.github/workflows/$1" | sort -u | tr '\n' ' '; }
expect "front-image-publish 트리거" "$(paths front-image-publish.yml)" ".github/scripts/image/** .github/workflows/front-image-publish.yml front/** "
expect "image-publish(백엔드) 트리거는 그대로(front/ 없음)" "$(paths image-publish.yml)" ".github/scripts/image/** .github/workflows/image-publish.yml back/** "
grep -q 'vars.FRONT_IMAGE_PUBLISH_ENABLED' "$ROOT/.github/workflows/front-image-publish.yml" && ! grep -q 'vars.IMAGE_PUBLISH_ENABLED' "$ROOT/.github/workflows/front-image-publish.yml" &&
  ok "화면은 화면 발행 변수만 읽음" || bad "화면 발행 변수"
grep -q 'vars.FRONT_IMAGE' "$ROOT/.github/workflows/image-publish.yml" && bad "백엔드가 화면 변수를 읽음" || ok "백엔드는 화면 변수를 읽지 않음"

echo "== 발행 허용 판단(gate, 화면 변수 이름)"
g() { EVENT_NAME=$1 REF=$2 PUBLISH_ENABLED=$3 IMAGE_REPOSITORY=$4 PUBLISH_VAR=FRONT_IMAGE_PUBLISH_ENABLED REPOSITORY_VAR=FRONT_IMAGE_REPOSITORY "$HERE/gate.sh"; }
expect "PR → 발행 안 함" "$(g pull_request refs/heads/main true ghcr.io/o/homestamp-front | head -n 1)" publish=false
expect "main인데 화면 변수 없음(지금 상태)" "$(g push refs/heads/main '' '' | head -n 1)" publish=false
g push refs/heads/main '' '' | grep -q 'FRONT_IMAGE_PUBLISH_ENABLED가' && ok "이유에 화면 변수 이름" || bad "이유의 변수 이름"
expect "main + 허용 + 이름 → 발행" "$(g workflow_dispatch refs/heads/main true ghcr.io/prgrms-be-devcourse/homestamp-front | head -n 1)" publish=true

echo "== 검사 판정(evaluate, npm·scratch 모양)"
pk() { printf '{"Name":"%s","Version":"1","Dev":%s}' "$1" "$2"; }
v() { printf '{"VulnerabilityID":"%s","PkgName":"p","InstalledVersion":"1","FixedVersion":"%s","Severity":"%s"}' "$1" "$2" "$3"; }
fxn() { # 이름 패키지JSON 취약점JSON [ImageID]
  printf '{"Metadata":{"ImageID":"%s"},"Results":[{"Target":"package-lock.json","Class":"lang-pkgs","Type":"npm","Packages":%s,"Vulnerabilities":%s}]}' \
    "${4:-}" "$2" "$3" >"$W/$1.json"
}
evn() { REQUIRE_OS=0 REQUIRE_JAVA=0 REQUIRE_NPM=1 "$HERE/evaluate.sh" "$W/$1.json" >/dev/null 2>&1; echo $?; }
fxn n-clean "[$(pk react false),$(pk vite true)]" "[$(v CVE-1 2 MEDIUM)]"; expect "npm MEDIUM만 → 0" "$(evn n-clean)" 0
fxn n-high "[$(pk react false),$(pk vite true)]" "[$(v CVE-2 2 HIGH)]";  expect "npm HIGH 고친 판 있음 → 10" "$(evn n-high)" 10
fxn n-nodev "[$(pk react false)]" '[]';                                  expect "개발 의존성을 읽지 않음 → 3" "$(evn n-nodev)" 3
fxn n-empty '[]' '[]';                                                   expect "패키지 없음 → 3" "$(evn n-empty)" 3
printf '{"Results":[]}' >"$W/n-none.json";                               expect "npm 대상 없음 → 3" "$(evn n-none)" 3
fxn img-ok '[]' '[]' sha256:aaa
evi() { REQUIRE_OS=0 REQUIRE_JAVA=0 EXPECT_IMAGE_ID=$2 "$HERE/evaluate.sh" "$W/$1.json" >/dev/null 2>&1; echo $?; }
expect "검사한 이미지 ID가 같음 → 0" "$(evi img-ok sha256:aaa)" 0
expect "검사한 이미지 ID가 다름 → 3" "$(evi img-ok sha256:bbb)" 3
expect "기본(백엔드)은 그대로 OS 필요 → 3" "$("$HERE/evaluate.sh" "$W/img-ok.json" >/dev/null 2>&1; echo $?)" 3

echo "== 준비(시험 레지스트리·runner)"
docker run -d --name $T-registry -p "$REG:5000" "$REGISTRY_IMAGE" >/dev/null
docker build -q -t $T-runner:local "$HERE/test/runner" >/dev/null || { echo "runner 빌드 실패" >&2; exit 1; }
inr() { docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v "$W:$W" -v "$ROOT:$ROOT:ro" \
  -e TRIVY_CACHE_VOLUME=$T-trivy-cache $T-runner:local bash -c "$1"; }

echo "== 실제 빌드(front/Dockerfile)"
# 빌드 맥락 시험용 복사본: 들어오면 안 되는 파일을 심는다(.env는 vite가 읽으므로 들어오면 결과가 바뀐다)
mkdir -p "$W/ctx" && (cd "$ROOT/front" && tar cf - --exclude node_modules --exclude dist .) | (cd "$W/ctx" && tar xf -)
printf 'VITE_API_BASE_URL=https://leak.invalid\nVITE_API_MODE=mock\n' >"$W/ctx/.env.production"
mkdir -p "$W/ctx/node_modules/planted" "$W/ctx/dist" && echo planted >"$W/ctx/node_modules/planted/x.js" && echo planted >"$W/ctx/dist/old.js"
fb() { # 태그 revision 맥락 → 종료 코드
  docker build --progress=plain --platform linux/amd64 ${2:+--build-arg "REVISION=$2"} --label "org.opencontainers.image.revision=${2:-none}" \
    -t "$T-$1:local" -f "$3/Dockerfile" "$3" >"$W/build-$1.out" 2>&1
  echo $?
}
expect "정상 빌드 → 0" "$(fb good $R1 "$W/ctx")" 0
expect "linux/amd64" "$(docker image inspect -f '{{.Os}}/{{.Architecture}}' $T-good:local)" linux/amd64
expect "커밋 없이 빌드 → 실패" "$([ "$(fb norev '' "$W/ctx")" != 0 ] && echo fail)" fail
expect "커밋 형식 아님 → 실패" "$([ "$(fb badrev XYZ "$W/ctx")" != 0 ] && echo fail)" fail
mkdir -p "$W/broken" && (cd "$W/ctx" && tar cf - --exclude node_modules --exclude dist .) | (cd "$W/broken" && tar xf -)
printf '\nconst broken: number = "x"\nexport default broken\n' >>"$W/broken/src/config.ts"
expect "타입 오류(tsc -b) → 빌드 실패" "$([ "$(fb broken $R1 "$W/broken")" != 0 ] && echo fail)" fail
grep -q 'error TS' "$W/build-broken.out" && ok "실패 원인이 타입 검사" || bad "실패 원인"

echo "== 내용 확인(check-front, docker create·export·cp)"
ck() { inr "'$HERE/check-front.sh' $1 $2 >'$W/ck.out' 2>&1; echo \$?"; }
expect "실제 이미지 → 0" "$(ck $T-good:local $R1)" 0
expect "커밋이 다름 → 30" "$(ck $T-good:local $R2)" 30
mkdir -p "$W/site" && c="$(docker create --platform linux/amd64 $T-good:local)" && docker cp -q "$c:/site" "$W/" && docker rm "$c" >/dev/null
js="$(ls "$W"/site/assets/*.js)"
grep -q '/api/auth/login' $js && ok "같은 출처 /api(상대 경로)로 부름" || bad "/api 경로"
grep -qE 'leak\.invalid|https?://localhost' $js && bad "맥락의 .env·개발 주소가 들어감" || ok "맥락의 .env.production이 들어오지 않음(API 주소 그대로)"
grep -q 'dev-landlord' $js && bad "목업(mock) 데이터가 들어감" || ok "실제 API 모드(목업 데이터 없음)"
expect "심은 node_modules·dist·소스맵 없음" "$(find "$W/site" \( -name planted -o -name old.js -o -name '*.map' -o -name '*.tsx' \) | wc -l | tr -d ' ')" 0
ls "$W"/site/assets/*.css >/dev/null 2>&1 && ok "CSS 있음" || bad "CSS"
c="$(docker create --platform linux/amd64 $T-good:local)"
docker export "$c" | docker run --rm -i $T-runner:local tar -tvf - | grep ' site/' >"$W/perm.txt"; docker rm "$c" >/dev/null
expect "/site 파일 644·폴더 755, root(0/0) 소유" "$(grep -vcE '^(-rw-r--r--|drwxr-xr-x) 0/0 ' "$W/perm.txt")" 0
expect "실행 환경 없음(레이어 하나, scratch)" "$(docker image inspect -f '{{len .RootFS.Layers}}' $T-good:local)" 1

# 계약을 어긴 이미지들(scratch + /site)
mkc() { # 태그 플랫폼 revision 준비명령
  local d="$W/bad-$1"
  mkdir -p "$d/site/assets" && cp "$W/site/index.html" "$d/site/" && cp "$W"/site/assets/* "$d/site/assets/"
  printf '{"revision":"%s"}\n' "$3" >"$d/site/version.json"
  (cd "$d" && eval "$4")
  [ -f "$d/Dockerfile" ] || printf 'FROM scratch\nCOPY site/ /site/\nCMD ["/x"]\n' >"$d/Dockerfile"
  docker build -q --platform "$2" --label "org.opencontainers.image.revision=$3" -t "$T-bad-$1:local" "$d" >/dev/null || bad "시험 이미지 $1 빌드"
}
mkc map linux/amd64 $R1 'echo "{}" >site/assets/index.js.map'
mkc nm linux/amd64 $R1 'mkdir -p site/node_modules && echo x >site/node_modules/a.js'
mkc nover linux/amd64 $R1 'rm site/version.json'
mkc src linux/amd64 $R1 'echo "export {}" >site/assets/a.tsx'
mkc pkg linux/amd64 $R1 'echo "{}" >site/package.json'
mkc odd linux/amd64 $R1 'echo x >site/run.sh'
mkc noidx linux/amd64 $R1 'rm site/index.html'
mkc dev linux/amd64 $R1 'echo "fetch(\"http://localhost:8080/api\")" >site/assets/dev.js'
mkc arm linux/arm64 $R1 ':'
mkc outside linux/amd64 $R1 'printf "FROM scratch\nCOPY site/ /site/\nCOPY site/index.html /etc/x.html\nCMD [\"/x\"]\n" >Dockerfile'
for b in map nm nover src pkg odd noidx dev arm outside; do expect "계약 위반($b) → 30" "$(ck $T-bad-$b:local $R1)" 30; done

echo "== 보안 검사(scan-front, 실제 Trivy)"
sf() { inr "${3:-} '$HERE/scan-front.sh' '$1' $2 '$W/scan-$4' >'$W/scan-$4.out' 2>&1; echo \$?"; }
# 캐시가 비어 있을 때(처음) 먼저: DB를 받지 못하면 통과가 아니다
expect "Trivy DB를 받지 못함 → 3" "$(sf "$ROOT/front" $T-good:local 'TRIVY_DB_REPOSITORY=127.0.0.1:1/none/trivy-db' nodb)" 3
expect "실제 front + 실제 이미지 → 0" "$(sf "$ROOT/front" $T-good:local '' good)" 0
grep -q '의존성=0 이미지=0' "$W/scan-good.out" && ok "두 검사를 모두 함" || bad "두 검사 출력"
expect "검사한 의존성에 개발 의존성 포함" "$(jq '[.Results[].Packages[]? | select(.Dev == true)] | length > 0' "$W/scan-good/trivy-deps.json")" true
# 고친 판이 있는 HIGH(lodash 4.17.15, CVE-2020-8203)를 개발 의존성으로 둔 lockfile
mkdir -p "$W/vuln"
printf '{"name":"v","version":"0.0.0","devDependencies":{"lodash":"4.17.15"}}\n' >"$W/vuln/package.json"
printf '{"name":"v","version":"0.0.0","lockfileVersion":3,"requires":true,"packages":{"":{"name":"v","version":"0.0.0","devDependencies":{"lodash":"4.17.15"}},"node_modules/lodash":{"version":"4.17.15","resolved":"https://registry.npmjs.org/lodash/-/lodash-4.17.15.tgz","dev":true}}}\n' >"$W/vuln/package-lock.json"
expect "개발 의존성의 막을 취약점 → 10(발행 안 함)" "$(sf "$W/vuln" $T-good:local '' vuln)" 10
# 가짜 AWS 키를 /site에 심은 이미지(시험 때만 만든다. 저장소에는 키 모양 문자열을 두지 않는다)
key="AKIA$(LC_ALL=C tr -dc 'A-Z2-7' </dev/urandom | head -c 16)"
printf 'const aws_access_key_id="%s";const aws_secret_access_key="%s"\n' "$key" "$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 40)" >"$W/leak.js"
mkc leak linux/amd64 $R1 'cp "$W/leak.js" site/assets/leak.js'
expect "/site에 비밀값 → 11" "$(sf "$ROOT/front" $T-bad-leak:local '' leak)" 11
grep -qF "$key" "$W/scan-leak.out" && bad "출력에 키 원문이 나옴" || ok "출력에 키 원문 없음"

echo "== 발행(publish.sh 그대로) → digest로 받아 다시 확인"
pub() { inr "IMAGE_REPOSITORY=$REPO LOCAL_IMAGE=$1 REVISION=$2 '$HERE/publish.sh'" >"$W/pub.out" 2>&1; echo $?; }
expect "화면 이미지 발행 → 0" "$(pub $T-good:local $R1)" 0
d1="$(grep -oE '^digest=sha256:[0-9a-f]{64}$' "$W/pub.out" | cut -d= -f2)"
expect "digest 형식" "$(echo "$d1" | grep -cE '^sha256:[0-9a-f]{64}$')" 1
expect "재실행 → 같은 digest, Push 안 함" "$(pub $T-good:local $R1)$(grep -c '이미 발행됐습니다' "$W/pub.out")" 01
docker image rm -f "$REPO:sha-$R1" >/dev/null 2>&1
expect "digest로 받은 이미지 → check-front 0" "$(inr "docker pull -q --platform linux/amd64 $REPO@$d1 >/dev/null && '$HERE/check-front.sh' $REPO@$d1 $R1 >/dev/null 2>&1; echo \$?")" 0

echo "passed=$passed failed=$failed"
[ "$failed" -eq 0 ]
