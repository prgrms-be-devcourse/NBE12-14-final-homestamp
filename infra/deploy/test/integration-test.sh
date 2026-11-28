#!/usr/bin/env bash
# deploy.sh 실제 통합 시험(로컬 Docker만. AWS·운영 Secret 없음). CI에서는 돌리지 않는다(host 네트워크 80·443을 씀)
#   integration-test.sh <저장소 루트> <빈 작업 폴더> <실제 이미지 태그>
#   예) docker build -t homestamp-int-real:local back && infra/deploy/test/integration-test.sh . "$(mktemp -d)" homestamp-int-real:local
#   필요: Linux Docker 호스트(또는 Docker Desktop)의 80·443·15100·18181·18182가 비어 있고 homestamp-nginx 컨테이너가 없을 것
# A. 실제 nginx(#204 설정·render.sh·switch-upstream.sh, HTTPS는 시험 CA) + 가짜 앱(#205 mock-app)
# B. 실제 이미지(back/Dockerfile 빌드 + 시험 CA 파일 하나만 더함) + 실제 PostgreSQL 18.6(bootstrap_roles.sql)
#    --migrate·--apply-grants는 verify-full TLS로 실제 DB에. 웹 앱은 prod 프로필이라 SSM 없이는 뜨지 못함(예상 실패)
# shellcheck disable=SC2154 # IMG_<이름>은 printf -v로 만든다
set -uo pipefail
SRC="$(cd "${1:?worktree}" && pwd -P)"
W="${2:?빈 작업 폴더}"
REAL="${3:?실제 이미지 태그}"
[ -d "$W" ] && [ -z "$(ls -A "$W")" ] || { echo "빈 폴더가 아닙니다: $W" >&2; exit 2; }
W="$(cd "$W" && pwd -P)"
T=hs-int
NGINX_IMAGE=nginx:1.28-alpine@sha256:a8b39bd9cf0f83869a2162827a0caf6137ddf759d50a171451b335cecc87d236
REGISTRY_IMAGE=registry:3@sha256:82f6ff4a7f982960c8aa51617fea3aabc485df6e55cce80d22deb1de36f1bc3c
PG_IMAGE=postgres:18.6
REPO=127.0.0.1:15100/$T/app
EDGE=homestamp-nginx # switch-upstream.sh 기본값(NGINX_TEST·NGINX_RELOAD를 바꾸지 않음)
DOMAIN=homestamp.test
PW="int-$(od -An -tx1 -N12 /dev/urandom | tr -d ' \n')" # 시험용 Migration 비밀번호(카나리)
passed=0
failed=0
ok() { passed=$((passed + 1)); echo "ok   $1"; }
bad() { failed=$((failed + 1)); echo "FAIL $1"; }
expect() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (기대 $3, 실제 $2)"; fi; }

cleanup() {
  ids="$(docker ps -aq --filter "name=^$T-") $(docker ps -aq --filter "name=^$EDGE\$")"
  [ -z "${ids// /}" ] || docker rm -f $ids >/dev/null 2>&1
  imgs="$(docker image ls -q --filter "reference=$T-*"; docker image ls -q --filter "reference=$REPO")"
  [ -z "$imgs" ] || docker image rm -f $imgs >/dev/null 2>&1
}
[ -z "$(docker ps -aq --filter "name=^$EDGE\$")" ] || { echo "이미 $EDGE 컨테이너가 있습니다(지우지 않음)" >&2; exit 2; }
trap cleanup EXIT
cleanup

mkdir -p "$W/secrets" "$W/state" "$W/lock" "$W/out" "$W/le/live/$DOMAIN" "$W/webroot" "$W/front/releases/v1" "$W/ca" "$W/pgtls"
echo "<html>front-v1</html>" >"$W/front/releases/v1/index.html"
# A(가짜 앱)용 자리표시 비밀 파일: 가짜 앱은 있는지만 본다. B에서 실제 시험 값으로 바꾼다
for f in spring.datasource.url spring.datasource.username spring.datasource.password app.migration.owner-role; do
  printf 'placeholder-%s' "$f" >"$W/secrets/$f"
done
chmod 0644 "$W"/secrets/*
ln -s releases/v1 "$W/front/current"

# ---- 시험 CA와 인증서(nginx: DNS homestamp.test, PostgreSQL: 컨테이너 IP)
cat >"$W/ca/Dockerfile" <<EOF
FROM docker:29-cli@sha256:34507eb5069d8be82c95dd3b6987d4250fd5fee49b82e2509b7d7a7030740d6d
RUN apk add --no-cache bash curl util-linux-misc openssl ca-certificates
EOF
docker build -q -t $T-tools:local "$W/ca" >/dev/null || { echo "도구 이미지 실패" >&2; exit 1; }
tools() { docker run --rm -v "$W:$W" -w "$W" $T-tools:local "$@"; }
tools sh -c "openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj /CN=hs-int-test-ca -keyout ca/ca.key -out ca/ca.crt \
  -addext basicConstraints=critical,CA:TRUE -addext keyUsage=critical,keyCertSign,cRLSign 2>/dev/null &&
  openssl req -newkey rsa:2048 -nodes -subj /CN=$DOMAIN -keyout le/live/$DOMAIN/privkey.pem -out ca/edge.csr 2>/dev/null &&
  printf 'subjectAltName=DNS:$DOMAIN\nextendedKeyUsage=serverAuth\n' >ca/edge.ext &&
  openssl x509 -req -in ca/edge.csr -CA ca/ca.crt -CAkey ca/ca.key -CAcreateserial -days 2 -extfile ca/edge.ext -out le/live/$DOMAIN/fullchain.pem 2>/dev/null"
# deploy.sh를 돌리는 runner: #205 runner와 같은 도구 + 시험 CA 신뢰(전환 뒤 확인의 HTTPS curl용, deploy.sh는 그대로)
cat >"$W/ca/Dockerfile.runner" <<EOF
FROM $T-tools:local
COPY ca.crt /usr/local/share/ca-certificates/hs-int-test-ca.crt
RUN update-ca-certificates >/dev/null 2>&1
EOF
docker build -q -f "$W/ca/Dockerfile.runner" -t $T-runner:local "$W/ca" >/dev/null || { echo "runner 실패" >&2; exit 1; }

docker run -d --name $T-registry -p 127.0.0.1:15100:5000 "$REGISTRY_IMAGE" >/dev/null
for _ in 1 2 3 4 5 6 7 8 9 10; do curl -fs http://127.0.0.1:15100/v2/ >/dev/null && break; sleep 1; done

# ---- 가짜 앱(A용)과 실제 이미지(B용)
build_mock() { # 이름 [build-arg...]
  local name=$1 args=()
  shift
  for a in "$@"; do args+=(--build-arg "$a"); done
  docker build -q --build-arg "BASE_IMAGE=$NGINX_IMAGE" "${args[@]}" -t "$REPO:$name" "$SRC/infra/deploy/test/mock-app" >/dev/null &&
    docker push -q "$REPO:$name" >/dev/null || { echo "mock $name 실패" >&2; exit 1; }
  printf -v "IMG_$name" '%s' "$(docker image inspect -f '{{index .RepoDigests 0}}' "$REPO:$name")"
}
build_mock v1 VERSION=v1
build_mock v2 VERSION=v2
build_mock api404 VERSION=api404 API_CODE=404
build_mock api503 VERSION=api503 API_CODE=503
mkdir -p "$W/realimg"
cp "$W/ca/ca.crt" "$W/realimg/test-ca.crt"
printf 'FROM %s\nCOPY --chmod=0444 test-ca.crt /app/certs/test-ca.crt\n' "$REAL" >"$W/realimg/Dockerfile"
docker build -q -t "$REPO:real" "$W/realimg" >/dev/null && docker push -q "$REPO:real" >/dev/null || { echo "실제 이미지 푸시 실패" >&2; exit 1; }
IMG_real="$(docker image inspect -f '{{index .RepoDigests 0}}' "$REPO:real")"
expect "시험 이미지는 실제 이미지 위에 CA 파일 하나만(앱 jar 같음)" \
  "$(docker run --rm --entrypoint sha256sum "$REAL" /app/app.jar | cut -c1-64)" \
  "$(docker run --rm --entrypoint sha256sum "$REPO:real" /app/app.jar | cut -c1-64)"

# ---- 실제 nginx(#204): 설정 복사 → render.sh(HTTPS) → compose와 같은 보안 옵션, host 네트워크(운영과 같음)
cp -R "$SRC/infra/nginx" "$W/nginx"
rm -rf "$W/nginx/run" "$W/nginx/test"
cat >"$W/nginx/homestamp.env" <<EOF
DOMAIN=$DOMAIN
PHOTOS_BUCKET=homestamp-photos
APP_UPSTREAM=127.0.0.1:18181
HSTS_MAX_AGE=
CERT_ROOT=$W/le
EOF
"$W/nginx/render.sh" "$W/nginx/homestamp.env" >/dev/null || { echo "render 실패" >&2; exit 1; }
grep -q "listen 443 ssl" "$W/nginx/run/conf.d/homestamp.conf" && ok "render: HTTPS 모드" || bad "render HTTPS"
start_edge() {
  docker run -d --name "$EDGE" --network host \
    --read-only --tmpfs /tmp --tmpfs /var/cache/nginx \
    --security-opt no-new-privileges:true --cap-drop ALL \
    --cap-add NET_BIND_SERVICE --cap-add SETUID --cap-add SETGID --cap-add CHOWN \
    -e NGINX_ENTRYPOINT_QUIET_LOGS=1 \
    -v "$W/nginx/nginx.conf:/etc/nginx/nginx.conf:ro" \
    -v "$W/nginx/run/conf.d:/etc/nginx/conf.d:ro" \
    -v "$W/nginx/run/snippets:/etc/nginx/snippets:ro" \
    -v "$W/nginx/run/upstream:/etc/nginx/homestamp:ro" \
    -v "$W/le:/etc/letsencrypt:ro" \
    -v "$W/webroot:/var/www/certbot:ro" \
    -v "$W/front:/srv/homestamp/front:ro" \
    "$NGINX_IMAGE" >/dev/null
  sleep 2
}
start_edge
docker exec "$EDGE" nginx -t -q && ok "실제 nginx -t" || bad "nginx -t"

cat >"$W/deploy.env" <<EOF
IMAGE_REPOSITORY=$REPO
MIGRATION_UID=10002:10002
POST_SWITCH_URL=https://$DOMAIN/api/
POST_SWITCH_RESOLVE=$DOMAIN:443:127.0.0.1
BLUE_PORT=18181
GREEN_PORT=18182
NAME_PREFIX=$T-app
MIGRATION_SECRETS_DIR=$W/secrets
UPSTREAM_FILE=$W/nginx/run/upstream/upstream.conf
SWITCH_CMD=$W/nginx/switch-upstream.sh
STATE_DIR=$W/state
LOCK_FILE=$W/lock/deploy.lock
HEALTH_TIMEOUT=60
STOP_TIMEOUT=10
MIGRATE_TIMEOUT=300
GRANTS_TIMEOUT=120
EOF
echo 0 >"$W/n"
runner() { docker run --rm --network host -e RELOAD_WAIT -v /var/run/docker.sock:/var/run/docker.sock -v "$W:$W" -v "$SRC:$SRC:ro" $T-runner:local "$@"; }
# 밖의 요청 흉내: bridge 컨테이너에서 Docker 호스트(게이트웨이) 주소로. nginx가 보는 주소가 127.0.0.1이 아님
GW="$(docker network inspect bridge -f '{{(index .IPAM.Config 0).Gateway}}')"
outside() { docker run --rm $T-runner:local curl -s --max-time 5 --resolve "$DOMAIN:443:$GW" -D - -o /dev/null "https://$DOMAIN$1" | tr -d '\r'; }
deploy() { # 출력은 $W/out/N(번호는 파일로 센다: $(...) 안에서도 이어짐)
  local i
  i=$(($(cat "$W/n") + 1))
  echo "$i" >"$W/n"
  runner bash "$SRC/infra/deploy/deploy.sh" --config "$W/deploy.env" "$@" >"$W/out/$i" 2>&1
  echo $?
}
out() { cat "$W/out/$(cat "$W/n")"; }
active() { grep -oE '127\.0\.0\.1:[0-9]+' "$W/nginx/run/upstream/upstream.conf" 2>/dev/null || echo none; }
# 실제 nginx를 HTTPS로 거친 응답(시험 CA로 인증서 검증)
edge() { runner curl -s --max-time 5 --resolve "$DOMAIN:443:127.0.0.1" -D - -o /dev/null "https://$DOMAIN$1" | tr -d '\r'; }
probe() { edge /api/ | awk -F': ' 'tolower($1)=="x-homestamp-upstream"{print $2}'; }
edge_code() { edge "$1" | awk 'NR==1{print $2}'; }
edge_version() { edge /api/ | awk -F': ' 'tolower($1)=="x-mock-version"{print $2}'; }
last() { tail -n 1 "$W/state/history.jsonl"; }
state() { docker container inspect -f '{{.State.Status}}' "$T-app-$1" 2>/dev/null || echo none; }

echo "== A. 실제 nginx + 가짜 앱"
expect "처음: 앱 없음 → nginx의 503(@api_unavailable)" "$(edge_code /api/)" 503
expect "처음: 화면(index.html) 200" "$(edge_code /)" 200
expect "A1 첫 배포 종료 0" "$(deploy "$IMG_v1")" 0
expect "A1 upstream=green(활성 blue 주소였지만 blue 없음)" "$(active)" 127.0.0.1:18182
expect "A1 실제 nginx HTTPS로 v1" "$(edge_version)" v1
expect "A1 nginx 경유 401(앱의 답)" "$(edge_code /api/)" 401
expect "A2 v2 배포 종료 0" "$(deploy "$IMG_v2")" 0
expect "A2 upstream=blue" "$(active)" 127.0.0.1:18181
expect "A2 실제 nginx로 v2" "$(edge_version)" v2
expect "A2 이전 green은 멈추고 남김" "$(state green)" exited
expect "A2 127.0.0.1 요청에는 실제로 답한 앱 주소(blue)" "$(probe)" 127.0.0.1:18181
oh="$(outside /api/)"
expect "A2 밖의 요청: 401" "$(awk 'NR==1{print $2}' <<<"$oh")" 401
expect "A2 밖의 요청에는 X-Homestamp-Upstream 없음" "$(grep -ci '^x-homestamp-upstream:' <<<"$oh")" 0
expect "A2 밖의 요청: Cache-Control no-store 한 줄" "$(grep -ci '^cache-control: no-store' <<<"$oh")-$(grep -ci '^cache-control:' <<<"$oh")" 1-1
expect "A2 밖의 요청: nosniff·X-Frame-Options 한 줄씩" "$(grep -ci '^x-content-type-options:' <<<"$oh")$(grep -ci '^x-frame-options:' <<<"$oh")" 11
expect "A2 밖의 요청: HSTS는 기본 꺼짐(#204 정책)" "$(grep -ci '^strict-transport-security:' <<<"$oh")" 0
expect "A3 (이번에 찾은 오판) 전환 뒤 앱이 404 → 성공 아님, 되돌림 16" "$(deploy "$IMG_api404")" 16
expect "A3 upstream=blue로 되돌림" "$(active)" 127.0.0.1:18181
expect "A3 실제 nginx로 다시 v2" "$(edge_version)" v2
case "$(last)" in *'"rolled_back":true'*) ok "A3 기록 rolled_back=true" ;; *) bad "A3 기록 $(last)" ;; esac
expect "A4 전환 뒤 503 → 되돌림 16" "$(deploy "$IMG_api503")" 16
expect "A4 실제 nginx로 다시 v2" "$(edge_version)" v2
expect "A4 되돌린 뒤 답한 앱 blue" "$(probe)" 127.0.0.1:18181

# A4b reload 지연: 처리 중인 느린 요청이 이전 작업 프로세스를 붙잡는 동안 전환은 그 끝을 기다린다(고정 sleep 아님)
head -c 900000 /dev/zero >"$W/slow.bin" # 30k/s로 약 30초
slow() { runner curl -s --max-time 60 --limit-rate 30k -o /dev/null -w '%{http_code}' --resolve "$DOMAIN:443:127.0.0.1" --data-binary "@$W/slow.bin" "https://$DOMAIN/api/slow" >"$W/slow.code" 2>&1; date +%s >"$W/slow.end"; }
slow &
spid=$!
sleep 2
t0=$SECONDS
expect "A4b 느린 요청 중 v1 배포 종료 0" "$(deploy "$IMG_v1")" 0
took=$((SECONDS - t0))
deploy_end="$(date +%s)"
wait $spid
expect "A4b 처리 중이던 요청은 끊기지 않고 끝남(이전 앱의 401)" "$(cat "$W/slow.code")" 401
[ "$deploy_end" -ge "$(cat "$W/slow.end")" ] && ok "A4b 배포는 처리 중이던 요청이 끝난 뒤에 끝남(${took}초, 이전 작업 프로세스를 기다림)" || bad "A4b 요청보다 먼저 끝남(${took}초)"
expect "A4b 답한 앱 green" "$(probe)" 127.0.0.1:18182
slow &
spid=$!
sleep 2
expect "A4c 기다림 제한(RELOAD_WAIT=2)을 넘기면 전환 실패 15" "$(RELOAD_WAIT=2 deploy "$IMG_v2")" 15
wait $spid
expect "A4c upstream은 green 그대로(되돌림)" "$(active)" 127.0.0.1:18182
expect "A4c 답한 앱 green 그대로" "$(probe)" 127.0.0.1:18182
expect "A4d 다시 v2 → blue, 0" "$(deploy "$IMG_v2")" 0
# A4e 되돌리기 실패: 새 앱(404)이 확인에 실패하는 사이 이전 앱(blue)이 사라지면 되돌린 뒤 확인도 실패 → 17, 컨테이너는 지우지 않음
i=$(($(cat "$W/n") + 1)); echo "$i" >"$W/n"
runner bash "$SRC/infra/deploy/deploy.sh" --config "$W/deploy.env" "$IMG_api404" >"$W/out/$i" 2>&1 &
dpid=$!
for _ in $(seq 1 120); do grep -q '헬스 체크 통과' "$W/out/$i" 2>/dev/null && break; sleep 0.5; done
docker stop -t 2 "$T-app-blue" >/dev/null
wait $dpid
expect "A4e 되돌리기를 확인하지 못함 → 17" "$?" 17
grep -q '전환 뒤 확인 실패(blue)' "$W/out/$i" && ok "A4e 되돌린 blue도 같은 식별로 확인(실패)" || bad "A4e 출력: $(grep '확인 실패' "$W/out/$i" | tr '\n' ' ')"
expect "A4e 두 앱 컨테이너 남김" "$(state blue) $(state green)" "exited running"
case "$(last)" in *'"exit_code":17,"failed_step":"rollback"'*) ok "A4e 기록 rollback 17" ;; *) bad "A4e 기록 $(last)" ;; esac
# 런북 "손으로 되돌리기" 흉내: blue를 다시 켜고 blue로
docker start "$T-app-blue" >/dev/null
sleep 2
runner bash "$W/nginx/switch-upstream.sh" 127.0.0.1:18181 >/dev/null
docker stop -t 2 "$T-app-green" >/dev/null
expect "A4e 손 복구 뒤 답한 앱 blue" "$(probe)" 127.0.0.1:18181

docker stop "$EDGE" >/dev/null
before="$(cat "$W/nginx/run/upstream/upstream.conf")"
expect "A5 nginx가 꺼져 전환 실패 → 15" "$(deploy "$IMG_v1")" 15
expect "A5 upstream 파일은 그대로(switch-upstream.sh가 되돌림)" "$(cat "$W/nginx/run/upstream/upstream.conf")" "$before"
expect "A5 활성 blue는 계속 실행" "$(state blue)" running
docker start "$EDGE" >/dev/null
sleep 2
expect "A5 nginx 다시 켠 뒤 v2" "$(edge_version)" v2
cp "$W/nginx/run/upstream/upstream.conf" "$W/upstream.bak"
printf 'upstream homestamp_app {\n  server 127.0.0.1:19999;\n}\n' >"$W/nginx/run/upstream/upstream.conf"
expect "A6 모르는 upstream → 4" "$(deploy "$IMG_v1")" 4
expect "A6 앱 컨테이너를 건드리지 않음" "$(state blue)" running
cp "$W/upstream.bak" "$W/nginx/run/upstream/upstream.conf"
expect "A7 같은 이미지(v2 활성) → noop 0" "$(deploy "$IMG_v2")" 0
out | grep -q 'result=noop' && ok "A7 결과 줄 noop" || bad "A7 noop: $(out | tail -n 2)"

echo "== B. 실제 이미지 + 실제 PostgreSQL"
docker run -d --name $T-pg -e POSTGRES_PASSWORD=admin-local-only -e POSTGRES_DB=homestamp "$PG_IMAGE" >/dev/null
for _ in $(seq 1 30); do docker exec $T-pg pg_isready -q -U postgres -d homestamp && break; sleep 1; done
sleep 2
PGIP="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $T-pg)"
[[ $PGIP =~ ^[0-9.]+$ ]] || { echo "PostgreSQL IP를 읽지 못함: $PGIP" >&2; exit 1; }
tools sh -c "openssl req -newkey rsa:2048 -nodes -subj /CN=$PGIP -keyout pgtls/server.key -out ca/pg.csr 2>/dev/null &&
  printf 'subjectAltName=IP:$PGIP\nextendedKeyUsage=serverAuth\n' >ca/pg.ext &&
  openssl x509 -req -in ca/pg.csr -CA ca/ca.crt -CAkey ca/ca.key -CAcreateserial -days 2 -extfile ca/pg.ext -out pgtls/server.crt 2>/dev/null"
docker cp "$W/pgtls/server.crt" $T-pg:/var/lib/postgresql/server.crt >/dev/null
docker cp "$W/pgtls/server.key" $T-pg:/var/lib/postgresql/server.key >/dev/null
docker exec $T-pg sh -c 'chown postgres:postgres /var/lib/postgresql/server.* && chmod 600 /var/lib/postgresql/server.key'
psqla() { docker exec -i $T-pg psql -X -q -v ON_ERROR_STOP=1 -U postgres -d homestamp "$@"; }
psqla -c "ALTER SYSTEM SET ssl = on" -c "ALTER SYSTEM SET ssl_cert_file = '/var/lib/postgresql/server.crt'" \
  -c "ALTER SYSTEM SET ssl_key_file = '/var/lib/postgresql/server.key'" >/dev/null
psqla -c "SELECT pg_reload_conf()" >/dev/null
sleep 1
expect "PostgreSQL TLS 켜짐" "$(psqla -At -c 'SHOW ssl')" on
docker cp "$SRC/infra/db/bootstrap_roles.sql" $T-pg:/tmp/bootstrap_roles.sql >/dev/null
# 역할 이름은 운영 이름(이미지 roles.properties와 같아야 --apply-grants가 대상을 찾는다, decisions 2026-11-28)
docker exec $T-pg psql -X -q -U postgres -d homestamp -v owner_role=hs_owner -v migration_role=hs_migrator \
  -v app_role=hs_app -v purge_role=hs_purge -v human_role=hs_human_read -f /tmp/bootstrap_roles.sql >/dev/null 2>&1 &&
  ok "Role Bootstrap(bootstrap_roles.sql)" || bad "Role Bootstrap"
printf "ALTER ROLE hs_migrator PASSWORD '%s';\n" "$PW" | psqla >/dev/null
printf '%s' "jdbc:postgresql://$PGIP:5432/homestamp?sslmode=verify-full&sslrootcert=/app/certs/test-ca.crt" >"$W/secrets/spring.datasource.url"
printf '%s' hs_migrator >"$W/secrets/spring.datasource.username"
printf '%s' "$PW" >"$W/secrets/spring.datasource.password"
printf '%s' hs_owner >"$W/secrets/app.migration.owner-role"
chmod 0644 "$W"/secrets/*

v_count() { psqla -At -c "SELECT count(*) FROM public.flyway_schema_history WHERE success" 2>/dev/null || echo 0; }
grant_ok() { psqla -At -c "SELECT has_table_privilege('hs_app','record.action','INSERT') AND has_table_privilege('hs_app','record.action','SELECT') AND NOT has_table_privilege('hs_app','record.action','UPDATE')" 2>/dev/null || echo error; }
expect "B0 처음 Flyway 이력 없음" "$(v_count)" 0
rc="$(deploy "$IMG_real")"
echo "     B1 종료 $rc"
out | grep -E '^\[|단계|끝:|migrate|grants|헬스|health' | grep -vi password | tail -n 25 | sed 's/^/     | /'
expect "B1 --migrate가 실제 DB에 적용(이력 > 0)" "$( [ "$(v_count)" -gt 0 ] && echo yes || echo no)" yes
expect "B1 --apply-grants 적용(app: action SELECT·INSERT, UPDATE 없음)" "$(grant_ok)" t
expect "B1 웹 앱은 prod 프로필이라 SSM(리전) 없이 시작 실패 → 13" "$rc" 13
expect "B1 upstream은 그대로 blue(v2)" "$(active)" 127.0.0.1:18181
expect "B1 실제 nginx로 여전히 v2" "$(edge_version)" v2
v1count="$(v_count)"
expect "B2 다시 실행해도 13(Migration·GRANT는 다시 0)" "$(deploy "$IMG_real")" 13
expect "B2 이력 개수 그대로(새로 적용 없음)" "$(v_count)" "$v1count"
expect "B2 권한 그대로" "$(grant_ok)" t
docker stop $T-pg >/dev/null
expect "B3 DB가 꺼져 있으면 --migrate 실패 → 11" "$(deploy "$IMG_real")" 11
out | grep -q 'grants' && out | grep -qE 'apply-grants.*(시작|실행)' && bad "B3 GRANT를 실행함" || ok "B3 GRANT·새 앱 단계로 가지 않음"
expect "B3 upstream 그대로 blue" "$(active)" 127.0.0.1:18181
grep -rqF "$PW" "$W/out" "$W/state" && bad "출력·기록에 시험 비밀번호가 나옴" || ok "출력·기록에 시험 비밀번호 없음"
grep -rqF "jdbc:postgresql" "$W/out" "$W/state" && bad "출력·기록에 JDBC 주소" || ok "출력·기록에 JDBC 주소 없음"

echo "passed=$passed failed=$failed"
[ "$failed" = 0 ]
