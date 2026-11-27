#!/usr/bin/env bash
# deploy-front.sh 실제 시험(로컬 Docker만. AWS·GHCR·운영 Secret 없음). CI에서는 돌리지 않는다(host 네트워크 443을 씀)
#   front-test.sh <저장소 루트> <빈 작업 폴더> [real]
#   필요: Docker 호스트의 443·15101이 비어 있고 homestamp-nginx 컨테이너가 없을 것. real을 주면 front/Dockerfile 실제 빌드도 배포한다
# - 실제 nginx(#204 설정·render.sh, 시험 CA로 HTTPS) + 시험 레지스트리(digest로 받기) + GNU 도구 runner(AL2023과 같은 find·mv)
# - 화면 이미지는 계약대로 만든 scratch 이미지(판마다 다른 해시 이름 JS·CSS·동적 chunk, 판끼리 같은 글꼴 하나)
# - 계약 시험: 같은 이미지를 check-front.sh(CI)와 deploy-front.sh --check-dir(서버)에 넣어 판단이 같은지
# shellcheck disable=SC2154 # IMG_<태그>는 printf -v로 만든다
set -uo pipefail
SRC="$(cd "${1:?저장소 루트}" && pwd -P)"
W="${2:?빈 작업 폴더}"
REAL="${3:-}"
T=hs-fdt
TOOLS_BASE=docker:29-cli@sha256:34507eb5069d8be82c95dd3b6987d4250fd5fee49b82e2509b7d7a7030740d6d
if [ "${4:-}" != --inside ]; then
  # 시험 전체를 Linux(GNU 도구) 컨테이너 안에서 돌린다. 경로는 호스트와 같게 묶어 형제 컨테이너(nginx·runner)도 같은 경로를 본다
  [ -d "$W" ] && [ -z "$(ls -A "$W")" ] || { echo "빈 폴더가 아닙니다: $W" >&2; exit 2; }
  W="$(cd "$W" && pwd -P)"
  mkdir -p "$W/ca"
  printf 'FROM %s\nRUN apk add --no-cache bash curl util-linux-misc openssl ca-certificates coreutils findutils tar jq git\n' "$TOOLS_BASE" >"$W/ca/Dockerfile"
  docker build -q -t $T-tools:local "$W/ca" >/dev/null || { echo "도구 이미지 실패" >&2; exit 1; }
  # 화면 폴더·상태는 Docker 볼륨에 둔다(Linux 파일 권한이 실제로 걸림. macOS 공유 폴더는 컨테이너 사용자 권한을 막지 않음)
  docker volume rm -f $T-vol >/dev/null 2>&1
  docker volume create $T-vol >/dev/null
  docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v "$W:$W" -v "$SRC:$SRC:ro" -v $T-vol:/work \
    $T-tools:local bash "$SRC/infra/deploy/test/front-test.sh" "$SRC" "$W" "$REAL" --inside
  rc=$?
  docker volume rm -f $T-vol >/dev/null 2>&1
  docker image rm -f $T-tools:local >/dev/null 2>&1
  exit $rc
fi
V=/work
NGINX_IMAGE=nginx:1.28-alpine@sha256:a8b39bd9cf0f83869a2162827a0caf6137ddf759d50a171451b335cecc87d236
REGISTRY_IMAGE=registry:3@sha256:82f6ff4a7f982960c8aa51617fea3aabc485df6e55cce80d22deb1de36f1bc3c
REPO=127.0.0.1:15101/$T/front
EDGE=homestamp-nginx
DOMAIN=homestamp.test
passed=0
failed=0
ok() { passed=$((passed + 1)); echo "ok   $1"; }
bad() { failed=$((failed + 1)); echo "FAIL $1"; }
expect() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (기대 $3, 실제 $2)"; fi; }

cleanup() {
  ids="$(docker ps -aq --filter "name=^$T-") $(docker ps -aq --filter "name=^$EDGE\$") $(docker ps -aq --filter "name=^homestamp-front-extract-")"
  [ -z "${ids// /}" ] || docker rm -f $ids >/dev/null 2>&1
  imgs="$(docker image ls -q --filter "reference=$T-runner*"; docker image ls -q --filter "reference=$REPO")"
  [ -z "$imgs" ] || docker image rm -f $imgs >/dev/null 2>&1
}
[ -z "$(docker ps -aq --filter "name=^$EDGE\$")" ] || { echo "이미 $EDGE 컨테이너가 있습니다(지우지 않음)" >&2; exit 2; }
trap cleanup EXIT
cleanup

mkdir -p "$W/le/live/$DOMAIN" "$W/webroot" "$V/front" "$V/state" "$V/lock" "$W/out" "$W/img" "$W/backstate"
# ---- 시험 CA·인증서. runner = 도구 이미지(GNU coreutils·findutils·tar, flock, curl, docker CLI) + 시험 CA 신뢰
tools() { docker run --rm -v "$W:$W" -w "$W" $T-tools:local "$@"; }
tools sh -c "openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj /CN=hs-fdt-ca -keyout ca/ca.key -out ca/ca.crt \
  -addext basicConstraints=critical,CA:TRUE -addext keyUsage=critical,keyCertSign,cRLSign 2>/dev/null &&
  openssl req -newkey rsa:2048 -nodes -subj /CN=$DOMAIN -keyout le/live/$DOMAIN/privkey.pem -out ca/edge.csr 2>/dev/null &&
  printf 'subjectAltName=DNS:$DOMAIN\nextendedKeyUsage=serverAuth\n' >ca/edge.ext &&
  openssl x509 -req -in ca/edge.csr -CA ca/ca.crt -CAkey ca/ca.key -CAcreateserial -days 2 -extfile ca/edge.ext -out le/live/$DOMAIN/fullchain.pem 2>/dev/null"
cat >"$W/ca/Dockerfile.runner" <<EOF
FROM $T-tools:local
COPY ca.crt /usr/local/share/ca-certificates/hs-fdt-ca.crt
RUN update-ca-certificates >/dev/null 2>&1
EOF
docker build -q -f "$W/ca/Dockerfile.runner" -t $T-runner:local "$W/ca" >/dev/null || { echo "runner 실패" >&2; exit 1; }
docker run -d --name $T-registry -p 127.0.0.1:15101:5000 "$REGISTRY_IMAGE" >/dev/null
for _ in 1 2 3 4 5 6 7 8 9 10; do curl -fs http://127.0.0.1:15101/v2/ >/dev/null && break; sleep 1; done

# ---- 실제 nginx(#204): 설정 복사 → render.sh(HTTPS) → compose와 같은 보안 옵션, host 네트워크
cp -R "$SRC/infra/nginx" "$W/nginx" && rm -rf "$W/nginx/run" "$W/nginx/test"
printf 'DOMAIN=%s\nPHOTOS_BUCKET=homestamp-photos\nAPP_UPSTREAM=127.0.0.1:18191\nHSTS_MAX_AGE=\nCERT_ROOT=%s\n' "$DOMAIN" "$W/le" >"$W/nginx/homestamp.env"
bash "$W/nginx/render.sh" "$W/nginx/homestamp.env" >/dev/null || { echo "render 실패" >&2; exit 1; }
start_edge() {
  docker run -d --name "$EDGE" --network host --read-only --tmpfs /tmp --tmpfs /var/cache/nginx \
    --security-opt no-new-privileges:true --cap-drop ALL --cap-add NET_BIND_SERVICE --cap-add SETUID --cap-add SETGID --cap-add CHOWN \
    -v "$W/nginx/nginx.conf:/etc/nginx/nginx.conf:ro" -v "$W/nginx/run/conf.d:/etc/nginx/conf.d:ro" \
    -v "$W/nginx/run/snippets:/etc/nginx/snippets:ro" -v "$W/nginx/run/upstream:/etc/nginx/homestamp:ro" \
    -v "$W/le:/etc/letsencrypt:ro" -v "$W/webroot:/var/www/certbot:ro" --mount "type=volume,src=$T-vol,dst=/srv/homestamp/front,volume-subpath=front,readonly" \
    "$NGINX_IMAGE" >/dev/null
  for _ in $(seq 1 20); do docker exec "$EDGE" nginx -t >/dev/null 2>&1 && break; sleep 0.5; done
}
# 운영과 같이 nginx를 먼저 띄운다(FRONT_ROOT만 있고 current 없음 = 아직 배포 안 함)
mkdir -p "$V/front/releases" "$V/front/assets"
start_edge

cat >"$W/deploy-front.env" <<EOF
IMAGE_REPOSITORY=$REPO
FRONT_ROOT=$V/front
STATE_DIR=$V/state
LOCK_FILE=$V/lock/homestamp-deploy.lock
VERIFY_URL=https://$DOMAIN
VERIFY_RESOLVE=$DOMAIN:443:127.0.0.1
VERIFY_TIMEOUT=3
EOF
echo 0 >"$W/n"
runner() { docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v "$W:$W" -v "$SRC:$SRC:ro" -v $T-vol:/work "$@"; }
deploy() { # [--config 파일] 이미지 → 종료 코드(출력은 $W/out/N)
  local n
  n=$(($(cat "$W/n") + 1))
  echo "$n" >"$W/n"
  local cfg="$W/deploy-front.env"
  if [ "$1" = --config ]; then cfg=$2; shift 2; fi
  runner $T-runner:local bash "$SRC/infra/deploy/deploy-front.sh" --config "$cfg" "$@" >"$W/out/$n" 2>&1
  echo $?
}
out() { cat "$W/out/$(cat "$W/n")"; }
edge() { runner $T-runner:local curl -s --max-time 5 --resolve "$DOMAIN:443:127.0.0.1" "$@"; }
served_rev() { edge "https://$DOMAIN/version.json"; }
code() { edge -o /dev/null -w '%{http_code}' "https://$DOMAIN$1"; }
cur() { readlink "$V/front/current" 2>/dev/null || echo -; }
lastj() { tail -n 1 "$V/state/history.jsonl"; }
field() { lastj | grep -oE "\"$1\":\"?[^,\"]*" | head -n 1 | sed -E 's/.*:"?//'; }
snap() { (cd "$V/front" && find . -path ./.incoming -prune -o -print | LC_ALL=C sort | while read -r p; do printf '%s %s\n' "$p" "$( [ -f "$p" ] && sha256sum "$p" | cut -c1-12)"; done; readlink current 2>/dev/null); }

# ---- 화면 이미지 만들기
h8() { printf '%s' "$1" | sha256sum | cut -c1-8; }
rev_of() { printf '%s' "$1" | sha256sum | cut -c1-40; }
site_std() { # <폴더> <판 이름> <커밋>: 계약대로인 /site
  local d=$1 v=$2 r=$3 h
  h="$(h8 "$v")"
  mkdir -p "$d/site/assets"
  printf '<!doctype html><html><head><script type="module" src="/assets/index-%s.js"></script><link rel="stylesheet" href="/assets/index-%s.css"></head><body>%s</body></html>\n' "$h" "$h" "$v" >"$d/site/index.html"
  printf 'console.log("%s");import("/assets/lazy-%s.js")\n' "$v" "$h" >"$d/site/assets/index-$h.js"
  printf 'export const v="%s"\n' "$v" >"$d/site/assets/lazy-$h.js"
  printf 'body{content:"%s"}\n' "$v" >"$d/site/assets/index-$h.css"
  printf 'shared-font\n' >"$d/site/assets/font-SHAREDaa.woff2"
  printf '{"revision":"%s"}\n' "$r" >"$d/site/version.json"
}
mk() { # <태그> <커밋> [플랫폼] [추가 Dockerfile 줄]: $W/img/<태그>/site로 이미지를 만들고 push → IMG_<태그>=digest 참조
  local tag=$1 r=$2 plat=${3:-linux/amd64} extra=${4:-}
  printf 'FROM scratch\nCOPY site/ /site/\n%s\nCMD ["/not-runnable"]\n' "$extra" >"$W/img/$tag/Dockerfile"
  docker build -q --platform "$plat" --label "org.opencontainers.image.revision=$r" -t "$REPO:$tag" "$W/img/$tag" >/dev/null &&
    docker push -q "$REPO:$tag" >/dev/null || { echo "이미지 $tag 실패" >&2; return 1; }
  printf -v "IMG_$tag" '%s' "$(docker image inspect -f '{{index .RepoDigests 0}}' "$REPO:$tag")"
}
std() { # <태그>: 판 이름 = 태그
  site_std "$W/img/$1" "$1" "$(rev_of "$1")" && mk "$1" "$(rev_of "$1")"
}
for v in a b c d e f g h i j z; do std $v || exit 1; done
asset() { echo "$V/front/assets/$1-$(h8 "$2").$3"; } # <이름> <판> <확장자>

# ---- 백엔드 상태 흉내: 화면 배포가 건드리지 않는지
printf '{"deploy_id":"back-1"}\n' >"$W/backstate/last.json"
back_before="$(cd "$W/backstate" && sha256sum last.json)"

echo "== F0 첫 배포가 확인에 실패하면 배포 전(current 없음)으로 돌림"
mkdir -p "$W/img/zx" && site_std "$W/img/zx" z "$(rev_of z)"
cp "$W/img/zx/site/assets/lazy-$(h8 z).js" "$(asset lazy z js)" && chmod 000 "$(asset lazy z js)"
expect "F0 z 첫 배포 → 16" "$(deploy "$IMG_z")" 16
expect "F0 current 없음" "$(cur)" -
expect "F0 version.json 404(배포 전과 같음)" "$(code /version.json)" 404
expect "F0 rolled_back verified" "$(field rolled_back)" verified
[ ! -f "$V/state/last-good" ] && ok "F0 last-good 없음" || bad "F0 last-good 생김"
rm -f "$(asset lazy z js)"

echo "== F1 첫 배포(current 없음)"
expect "F1 첫 배포 종료 0" "$(deploy "$IMG_a")" 0
expect "F1 HTTPS로 a의 판" "$(served_rev)" "{\"revision\":\"$(rev_of a)\"}"
expect "F1 index.html 내용" "$(edge "https://$DOMAIN/" | grep -o '<body>a</body>')" "<body>a</body>"
expect "F1 동적 chunk 200" "$(code "/assets/lazy-$(h8 a).js")" 200
id_a="$(cut -d/ -f2 <<<"$(cur)")"
[ -f "$V/state/manifests/$id_a.manifest" ] && grep -q "^commit $(rev_of a)$" "$V/state/manifests/$id_a.manifest" && ok "F1 manifest(커밋·digest·assets)" || bad "F1 manifest"
[ ! -e "$V/front/releases/$id_a/assets" ] && ok "F1 릴리스 안에는 assets 없음(공용 폴더)" || bad "F1 릴리스 assets"
expect "F1 파일 권한 0644 root" "$(stat -c '%a %u' "$V/front/releases/$id_a/index.html" "$(asset index a js)" | sort -u | tr '\n' ' ')" "644 0 "
[ -z "$(ls -A "$V/front/.incoming")" ] && [ -z "$(docker ps -aq --filter name=^homestamp-front-extract-)" ] && ok "F1 임시 폴더·꺼내기 컨테이너 없음" || bad "F1 임시 자원"
expect "F1 결과 success" "$(field result)" success

echo "== F2 같은 이미지"
expect "F2 같은 digest → noop 0" "$(deploy "$IMG_a")" 0
expect "F2 결과 noop" "$(field result)" noop
expect "F2 current 그대로" "$(cut -d/ -f2 <<<"$(cur)")" "$id_a"
chmod 000 "$V/front/releases/$id_a/version.json"
expect "F2b noop인데 실제 응답이 다름(403) → 4" "$(deploy "$IMG_a")" 4
expect "F2b failed_step noop-check" "$(field failed_step)" noop-check
chmod 644 "$V/front/releases/$id_a/version.json"

echo "== F3 a → b"
expect "F3 b 배포 0" "$(deploy "$IMG_b")" 0
expect "F3 HTTPS로 b" "$(served_rev)" "{\"revision\":\"$(rev_of b)\"}"
expect "F3 이전 a의 JS도 그대로(열린 브라우저)" "$(code "/assets/index-$(h8 a).js")" 200
expect "F3 같은 글꼴은 하나(다시 씀)" "$(find "$V/front/assets" -name 'font-SHAREDaa.woff2' | wc -l | tr -d ' ')" 1
id_b="$(cut -d/ -f2 <<<"$(cur)")"

echo "== F4 b → c 확인 실패 → b로 되돌림(16)"
# c의 동적 chunk를 nginx가 읽지 못하게 미리 둔다(같은 내용이라 재사용되고 권한만 다름 = 서버 권한 어긋남 흉내)
mkdir -p "$W/img/cx" && site_std "$W/img/cx" c "$(rev_of c)"
install -m 000 "$W/img/cx/site/assets/lazy-$(h8 c).js" "$(asset lazy c js)" 2>/dev/null || { cp "$W/img/cx/site/assets/lazy-$(h8 c).js" "$(asset lazy c js)"; chmod 000 "$(asset lazy c js)"; }
expect "F4 c 배포 → 16" "$(deploy "$IMG_c")" 16
expect "F4 current는 b" "$(cut -d/ -f2 <<<"$(cur)")" "$id_b"
expect "F4 HTTPS로 b" "$(served_rev)" "{\"revision\":\"$(rev_of b)\"}"
expect "F4 rolled_back verified" "$(field rolled_back)" verified
expect "F4 되돌린 뒤에도 c의 JS·CSS 제공(새 index를 받은 브라우저)" "$(code "/assets/index-$(h8 c).js")-$(code "/assets/index-$(h8 c).css")" 200-200
[ ! -f "$V/state/in-progress" ] && ok "F4 in-progress 정리" || bad "F4 in-progress 남음"
chmod 644 "$(asset lazy c js)"
expect "F4b 고친 뒤 c 다시 → 0" "$(deploy "$IMG_c")" 0
id_c="$(cut -d/ -f2 <<<"$(cur)")"
[ "$id_c" != "$id_b" ] && ok "F4b 새 배포 ID" || bad "F4b ID"

echo "== F5 되돌린 상태도 확인 실패(17) → 다음 배포는 먼저 확인(4)"
mkdir -p "$W/img/dx" && site_std "$W/img/dx" d "$(rev_of d)"
cp "$W/img/dx/site/assets/lazy-$(h8 d).js" "$(asset lazy d js)" && chmod 000 "$(asset lazy d js)"
# 전환 전 확인(precheck)은 c가 멀쩡해서 통과하고, 전환한 뒤에 c가 깨진다(전환 뒤 손상 = 되돌린 상태 확인 실패)
sed 's/^VERIFY_TIMEOUT=.*/VERIFY_TIMEOUT=15/' "$W/deploy-front.env" >"$W/mid.env"
runner --name $T-mid $T-runner:local bash "$SRC/infra/deploy/deploy-front.sh" --config "$W/mid.env" "$IMG_d" >"$W/out/mid" 2>&1 &
p=$!
for _ in $(seq 1 150); do grep -q '^phase=switched$' "$V/state/in-progress" 2>/dev/null && break; sleep 0.2; done
chmod 000 "$V/front/releases/$id_c/version.json"
wait $p
expect "F5 d 배포 → 17" "$?" 17
grep -q 'failed_step":"rollback"' "$V/state/last.json" && grep -q '"rolled_back":"unverified"' "$V/state/last.json" && ok "F5 복구 성공으로 기록하지 않음(unverified)" || bad "F5 기록"
expect "F5 current는 c로 돌아감" "$(cut -d/ -f2 <<<"$(cur)")" "$id_c"
[ -f "$V/state/in-progress" ] && grep -q '^phase=rollback$' "$V/state/in-progress" && ok "F5 in-progress(rollback) 남김" || bad "F5 in-progress"
before="$(snap)"
expect "F5b 다른 이미지(e) → 4(먼저 c를 확인, 실패)" "$(deploy "$IMG_e")" 4
expect "F5b 아무것도 바뀌지 않음" "$(snap)" "$before"
chmod 644 "$V/front/releases/$id_c/version.json" "$(asset lazy d js)"
expect "F5c 고친 뒤 e → 0(앞 배포를 interrupted로 마무리하고 진행)" "$(deploy "$IMG_e")" 0
grep -q "\"result\":\"interrupted\".*이전 current가 나가는 것을 확인함" "$V/state/history.jsonl" && ok "F5c 앞 배포 마무리 기록" || bad "F5c 기록"
id_e="$(cut -d/ -f2 <<<"$(cur)")"

echo "== F6 전환 뒤 확인 중 TERM"
mkdir -p "$W/img/fx" && site_std "$W/img/fx" f "$(rev_of f)"
cp "$W/img/fx/site/assets/lazy-$(h8 f).js" "$(asset lazy f js)" && chmod 000 "$(asset lazy f js)"
sed 's/^VERIFY_TIMEOUT=.*/VERIFY_TIMEOUT=30/' "$W/deploy-front.env" >"$W/slow.env"
runner --name $T-term $T-runner:local bash "$SRC/infra/deploy/deploy-front.sh" --config "$W/slow.env" "$IMG_f" >"$W/out/term" 2>&1 &
p=$!
for _ in $(seq 1 100); do grep -q '^phase=switched$' "$V/state/in-progress" 2>/dev/null && break; sleep 0.2; done
sleep 1
docker kill -s TERM $T-term >/dev/null 2>&1
wait $p
rc=$?
expect "F6 TERM → 143" "$rc" 143
expect "F6 current는 e로 돌림" "$(cut -d/ -f2 <<<"$(cur)")" "$id_e"
grep -q '^phase=rollback$' "$V/state/in-progress" 2>/dev/null && ok "F6 in-progress(rollback) 남김(다음 실행이 확인)" || bad "F6 in-progress"
chmod 644 "$(asset lazy f js)"
expect "F6b 다시 f → 0" "$(deploy "$IMG_f")" 0
expect "F6b HTTPS로 f" "$(served_rev)" "{\"revision\":\"$(rev_of f)\"}"
id_f="$(cut -d/ -f2 <<<"$(cur)")"

echo "== F7 기록 직전에 멈춘 상태(SIGKILL 흉내): 전환·확인은 끝났지만 last-good·성공 기록 전"
expect "F7 g 배포 0" "$(deploy "$IMG_g")" 0
id_g="$(cut -d/ -f2 <<<"$(cur)")"
echo "$id_f" >"$V/state/last-good"
sed -i.bak "/^$id_g success$/d" "$V/state/releases.log" 2>/dev/null || { grep -v "^$id_g success$" "$V/state/releases.log" >"$W/rl" && cat "$W/rl" >"$V/state/releases.log"; }
printf 'id=%s\nimage=%s\ncommit=%s\nprev=releases/%s\nphase=switched\n' "$id_g" "$IMG_g" "$(rev_of g)" "$id_f" >"$V/state/in-progress"
expect "F7 다른 이미지(h) → 0(g를 확인해 성공으로 마무리 뒤 진행)" "$(deploy "$IMG_h")" 0
grep -q "\"deploy_id\":\"$id_g\".*\"result\":\"success\".*마무리" "$V/state/history.jsonl" && ok "F7 g 마무리 기록" || bad "F7 g 기록"
expect "F7 HTTPS로 h" "$(served_rev)" "{\"revision\":\"$(rev_of h)\"}"
id_h="$(cut -d/ -f2 <<<"$(cur)")"
# 전환 전에 멈춘 상태: current는 그대로
printf 'id=%s\nimage=%s\ncommit=%s\nprev=releases/%s\nphase=prepare\n' "20261127T000000Z-1" "$IMG_a" "$(rev_of a)" "$id_h" >"$V/state/in-progress"
expect "F7b 전환 전 멈춤 뒤 같은 이미지(h) → noop 0" "$(deploy "$IMG_h")" 0
grep -q '"deploy_id":"20261127T000000Z-1".*"result":"interrupted"' "$V/state/history.jsonl" && ok "F7b 앞 배포 interrupted 기록" || bad "F7b 기록"
# 손상된 in-progress
printf 'garbage\n' >"$V/state/in-progress"
expect "F7c 손상된 in-progress → 4" "$(deploy "$IMG_a")" 4
rm -f "$V/state/in-progress"

echo "== F8 이전 판 다시 배포(a)"
expect "F8 a 다시 → 0" "$(deploy "$IMG_a")" 0
expect "F8 HTTPS로 a" "$(served_rev)" "{\"revision\":\"$(rev_of a)\"}"
id_a2="$(cut -d/ -f2 <<<"$(cur)")"
[ "$id_a2" != "$id_a" ] && ok "F8 새 배포 ID(예전 폴더를 다시 쓰지 않음)" || bad "F8 ID"

echo "== F9 보존·정리"
nrel="$(find "$V/front/releases" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"
[ "$nrel" -le 4 ] && ok "F9 릴리스 폴더 ${nrel}개(최근 정상 3 + 직전)" || bad "F9 릴리스 ${nrel}개"
[ ! -e "$V/front/releases/$id_b" ] && [ ! -e "$(asset index b js)" ] && ok "F9 범위 밖 b의 폴더·고유 assets 지움" || bad "F9 b 정리"
[ -e "$(asset index a js)" ] && [ -e "$V/front/assets/font-SHAREDaa.woff2" ] && ok "F9 현재 a·공유 글꼴 보존" || bad "F9 보존"
[ -e "$(asset index h js)" ] && [ -e "$V/front/releases/$id_h" ] && ok "F9 직전 h 보존(되돌리기 대상)" || bad "F9 h"
# 보존할 manifest가 손상되면 assets는 지우지 않는다
cp "$V/state/manifests/$id_h.manifest" "$W/mf.bak" && head -n 3 "$W/mf.bak" >"$V/state/manifests/$id_h.manifest"
n_before="$(find "$V/front/assets" -type f | wc -l | tr -d ' ')"
expect "F9b 다음 배포(b) → 0" "$(deploy "$IMG_b")" 0
expect "F9b gc skipped" "$(field gc)" skipped
[ "$(find "$V/front/assets" -type f | wc -l | tr -d ' ')" -ge "$n_before" ] && ok "F9b manifest 손상 → assets를 지우지 않음" || bad "F9b assets 지워짐"
cp "$W/mf.bak" "$V/state/manifests/$id_h.manifest"
# 마지막 정상 릴리스의 manifest가 없으면 시작하지 않는다
id_now="$(cut -d/ -f2 <<<"$(cur)")"
mv "$V/state/manifests/$id_now.manifest" "$W/mf2.bak"
expect "F9c 마지막 정상 manifest 없음 → 4" "$(deploy "$IMG_c")" 4
mv "$W/mf2.bak" "$V/state/manifests/$id_now.manifest"

echo "== F10 충돌·계약 위반(아무것도 바꾸지 않음)"
mkdir -p "$W/img/clash" && site_std "$W/img/clash" clash "$(rev_of clash)" && printf 'different\n' >"$W/img/clash/site/assets/font-SHAREDaa.woff2" && mk clash "$(rev_of clash)"
before="$(snap)"
expect "F10 같은 이름 다른 내용 → 23" "$(deploy "$IMG_clash")" 23
expect "F10 그대로" "$(snap)" "$before"
mkdir -p "$W/img/samec" && site_std "$W/img/samec" samec "$(rev_of a)" && mk samec "$(rev_of a)"
expect "F10 같은 커밋 다른 digest → 23" "$(deploy "$IMG_samec")" 23
bad_img() { # <태그> <설명> <기대> <준비 명령>
  mkdir -p "$W/img/$1" && site_std "$W/img/$1" "$1" "$(rev_of "$1")" && (cd "$W/img/$1/site" && eval "$4") && mk "$1" "$(rev_of "$1")" "${5:-linux/amd64}" "${6:-}" || { bad "$2 이미지 만들기"; return; }
  before="$(snap)"
  expect "F10 $2 → $3" "$(deploy "$(eval echo "\$IMG_$1")")" "$3"
  expect "F10 $2: 그대로·임시 자원 없음" "$(snap)$(ls -A "$V/front/.incoming")$(docker ps -aq --filter name=^homestamp-front-extract-)" "$before"
}
bad_img blink "링크" 22 'ln -s index.html link.html'
bad_img bhard "하드링크" 22 'ln assets/index-*.css same.css'
bad_img bdot "점 파일" 22 'echo x > .hidden.txt'
bad_img bname "이름에 공백" 22 'echo x > "a b.txt"'
bad_img bext "허용 안 된 종류" 22 'echo x > run.sh'
bad_img bmap "소스맵" 22 'echo x > assets/index-abcdefgh.js.map'
bad_img bsub "assets 하위 폴더" 22 'mkdir assets/x && echo x > assets/x/y-abcdefgh.js'
bad_img bnohash "해시 없는 assets 이름" 22 'echo x > assets/plain.js'
bad_img bver "version.json 다름" 22 'echo "{\"revision\":\"0000000000000000000000000000000000000000\"}" > version.json'
bad_img bnoidx "index.html 없음" 22 'rm index.html'
bad_img bref "index.html이 /assets/../ 로 밖을 부름" 22 'echo "<script src=\"/assets/../x.js\"></script>" >> index.html && echo x > x.js'
mkdir -p "$W/img/bout/root/etc" && site_std "$W/img/bout/root" bout "$(rev_of bout)" && echo x >"$W/img/bout/root/etc/extra.html" &&
  printf 'FROM scratch\nCOPY root/ /\nCMD ["/not-runnable"]\n' >"$W/img/bout/Dockerfile" &&
  docker build -q --platform linux/amd64 --label "org.opencontainers.image.revision=$(rev_of bout)" -t "$REPO:bout" "$W/img/bout" >/dev/null &&
  docker push -q "$REPO:bout" >/dev/null && IMG_bout="$(docker image inspect -f '{{index .RepoDigests 0}}' "$REPO:bout")"
before="$(snap)"
expect "F10 /site 밖 파일(레이어 하나) → 22" "$(deploy "$IMG_bout")" 22
expect "F10 /site 밖 파일: 그대로" "$(snap)" "$before"
bad_img barm "arm64" 10 'true' linux/arm64
bad_img blayer "레이어 둘" 10 'true' linux/amd64 'COPY site/version.json /site/again.json'
dd if=/dev/zero of="$W/img/big.bin" bs=1024 count=2048 2>/dev/null
mkdir -p "$W/img/bbig" && site_std "$W/img/bbig" bbig "$(rev_of bbig)" && cp "$W/img/big.bin" "$W/img/bbig/site/big.txt" && mk bbig "$(rev_of bbig)"
sed -i.bak 's/^VERIFY_TIMEOUT=3$/VERIFY_TIMEOUT=3\nMAX_FILE_BYTES=1048576/' "$W/deploy-front.env" 2>/dev/null
grep -q '^MAX_FILE_BYTES=1048576' "$W/deploy-front.env" || printf 'MAX_FILE_BYTES=1048576\n' >>"$W/deploy-front.env"
before="$(snap)"
expect "F10 파일 하나가 너무 큼(1MiB 한도) → 24" "$(deploy "$IMG_bbig")" 24
expect "F10 크기 초과: 그대로" "$(snap)" "$before"
sed -i.bak '/^MAX_FILE_BYTES=/d' "$W/deploy-front.env"
expect "F10 태그(digest 아님) → 2" "$(deploy "$REPO:a")" 2
expect "F10 다른 저장소 → 2" "$(deploy "127.0.0.1:15101/other/app@sha256:$(printf 'a%.0s' $(seq 1 64))")" 2

echo "== F11 잠금·백엔드와 분리"
runner -d --name $T-lock $T-runner:local flock "$V/lock/homestamp-deploy.lock" sleep 20 >/dev/null
sleep 1
expect "F11 백엔드가 잠금 중 → 3" "$(deploy "$IMG_c")" 3
docker rm -f $T-lock >/dev/null
sed 's|^STATE_DIR=.*|STATE_DIR=/var/lib/homestamp/deploy|' "$W/deploy-front.env" >"$W/same.env"
expect "F11 백엔드 상태 폴더를 쓰려 하면 → 2" "$(deploy --config "$W/same.env" "$IMG_c")" 2
expect "F11 백엔드 상태 파일 그대로" "$(cd "$W/backstate" && sha256sum last.json)" "$back_before"
grep -nE 'docker (run|start|exec)|--migrate|--apply-grants|switch-upstream|nginx -s|MIGRATION' "$SRC/infra/deploy/deploy-front.sh" >/dev/null && bad "F11 스크립트에 앱 실행·Migration·nginx 조작" || ok "F11 스크립트에 앱 실행·Migration·nginx reload 없음"
expect "F11 남은 컨테이너는 nginx·레지스트리뿐" "$(docker ps -a --format '{{.Names}}' | grep -E "^($T-|$EDGE\$|homestamp-front-extract-)" | sort | tr '\n' ' ')" "$EDGE $T-registry "

echo "== F14 전환·확인 뒤 기록 쓰기 실패(신호 아님): 이전으로 돌리고 in-progress를 남김"
id_before="$(cut -d/ -f2 <<<"$(cur)")"
mkdir "$V/state/last-good.tmp.1" # put()의 임시 파일 자리(runner의 bash는 PID 1)를 막아 last-good 쓰기를 실패시킨다
expect "F14 i 배포 → 1(예상 못 한 실패)" "$(deploy "$IMG_i")" 1
expect "F14 current는 직전으로 돌림" "$(cut -d/ -f2 <<<"$(cur)")" "$id_before"
grep -q '^phase=rollback$' "$V/state/in-progress" 2>/dev/null && ok "F14 in-progress(rollback) 남김" || bad "F14 in-progress"
rmdir "$V/state/last-good.tmp.1"
expect "F14b 다음 배포(j) → 0(앞 배포를 확인해 마무리)" "$(deploy "$IMG_j")" 0
grep -q "\"result\":\"interrupted\".*이전 current가 나가는 것을 확인함" "$V/state/history.jsonl" && ok "F14b 앞 배포 마무리 기록" || bad "F14b 기록"
expect "F14b HTTPS로 j" "$(served_rev)" "{\"revision\":\"$(rev_of j)\"}"

echo "== F15 root가 쓰는 자리의 소유자·링크, 남은 꺼내기 컨테이너"
mkdir -p "$V/userroot" && chown 1000 "$V/userroot"
sed "s|^FRONT_ROOT=.*|FRONT_ROOT=$V/userroot/front|" "$W/deploy-front.env" >"$W/user.env"
expect "F15 root 아닌 폴더 아래 FRONT_ROOT → 2" "$(deploy --config "$W/user.env" "$IMG_c")" 2
[ ! -e "$V/userroot/front" ] && ok "F15 그 아래에 아무것도 만들지 않음" || bad "F15 만들어짐"
ln -s "$V/front" "$V/linkroot"
sed "s|^FRONT_ROOT=.*|FRONT_ROOT=$V/linkroot|" "$W/deploy-front.env" >"$W/link.env"
expect "F15 링크인 FRONT_ROOT → 2" "$(deploy --config "$W/link.env" "$IMG_c")" 2
docker create --name homestamp-front-extract-left "$IMG_a" >/dev/null
expect "F15 남은 꺼내기 컨테이너가 있어도 배포(a) → 0" "$(deploy "$IMG_a")" 0
[ -z "$(docker ps -aq --filter name=^homestamp-front-extract-)" ] && ok "F15 남은 꺼내기 컨테이너 지움" || bad "F15 컨테이너 남음"

echo "== F16 지금 나가는 판이 이미 깨져 있으면 전환하지 않음(4)"
id_now="$(cut -d/ -f2 <<<"$(cur)")"
chmod 000 "$(asset index a js)"
before="$(snap)"
expect "F16 c 배포 → 4" "$(deploy "$IMG_c")" 4
expect "F16 failed_step precheck" "$(field failed_step)" precheck
expect "F16 그대로(새 릴리스·assets 없음)" "$(snap)" "$before"
expect "F16 current 그대로" "$(cut -d/ -f2 <<<"$(cur)")" "$id_now"
[ ! -f "$V/state/in-progress" ] && ok "F16 in-progress 없음" || bad "F16 in-progress"
chmod 644 "$(asset index a js)"

echo "== F12 계약 시험: check-front.sh(CI)와 deploy-front.sh --check-dir(서버)가 같은 판단"
for tag in a blink bhard bdot bname bext bmap bsub bnohash bver bnoidx bref bout bbig; do
  r="$(rev_of "$tag")"
  [ "$tag" = a ] && r="$(rev_of a)"
  c1="$(runner $T-runner:local bash "$SRC/.github/scripts/image/check-front.sh" "$(eval echo "\$IMG_$tag")" "$r" >/dev/null 2>&1; echo $?)"
  rm -rf "$W/cd" && mkdir -p "$W/cd" && cid="$(docker create --platform linux/amd64 "$(eval echo "\$IMG_$tag")")" && docker cp -q "$cid:/site" "$W/cd/" 2>/dev/null; docker rm -f "$cid" >/dev/null
  c2="$(runner $T-runner:local bash "$SRC/infra/deploy/deploy-front.sh" --check-dir "$W/cd/site" "$r" >/dev/null 2>&1; echo $?)"
  [ "$tag" = bout ] && c2=22 # /site 밖 파일은 서버가 docker export로 본다(위 F10에서 22 확인)
  expect "F12 $tag: 둘 다 $([ "$c1" = 0 ] && echo 통과 || echo 거절)" "$([ "$c1" = 0 ] && echo pass || echo reject)" "$([ "$c2" = 0 ] && echo pass || echo reject)"
done

if [ "$REAL" = real ]; then
  echo "== F13 실제 front/Dockerfile 빌드"
  rr="$(git -c safe.directory="$SRC" -C "$SRC" rev-parse HEAD 2>/dev/null || rev_of real)"
  if docker build -q --platform linux/amd64 --build-arg "REVISION=$rr" --label "org.opencontainers.image.revision=$rr" -t "$REPO:real" -f "$SRC/front/Dockerfile" "$SRC/front" >/dev/null &&
    docker push -q "$REPO:real" >/dev/null; then
    IMG_real="$(docker image inspect -f '{{index .RepoDigests 0}}' "$REPO:real")"
    expect "F13 실제 이미지 배포 → 0" "$(deploy "$IMG_real")" 0
    expect "F13 HTTPS로 실제 판" "$(served_rev)" "{\"revision\":\"$rr\"}"
    js="$(edge "https://$DOMAIN/" | grep -oE '/assets/index-[A-Za-z0-9_-]{8}\.js' | head -n 1)"
    expect "F13 실제 JS 200" "$(code "$js")" 200
  else
    bad "F13 실제 이미지 빌드"
  fi
fi

echo "passed=$passed failed=$failed"
[ "$failed" = 0 ]
