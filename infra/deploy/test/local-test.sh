#!/usr/bin/env bash
# deploy.sh 로컬 격리 시험. AWS·EC2·운영 nginx 없음. 빈 작업 폴더를 인자로 준다.
#   mkdir -p /tmp/hs-deploy-work && infra/deploy/test/local-test.sh /tmp/hs-deploy-work
# - 이름이 hs-deploy-test- 로 시작하는 컨테이너·이미지만 만들고 끝나면 지운다(포트 15000·18081·18082·18090)
# - deploy.sh는 Linux 컨테이너(bash·util-linux flock·curl·docker CLI)에서 호스트 Docker 소켓으로 돈다
# - 가짜 앱 이미지는 시험용 로컬 레지스트리에 올려 운영처럼 digest로 받는다
# - nginx 전환은 가짜 전환기(test 안의 작은 nginx + upstream 파일)로 흉내 낸다. 실제 switch-upstream.sh는 #204
# macOS 기본 bash 3.2에서도 돌도록 쓴다.
# shellcheck disable=SC2154  # IMG_<이름>은 build()가 printf -v로 만든다
set -uo pipefail

W="${1:?빈 작업 폴더를 주세요}"
[ -d "$W" ] && [ -z "$(ls -A "$W")" ] || { echo "빈 폴더가 아닙니다: $W" >&2; exit 2; }
W="$(cd "$W" && pwd -P)"
DIR="$(cd "$(dirname "$0")/.." && pwd -P)"
T=hs-deploy-test
NGINX_IMAGE=nginx:1.28-alpine@sha256:a8b39bd9cf0f83869a2162827a0caf6137ddf759d50a171451b335cecc87d236
REGISTRY_IMAGE=registry:3@sha256:82f6ff4a7f982960c8aa51617fea3aabc485df6e55cce80d22deb1de36f1bc3c
REPO=127.0.0.1:15000/$T/app
CANARY="CANARY-$(od -An -tx1 -N8 /dev/urandom | tr -d ' \n')"
passed=0
failed=0

ok() { passed=$((passed + 1)); echo "ok   $1"; }
bad() { failed=$((failed + 1)); echo "FAIL $1"; }
expect() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (기대 $3, 실제 $2)"; fi; }

cleanup() {
  ids="$(docker ps -aq --filter "name=^$T-")"
  [ -z "$ids" ] || docker rm -f $ids >/dev/null 2>&1
  imgs="$(docker image ls -q --filter "reference=$T-*" ; docker image ls -q --filter "reference=$REPO")"
  [ -z "$imgs" ] || docker image rm -f $imgs >/dev/null 2>&1
}
trap cleanup EXIT
cleanup

# ---- 준비
mkdir -p "$W/secrets" "$W/upstream" "$W/flags" "$W/bin" "$W/state" "$W/lock" "$W/out"
for f in spring.datasource.url spring.datasource.username spring.datasource.password app.migration.owner-role; do
  printf '%s-%s' "$CANARY" "$f" >"$W/secrets/$f"
  chmod 0644 "$W/secrets/$f"
done

docker run -d --name $T-registry -p 127.0.0.1:15000:5000 "$REGISTRY_IMAGE" >/dev/null
docker build -q -t $T-runner:local "$DIR/test/runner" >/dev/null || { echo "runner 이미지 빌드 실패" >&2; exit 1; }

build() { # 이름 [build-arg...] → 변수 IMG_<이름>에 digest 참조
  local name=$1
  shift
  local args=()
  for a in "$@"; do args+=(--build-arg "$a"); done
  docker build -q --build-arg "BASE_IMAGE=$NGINX_IMAGE" "${args[@]}" -t "$REPO:$name" "$DIR/test/mock-app" >/dev/null &&
    docker push -q "$REPO:$name" >/dev/null || { echo "mock $name 빌드·푸시 실패" >&2; exit 1; }
  printf -v "IMG_$name" '%s' "$(docker image inspect -f '{{index .RepoDigests 0}}' "$REPO:$name")"
}
for _ in 1 2 3 4 5 6 7 8 9 10; do curl -fs http://127.0.0.1:15000/v2/ >/dev/null && break; sleep 1; done
build v1 VERSION=v1
build v2 VERSION=v2
build v3 VERSION=v3
build migfail VERSION=migfail MIGRATE_EXIT=1
build grantfail VERSION=grantfail GRANTS_EXIT=1
build crash VERSION=crash CRASH=1
build unhealthy VERSION=unhealthy HEALTH_CODE=503 HEALTH_STATUS=DOWN
build api503 VERSION=api503 API_CODE=503
build slow VERSION=slow MIGRATE_SLEEP=6
build slow2 VERSION=slow2 MIGRATE_SLEEP=10
build api404 VERSION=api404 API_CODE=404
build hang VERSION=hang MIGRATE_SLEEP=60
build oldimg VERSION=oldimg GRANTS_WEB=1

cat >"$W/edge.conf" <<'EOF'
pid /tmp/edge.pid;
events {}
http {
  access_log off;
  include /edge/upstream/upstream.conf;
  # 실제 nginx(infra/nginx)와 같은 확인 헤더 계약
  map $remote_addr $homestamp_upstream_probe { 127.0.0.1 $upstream_addr; default ""; }
  server {
    listen 127.0.0.1:18090;
    location /api/ {
      proxy_pass http://homestamp_app; proxy_next_upstream off;
      proxy_hide_header X-Homestamp-Upstream;
      add_header X-Homestamp-Upstream $homestamp_upstream_probe always;
    }
  }
}
EOF
# 가짜 전환기: #204 switch-upstream.sh와 같은 약속(주소 하나, 실패하면 이전 파일로 되돌리고 0이 아닌 값)
cat >"$W/bin/switch" <<EOF
#!/usr/bin/env bash
set -euo pipefail
addr=\$1
[[ \$addr =~ ^127\.0\.0\.1:[0-9]+\$ ]] || exit 2
if [[ -e $W/flags/switch-fail-to && "\$(cat $W/flags/switch-fail-to)" == "\$addr" ]]; then echo "mock: 이 주소로 전환 실패" >&2; exit 1; fi
conf=$W/upstream/upstream.conf
had=0; if [[ -f \$conf ]]; then cp "\$conf" "\$conf.prev"; had=1; fi
restore() { if ((had)); then mv "\$conf.prev" "\$conf"; else rm -f "\$conf"; fi; }
printf 'upstream homestamp_app {\n  server %s;\n  keepalive 8;\n}\n' "\$addr" >"\$conf.tmp"
mv "\$conf.tmp" "\$conf"
if [[ -e $W/flags/switch-test-fail ]]; then restore; echo "mock: nginx -t 실패" >&2; exit 1; fi
if ! docker container inspect $T-edge >/dev/null 2>&1; then
  docker run -d --name $T-edge --network host -v $W/edge.conf:/etc/nginx/nginx.conf:ro \\
    -v $W/upstream:/edge/upstream:ro $NGINX_IMAGE >/dev/null
else
  docker exec $T-edge nginx -t -q || { restore; exit 1; }
  if [[ -e $W/flags/switch-reload-fail ]]; then restore; echo "mock: reload 실패" >&2; exit 1; fi
  # reload가 아직 반영되지 않은 nginx 흉내: 파일만 바꾸고 0(이전 앱이 계속 답함)
  if [[ -e $W/flags/switch-no-reload ]]; then exit 0; fi
  docker exec $T-edge nginx -s reload
fi
sleep 1
EOF
chmod 0755 "$W/bin/switch"

cat >"$W/deploy.env" <<EOF
IMAGE_REPOSITORY=$REPO
MIGRATION_UID=10002:10002
POST_SWITCH_URL=http://127.0.0.1:18090/api/
BLUE_PORT=18081
GREEN_PORT=18082
NAME_PREFIX=$T-app
MIGRATION_SECRETS_DIR=$W/secrets
UPSTREAM_FILE=$W/upstream/upstream.conf
SWITCH_CMD=$W/bin/switch
STATE_DIR=$W/state
LOCK_FILE=$W/lock/deploy.lock
HEALTH_TIMEOUT=12
STOP_TIMEOUT=5
EOF

echo 0 >"$W/n"
next_out() { local i; i=$(($(cat "$W/n") + 1)); echo "$i" >"$W/n"; echo "$W/out/$i"; }
lastout() { echo "$W/out/$(cat "$W/n")"; }
runner() { docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v "$W:$W" -v "$DIR:$DIR:ro" $T-runner:local "$@"; }
deploy() { # 출력은 $W/out/N, 종료 코드를 돌려준다
  local out
  out="$(next_out)"
  runner bash "$DIR/deploy.sh" --config "$W/deploy.env" "$@" >"$out" 2>&1
  echo $?
}
active() { grep -oE '127\.0\.0\.1:[0-9]+' "$W/upstream/upstream.conf" 2>/dev/null || echo none; }
# nginx(가짜 입구)는 Docker host 네트워크에 있어 runner 안에서 부른다
edge_version() { runner curl -s --max-time 3 -D - -o /dev/null http://127.0.0.1:18090/api/ | tr -d '\r' | awk -F': ' 'tolower($1)=="x-mock-version"{print $2}'; }
state() { docker container inspect -f '{{.State.Status}}' "$T-app-$1" 2>/dev/null || echo none; }
cimage() { docker container inspect -f '{{.Config.Image}}' "$T-app-$1" 2>/dev/null || echo none; }
last() { tail -n 1 "$W/state/history.jsonl"; }
has() { case "$1" in *"$2"*) return 0 ;; esac; return 1; }
check_last() { if has "$(last)" "$2"; then ok "$1"; else bad "$1 ($(last))"; fi; }

echo "== 입력 검사"
expect "태그만 준 이미지 거절" "$(deploy "$REPO:v1")" 2
expect "다른 저장소 이미지 거절" "$(deploy "ghcr.io/other/app@sha256:$(printf '%064d' 0)")" 2
cp "$W/deploy.env" "$W/deploy.env.bak"
grep -v '^MIGRATION_UID=' "$W/deploy.env.bak" >"$W/deploy.env"
expect "MIGRATION_UID 없으면 거절" "$(deploy "$IMG_v1")" 2
for bad in 'POST_SWITCH_URL=https://homestamp.cloud/api/' \
  'POST_SWITCH_URL=https://homestamp.cloud/api/
POST_SWITCH_RESOLVE=homestamp.cloud:443:10.0.0.5' \
  'POST_SWITCH_URL=https://homestamp.cloud/api/
POST_SWITCH_RESOLVE=other.cloud:443:127.0.0.1' \
  'POST_SWITCH_URL=https://homestamp.cloud/api/
POST_SWITCH_RESOLVE=homestamp.cloud:8443:127.0.0.1'; do
  { grep -v '^POST_SWITCH_URL=' "$W/deploy.env.bak"; printf '%s\n' "$bad"; } >"$W/deploy.env"
  expect "확인 요청이 127.0.0.1로 가지 않는 설정 거절($(tr '\n' ' ' <<<"$bad"))" "$(deploy "$IMG_v1")" 2
done
mv "$W/deploy.env.bak" "$W/deploy.env"
expect "입력 거절은 기록·잠금 전에 끝남" "$([ -e "$W/state/history.jsonl" ] && echo yes || echo no)" no

echo "== 1. 첫 배포(활성 슬롯 없음)"
expect "종료 0" "$(deploy "$IMG_v1")" 0
expect "upstream=blue" "$(active)" 127.0.0.1:18081
expect "nginx 경유 응답=v1" "$(edge_version)" v1
expect "blue 실행 중" "$(state blue)" running
check_last "기록: success, active blue" '"result":"success","exit_code":0,"failed_step":null,"prev_slot":"none","prev_running":false,"active_slot":"blue","rolled_back":false,"db_changes_rolled_back":false'
expect "앱 포트는 127.0.0.1에만" "$(docker port $T-app-blue 8080/tcp)" 127.0.0.1:18081
expect "메모리 한도 800m" "$(docker container inspect -f '{{.HostConfig.Memory}}' $T-app-blue)" 838860800
expect "재시작 정책" "$(docker container inspect -f '{{.HostConfig.RestartPolicy.Name}}' $T-app-blue)" unless-stopped
expect "읽기 전용 루트·권한 없음" "$(docker container inspect -f '{{.HostConfig.ReadonlyRootfs}} {{.HostConfig.CapDrop}} {{.HostConfig.SecurityOpt}}' $T-app-blue)" "true [ALL] [no-new-privileges]"
expect "앱 환경변수는 프로필·시간대뿐(비밀값 없음)" "$(docker container inspect -f '{{range .Config.Env}}{{println .}}{{end}}' $T-app-blue | grep -E '^(SPRING_|TZ=)' | sort | tr '\n' ' ')" "SPRING_PROFILES_ACTIVE=prod TZ=UTC "
expect "앱에는 비밀 폴더를 묶지 않음" "$(docker container inspect -f '{{len .Mounts}}' $T-app-blue)" 0
expect "기록 파일 권한 0640·폴더 0750" "$(stat -f '%Lp' "$W/state/history.jsonl") $(stat -f '%Lp' "$W/state")" "640 750"

echo "== 2. Blue → Green"
blue_id="$(docker container inspect -f '{{.Id}}' $T-app-blue)"
expect "종료 0" "$(deploy "$IMG_v2")" 0
expect "upstream=green" "$(active)" 127.0.0.1:18082
expect "nginx 경유 응답=v2" "$(edge_version)" v2
expect "이전 blue는 멈추고 남김" "$(state blue)" exited
expect "남긴 blue는 같은 컨테이너" "$(docker container inspect -f '{{.Id}}' $T-app-blue)" "$blue_id"
check_last "기록: prev blue → active green" '"prev_slot":"blue","prev_running":true,"active_slot":"green","rolled_back":false'

echo "== 3. Green → Blue"
expect "종료 0" "$(deploy "$IMG_v3")" 0
expect "upstream=blue" "$(active)" 127.0.0.1:18081
expect "nginx 경유 응답=v3" "$(edge_version)" v3
expect "blue는 새 컨테이너(이전 것 지움)" "$([ "$(docker container inspect -f '{{.Id}}' $T-app-blue)" != "$blue_id" ] && echo new)" new
expect "green 멈춤" "$(state green)" exited

echo "== 14. 같은 이미지 다시 배포"
blue_id="$(docker container inspect -f '{{.Id}}' $T-app-blue)"
expect "종료 0" "$(deploy "$IMG_v3")" 0
check_last "기록: noop" '"result":"noop","exit_code":0'
expect "blue 그대로" "$(docker container inspect -f '{{.Id}}' $T-app-blue)" "$blue_id"
expect "green 그대로 멈춤" "$(state green)" exited
if grep -q -- '--migrate' "$(lastout)"; then bad "noop은 Migration을 돌리지 않음"; else ok "noop은 Migration을 돌리지 않음"; fi

wait_up() { for _ in $(seq 1 30); do runner curl -fsS --max-time 2 "http://127.0.0.1:$1/actuator/health" >/dev/null 2>&1 && return 0; sleep 1; done; return 1; }
echo "== 14b. 같은 이미지, 전환 뒤 확인 전에 멈춘 배포(두 앱 실행 중) → 확인 통과 뒤 noop, 남은 이전 앱 멈춤"
docker start $T-app-green >/dev/null && wait_up 18082
runner bash "$W/bin/switch" 127.0.0.1:18082 >/dev/null 2>&1 # 전환까지 하고 배포가 죽은 상태(green=v2)
green_id="$(docker container inspect -f '{{.Id}}' $T-app-green)"
expect "종료 0" "$(deploy "$IMG_v2")" 0
check_last "기록: noop" '"result":"noop","exit_code":0'
expect "green 그대로(같은 컨테이너)" "$(docker container inspect -f '{{.Id}}' $T-app-green)" "$green_id"
expect "남아 있던 blue는 멈춤(지우지 않음)" "$(state blue)" exited
if grep -q '멈춘 배포가 남긴 이전 슬롯' "$(lastout)"; then ok "출력: 중단된 배포의 마무리를 밝힘"; else bad "출력: 마무리 안내"; fi

echo "== 14c. 같은 이미지지만 활성 슬롯이 확인을 통과하지 못함 → noop 아님(4), 아무것도 바꾸지 않음"
expect "404 이미지 → 되돌림 16(blue에 멈춰 남음)" "$(deploy "$IMG_api404")" 16
docker start $T-app-blue >/dev/null && wait_up 18081
runner bash "$W/bin/switch" 127.0.0.1:18081 >/dev/null 2>&1 # 확인을 통과한 적 없는 blue로 전환된 채 멈춘 상태
blue_id="$(docker container inspect -f '{{.Id}}' $T-app-blue)"
expect "종료 4(noop 아님)" "$(deploy "$IMG_api404")" 4
check_last "기록: noop-check에서 4" '"exit_code":4,"failed_step":"noop-check"'
if grep -q 'noop으로 보지 않고' "$(lastout)"; then ok "출력: noop으로 보지 않음"; else bad "출력: noop 거절 안내"; fi
expect "upstream 그대로 blue" "$(active)" 127.0.0.1:18081
expect "두 앱 그대로 실행, blue 같은 컨테이너" "$(state blue) $(state green) $(docker container inspect -f '{{.Id}}' $T-app-blue)" "running running $blue_id"
if grep -q -- '--migrate' "$(lastout)"; then bad "noop 확인 실패는 Migration을 돌리지 않음"; else ok "noop 확인 실패는 Migration을 돌리지 않음"; fi
# 런북처럼 손으로 확인된 green으로 돌리고, 다음 시험의 기준(blue=v3 활성, green 멈춤)으로 다시 배포
runner bash "$W/bin/switch" 127.0.0.1:18082 >/dev/null 2>&1
docker stop -t 5 $T-app-blue >/dev/null
expect "다시 v3 배포 → 0" "$(deploy "$IMG_v3")" 0
expect "기준 복구: upstream=blue" "$(active)" 127.0.0.1:18081
expect "기준 복구: green 멈춤" "$(state green)" exited

after_fail() { # 실패 뒤 공통: 활성 그대로 blue(v3), nginx 응답 v3
  expect "$1: upstream 그대로 blue" "$(active)" 127.0.0.1:18081
  expect "$1: nginx 경유 응답 그대로 v3" "$(edge_version)" v3
  expect "$1: blue 계속 실행" "$(state blue)" running
}

echo "== 4. Migration 실패"
green_id="$(docker container inspect -f '{{.Id}}' $T-app-green)"
expect "종료 11" "$(deploy "$IMG_migfail")" 11
after_fail migrate
expect "green 슬롯 손대지 않음" "$(docker container inspect -f '{{.Id}}' $T-app-green)" "$green_id"
check_last "기록: failed_step migrate, DB 롤백 없음 표시" '"result":"failed","exit_code":11,"failed_step":"migrate","prev_slot":"blue","prev_running":true,"active_slot":"blue","rolled_back":false,"db_changes_rolled_back":false'

echo "== 5. GRANT 실패"
expect "종료 12" "$(deploy "$IMG_grantfail")" 12
after_fail grants
expect "green 슬롯 손대지 않음(새 앱 시작 안 함)" "$(docker container inspect -f '{{.Id}}' $T-app-green)" "$green_id"
if grep -q 'mock migrate exit=0' "$(lastout)"; then ok "GRANT 전에 Migration 성공"; else bad "GRANT 전에 Migration 성공"; fi
check_last "기록: failed_step grants" '"exit_code":12,"failed_step":"grants"'

echo "== 6. 새 컨테이너 시작 실패"
expect "종료 13" "$(deploy "$IMG_crash")" 13
after_fail start
expect "실패한 green은 멈춰 남김" "$(state green)" exited
expect "남긴 green이 실패 이미지" "$(cimage green)" "$IMG_crash"
check_last "기록: failed_step start" '"exit_code":13,"failed_step":"start"'

echo "== 7. 헬스 체크 실패(DOWN 503)"
expect "종료 14" "$(deploy "$IMG_unhealthy")" 14
after_fail health
expect "실패한 green은 멈춤" "$(state green)" exited
check_last "기록: failed_step health" '"exit_code":14,"failed_step":"health"'

echo "== 8. nginx 설정 검사 실패"
touch "$W/flags/switch-test-fail"
expect "종료 15" "$(deploy "$IMG_v1")" 15
rm -f "$W/flags/switch-test-fail"
after_fail nginx-t
expect "새 green은 멈춤" "$(state green)" exited
check_last "기록: failed_step switch" '"exit_code":15,"failed_step":"switch"'

echo "== 9. nginx reload 실패"
touch "$W/flags/switch-reload-fail"
expect "종료 15" "$(deploy "$IMG_v1")" 15
rm -f "$W/flags/switch-reload-fail"
after_fail reload

echo "== 10. 전환 직후 요청 확인 실패(새 앱이 503) → 자동 되돌림"
expect "종료 16" "$(deploy "$IMG_api503")" 16
after_fail verify
expect "새 green은 멈춤" "$(state green)" exited
check_last "기록: verify, rolled_back true, DB 롤백 없음" '"exit_code":16,"failed_step":"verify","prev_slot":"blue","prev_running":true,"active_slot":"blue","rolled_back":true,"db_changes_rolled_back":false'
if grep -q '새 앱이 받은 요청의 DB 변경은 그대로' "$(lastout)"; then ok "출력에 DB 변경 안내"; else bad "출력에 DB 변경 안내"; fi

echo "== 10b. 전환 뒤 nginx·앱이 401이 아닌 답(404)도 실패로 보고 되돌림"
expect "종료 16" "$(deploy "$IMG_api404")" 16
after_fail verify-404
check_last "기록: verify, rolled_back true" '"exit_code":16,"failed_step":"verify","prev_slot":"blue","prev_running":true,"active_slot":"blue","rolled_back":true'

echo "== 10f. reload가 반영되기 전(이전 앱의 401)은 새 슬롯 성공이 아님 → 되돌림"
touch "$W/flags/switch-no-reload"
expect "종료 16(이전 blue의 401을 green 성공으로 보지 않음)" "$(deploy "$IMG_v1")" 16
rm -f "$W/flags/switch-no-reload"
if grep -q '답한 앱 127.0.0.1:18081' "$(lastout)"; then ok "출력: 실제로 답한 앱(blue)을 밝힘"; else bad "출력: 답한 앱 $(grep '전환 뒤 확인 실패' "$(lastout)" | head -n 1)"; fi
check_last "기록: verify, rolled_back true(되돌린 blue도 같은 식별로 확인)" '"exit_code":16,"failed_step":"verify","prev_slot":"blue","prev_running":true,"active_slot":"blue","rolled_back":true'
expect "upstream=blue" "$(active)" 127.0.0.1:18081

echo "== 10c. 되돌리기 실패 → 두 앱 남김(17)"
echo 127.0.0.1:18081 >"$W/flags/switch-fail-to"
expect "종료 17" "$(deploy "$IMG_api503")" 17
rm -f "$W/flags/switch-fail-to"
expect "upstream은 새 슬롯(green)에 남음" "$(active)" 127.0.0.1:18082
expect "두 앱 모두 실행 중(지우지 않음)" "$(state blue) $(state green)" "running running"
check_last "기록: rollback 단계, rolled_back false" '"exit_code":17,"failed_step":"rollback","prev_slot":"blue","prev_running":true,"active_slot":"green","rolled_back":false'
# 런북 "손으로 되돌리기"처럼 이전 슬롯으로 되돌린다
runner bash "$W/bin/switch" 127.0.0.1:18081 >/dev/null 2>&1
docker stop -t 5 $T-app-green >/dev/null
after_fail manual-rollback

cp "$W/deploy.env" "$W/deploy.env.bak"
printf 'MIGRATE_TIMEOUT=4\nGRANTS_TIMEOUT=4\n' >>"$W/deploy.env"
echo "== 10d. Migration 시간 초과"
expect "종료 11" "$(deploy "$IMG_hang")" 11
after_fail migrate-timeout
expect "Migration 컨테이너는 멈춰 남김(실행 중 아님)" "$(docker container inspect -f '{{.State.Running}}' $T-app-migrate 2>/dev/null)" false
check_last "기록: failed_step migrate" '"exit_code":11,"failed_step":"migrate"'
if grep -q '4초 안에 끝나지 않았습니다' "$(lastout)"; then ok "출력에 시간 초과 안내"; else bad "출력에 시간 초과 안내"; fi

echo "== 10e. --apply-grants를 모르는 이미지(웹 앱으로 뜸) → 시간 초과로 끝냄"
expect "종료 12" "$(deploy "$IMG_oldimg")" 12
after_fail grants-old-image
expect "멈춰 남긴 Migration 컨테이너는 이번 배포가 지움" "$(docker ps -aq --filter "name=^$T-app-migrate$" | wc -l | tr -d ' ')" 0
expect "GRANT 컨테이너는 멈춰 남김" "$(docker container inspect -f '{{.State.Running}}' $T-app-grants 2>/dev/null)" false
expect "새 앱은 만들지 않음(green은 앞 시험의 멈춘 앱)" "$(state green) $(cimage green)" "exited $IMG_api503"
check_last "기록: failed_step grants" '"exit_code":12,"failed_step":"grants"'
mv "$W/deploy.env.bak" "$W/deploy.env"

echo "== 11. 동시 배포"
deploy "$IMG_slow" >"$W/first.rc" &
first=$!
sleep 4
expect "두 번째는 바로 거절(3)" "$(deploy "$IMG_v1")" 3
wait $first
expect "첫 번째는 성공" "$(cat "$W/first.rc")" 0
expect "upstream=green(slow)" "$(active)" 127.0.0.1:18082

echo "== 12. 배포 중 강제 종료(kill -9)"
runner bash -c "bash '$DIR/deploy.sh' --config '$W/deploy.env' '$IMG_slow2' & p=\$!; sleep 4; kill -9 \$p; wait \$p; exit 0" >"$(next_out)" 2>&1
expect "in-progress 기록이 남음" "$([ -f "$W/state/in-progress.json" ] && echo yes)" yes
expect "upstream 그대로 green" "$(active)" 127.0.0.1:18082
expect "Migration 컨테이너가 남아 있으면 거절(5)" "$(deploy "$IMG_v1")" 5
check_last "기록: 이전 배포를 interrupted로 남긴 뒤 이번은 failed" '"exit_code":5'
if grep -q '"result":"interrupted"' "$W/state/history.jsonl"; then ok "강제 종료된 배포가 interrupted로 기록됨"; else bad "강제 종료된 배포가 interrupted로 기록됨"; fi
# Migration 컨테이너는 끝나도 남는다(--rm 아님, 다음 배포가 지움). 실행이 끝날 때까지만 기다린다
for _ in $(seq 1 30); do [ "$(docker container inspect -f '{{.State.Running}}' $T-app-migrate 2>/dev/null)" = true ] || break; sleep 1; done
expect "남은 Migration이 끝난 뒤 다시 실행하면 성공" "$(deploy "$IMG_v1")" 0
expect "upstream=blue" "$(active)" 127.0.0.1:18081
expect "nginx 경유 응답=v1" "$(edge_version)" v1

echo "== 13. 서버 재시작 뒤(재부팅 흉내: 잠금 파일 사라짐, 활성만 자동 재시작 정책)"
rm -f "$W/lock/deploy.lock"
expect "활성 blue는 unless-stopped·실행" "$(docker container inspect -f '{{.HostConfig.RestartPolicy.Name}} {{.State.Status}}' $T-app-blue)" "unless-stopped running"
expect "비활성 green은 손으로 멈춘 상태(재부팅해도 안 뜸)" "$(state green)" exited
status_out="$(runner bash "$DIR/deploy.sh" --config "$W/deploy.env" --status 2>&1)"
if has "$status_out" "active: blue"; then ok "--status가 upstream으로 활성 슬롯을 보여 줌"; else bad "--status ($status_out)"; fi
expect "잠금 파일 없이도 다음 배포 성공" "$(deploy "$IMG_v2")" 0

echo "== upstream이 모르는 주소"
cp "$W/upstream/upstream.conf" "$W/upstream.keep"
printf 'upstream homestamp_app {\n  server 127.0.0.1:9999;\n}\n' >"$W/upstream/upstream.conf"
expect "종료 4(추측하지 않음)" "$(deploy "$IMG_v3")" 4
mv "$W/upstream.keep" "$W/upstream/upstream.conf"
expect "아무것도 바꾸지 않음(green v2 실행)" "$(state green) $(cimage green)" "running $IMG_v2"

echo "== 16. 비밀값이 출력·기록·컨테이너 설정에 없는지"
leak=0
grep -rqF "$CANARY" "$W/out" "$W/state" && leak=1
for c in $(docker ps -aq --filter "name=^$T-app-"); do
  docker container inspect "$c" | grep -qF "$CANARY" && leak=1
done
expect "canary 값 노출 없음" "$leak" 0
if grep -q 'mock migrate exit=0' "$W/out/"*; then ok "Migration 컨테이너는 비밀 폴더를 읽을 수 있었음(값은 출력 안 함)"; else bad "Migration 비밀 폴더 연결"; fi
expect "끝난 Migration·GRANT 컨테이너는 다음 배포가 지워 남지 않음" "$(docker ps -aq --filter "name=^$T-app-(migrate|grants)$" | wc -l | tr -d ' ')" 0

echo "== 기록 형식"
lines="$(wc -l <"$W/state/history.jsonl" | tr -d ' ')"
bad_lines="$(grep -cvE '^\{"deploy_id":"[0-9TZ-]+","image":"[^"]*","started_at":"[0-9TZ:-]+",' "$W/state/history.jsonl")"
expect "모든 줄이 같은 머리 형식(${lines}줄)" "$bad_lines" 0

echo "passed=$passed failed=$failed"
[ "$failed" -eq 0 ]
