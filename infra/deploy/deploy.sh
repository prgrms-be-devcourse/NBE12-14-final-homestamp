#!/usr/bin/env bash
# HomeStamp 블루/그린 배포 실행기(앱 EC2). CD가 SSM 문서로 부른다(04 9-2, 런북 docs/runbooks/배포-블루그린.md).
#
#   deploy.sh [--config 파일] <IMAGE_REPOSITORY>@sha256:<64자>   배포
#   deploy.sh [--config 파일] --status                           지금 상태 보기
#
# 순서: 잠금 → 활성 슬롯 → 이미지 받기 → --migrate → --apply-grants → 비활성 슬롯에 새 앱 → 헬스 체크
#       → nginx 전환 → 전환 뒤 확인(실패하면 이전 슬롯으로 되돌림) → 이전 앱 멈춤 → 기록 → 잠금 해제
#
# - 활성 슬롯은 nginx upstream 파일이 가리키는 주소로만 판단한다(트래픽이 실제로 가는 곳)
# - DB 변경(Migration·GRANT)은 어떤 실패에서도 되돌리지 않는다. 스키마는 이전 앱과도 맞게 쓴다(04 8장)
# - 비밀값은 읽지도 출력하지도 않는다. 앱은 SSM을 직접 읽고(prod 프로필), Migration 비밀 폴더는 내용을 보지 않고
#   읽기 전용으로 넘기기만 한다. 웹 앱 로그는 이 출력에 옮기지 않는다(docker logs로 따로 본다). Migration·GRANT 출력은
#   끝난 뒤 마지막 100줄만 옮긴다(#173·#299: 값을 넣지 않는 문장)
#
# 종료 코드: 0 성공·이미 배포됨 | 2 인자·설정 | 3 다른 배포 중 | 4 활성 슬롯을 알 수 없음 | 5 이전 Migration 컨테이너가 아직 실행 중
#            10 이미지 | 11 --migrate(실패·시간 초과) | 12 --apply-grants(실패·시간 초과) | 13 새 앱 시작 | 14 헬스 체크 | 15 nginx 전환(이전 앱 유지)
#            16 전환 뒤 확인 실패(되돌림 시도) | 17 되돌리기 실패(두 앱 모두 남김, 손으로 복구) | 129·130·143 신호로 중단
set -euo pipefail
set +x
umask 027

readonly EXIT_USAGE=2 EXIT_BUSY=3 EXIT_STATE=4 EXIT_LEFTOVER=5
readonly EXIT_IMAGE=10 EXIT_MIGRATE=11 EXIT_GRANTS=12 EXIT_START=13 EXIT_HEALTH=14
readonly EXIT_SWITCH=15 EXIT_VERIFY=16 EXIT_ROLLBACK=17

log() { printf '%s deploy: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() {
  local code=$1
  shift
  log "실패: $*" >&2
  exit "$code"
}

here="$(cd "$(dirname "$0")" && pwd)"
config="$here/deploy.env"
mode=deploy
image=""
while (($#)); do
  case "$1" in
    --config)
      (($# >= 2)) || die $EXIT_USAGE "--config 뒤에 파일이 없습니다"
      config=$2
      shift 2
      ;;
    --status)
      mode=status
      shift
      ;;
    -*) die $EXIT_USAGE "모르는 옵션입니다" ;;
    *)
      [[ -z $image ]] || die $EXIT_USAGE "이미지는 하나만 줍니다"
      image=$1
      shift
      ;;
  esac
done

# ---- 설정: KEY=value 줄만 읽는다(source 하지 않음). 비밀값은 넣지 않는 파일이다
KEYS="IMAGE_REPOSITORY BLUE_PORT GREEN_PORT NAME_PREFIX MIGRATION_UID MIGRATION_SECRETS_DIR UPSTREAM_FILE SWITCH_CMD
POST_SWITCH_URL POST_SWITCH_RESOLVE POST_SWITCH_EXPECT STATE_DIR LOCK_FILE HEALTH_TIMEOUT APP_MEMORY STOP_TIMEOUT
MIGRATE_TIMEOUT GRANTS_TIMEOUT VERIFY_TIMEOUT"
BLUE_PORT=8081
GREEN_PORT=8082
NAME_PREFIX=homestamp-app
MIGRATION_SECRETS_DIR=/run/secrets/homestamp-migration
UPSTREAM_FILE=/opt/homestamp/nginx/run/upstream/upstream.conf
SWITCH_CMD=/opt/homestamp/nginx/switch-upstream.sh
POST_SWITCH_RESOLVE=""
# 인증 없이 /api/에 가면 앱(Spring Security)만 401을 준다. nginx 자신의 404·503은 통과하지 못한다
POST_SWITCH_EXPECT=401
# 일회성 실행의 제한 시간(초). --apply-grants를 모르는 이미지는 웹 앱으로 떠 끝나지 않으므로 반드시 끝을 둔다
MIGRATE_TIMEOUT=900
GRANTS_TIMEOUT=300
# 전환 뒤 확인을 다시 묻는 시간(초). switch-upstream.sh가 reload 끝을 기다린 뒤라 보통 첫 요청에서 끝난다
VERIFY_TIMEOUT=20
STATE_DIR=/var/lib/homestamp/deploy
LOCK_FILE=/run/lock/homestamp-deploy.lock
HEALTH_TIMEOUT=120
APP_MEMORY=800m
STOP_TIMEOUT=30
IMAGE_REPOSITORY=""
MIGRATION_UID=""
POST_SWITCH_URL=""

[[ -f $config ]] || die $EXIT_USAGE "설정 파일이 없습니다: $config"
while IFS= read -r line || [[ -n $line ]]; do
  [[ -z $line || $line == \#* ]] && continue
  [[ $line =~ ^([A-Z_]+)=(.*)$ ]] || die $EXIT_USAGE "설정 줄 형식이 아닙니다(KEY=value)"
  key=${BASH_REMATCH[1]}
  case " ${KEYS//$'\n'/ } " in
    *" $key "*) printf -v "$key" '%s' "${BASH_REMATCH[2]}" ;;
    *) die $EXIT_USAGE "모르는 설정입니다: $key" ;;
  esac
done <"$config"

check() { [[ $2 =~ $3 ]] || die $EXIT_USAGE "$1 값이 형식에 맞지 않습니다"; }
check IMAGE_REPOSITORY "$IMAGE_REPOSITORY" '^[a-z0-9]([a-z0-9._:-]*[a-z0-9])?(/[a-z0-9]([a-z0-9._-]*[a-z0-9])?)+$'
check BLUE_PORT "$BLUE_PORT" '^[1-9][0-9]{3,4}$'
check GREEN_PORT "$GREEN_PORT" '^[1-9][0-9]{3,4}$'
((BLUE_PORT != GREEN_PORT && BLUE_PORT <= 65535 && GREEN_PORT <= 65535)) || die $EXIT_USAGE "BLUE_PORT·GREEN_PORT가 잘못됐습니다"
check NAME_PREFIX "$NAME_PREFIX" '^[a-z][a-z0-9-]{0,40}$'
check MIGRATION_UID "$MIGRATION_UID" '^[1-9][0-9]*:[1-9][0-9]*$'
for key in MIGRATION_SECRETS_DIR UPSTREAM_FILE SWITCH_CMD STATE_DIR LOCK_FILE; do
  check "$key" "${!key}" '^/[A-Za-z0-9._/-]+$'
done
check POST_SWITCH_URL "$POST_SWITCH_URL" '^https?://[A-Za-z0-9.-]+(:[0-9]+)?/[A-Za-z0-9._/-]*$'
[[ -z $POST_SWITCH_RESOLVE ]] || check POST_SWITCH_RESOLVE "$POST_SWITCH_RESOLVE" '^[a-z0-9.-]+:[0-9]+:[0-9.]+$'
check HEALTH_TIMEOUT "$HEALTH_TIMEOUT" '^[1-9][0-9]{0,2}$'
check APP_MEMORY "$APP_MEMORY" '^[1-9][0-9]*[mg]$'
check STOP_TIMEOUT "$STOP_TIMEOUT" '^[1-9][0-9]{0,2}$'
check POST_SWITCH_EXPECT "$POST_SWITCH_EXPECT" '^[1-5][0-9][0-9]$'
check MIGRATE_TIMEOUT "$MIGRATE_TIMEOUT" '^[1-9][0-9]{0,4}$'
check GRANTS_TIMEOUT "$GRANTS_TIMEOUT" '^[1-9][0-9]{0,4}$'
check VERIFY_TIMEOUT "$VERIFY_TIMEOUT" '^[1-9][0-9]{0,2}$'
for tool in docker curl flock; do
  command -v "$tool" >/dev/null || die $EXIT_USAGE "$tool 명령이 없습니다"
done

slot_port() { if [[ $1 == blue ]]; then echo "$BLUE_PORT"; else echo "$GREEN_PORT"; fi; }
slot_name() { echo "$NAME_PREFIX-$1"; }
other_slot() { if [[ $1 == blue ]]; then echo green; else echo blue; fi; }

# upstream 파일의 server 주소 하나 → blue/green/none. 그 밖이면 unknown
read_active() {
  [[ -e $UPSTREAM_FILE ]] || {
    echo none
    return
  }
  local servers
  servers="$(grep -oE 'server[[:space:]]+[^;[:space:]]+;' "$UPSTREAM_FILE" | awk '{print $2}' | tr -d ';' || true)"
  case "$servers" in
    "127.0.0.1:$BLUE_PORT") echo blue ;;
    "127.0.0.1:$GREEN_PORT") echo green ;;
    *) echo unknown ;;
  esac
}

c_field() { docker container inspect -f "$2" "$1" 2>/dev/null || true; }
c_exists() { docker container inspect "$1" >/dev/null 2>&1; }
c_running() { [[ "$(c_field "$1" '{{.State.Running}}')" == true ]]; }

if [[ $mode == status ]]; then
  [[ -z $image ]] || die $EXIT_USAGE "--status에는 이미지를 주지 않습니다"
  echo "active: $(read_active)"
  for s in blue green; do
    n="$(slot_name "$s")"
    if c_exists "$n"; then
      echo "$s: $(c_field "$n" '{{.State.Status}} restart={{.HostConfig.RestartPolicy.Name}} image={{.Config.Image}}')"
    else
      echo "$s: 없음"
    fi
  done
  if [[ -f $STATE_DIR/last.json ]]; then echo "last: $(cat "$STATE_DIR/last.json")"; fi
  if [[ -f $STATE_DIR/in-progress.json ]]; then echo "in-progress(끝나지 않은 배포): $(cat "$STATE_DIR/in-progress.json")"; fi
  exit 0
fi

[[ -n $image ]] || die $EXIT_USAGE "배포할 이미지가 없습니다"
[[ $image == "$IMAGE_REPOSITORY@sha256:"* && ${image#"$IMAGE_REPOSITORY@sha256:"} =~ ^[0-9a-f]{64}$ ]] ||
  die $EXIT_USAGE "이미지는 $IMAGE_REPOSITORY@sha256:<64자>만 받습니다(태그만으로는 배포하지 않음)"

# ---- 잠금: 배포 전체를 직렬화한다. 기다리지 않고 거절한다(CD가 다시 실행). 프로세스가 끝나면 커널이 풀어 준다
mkdir -p "$(dirname "$LOCK_FILE")"
exec 9>>"$LOCK_FILE"
flock -n 9 || die $EXIT_BUSY "다른 배포가 진행 중입니다"

# ---- 기록
mkdir -p "$STATE_DIR"
chmod 0750 "$STATE_DIR"
history="$STATE_DIR/history.jsonl"
deploy_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
step=state
result=failed
prev_slot=none
prev_running=false
rolled_back=false
interrupted=0

append() {
  printf '%s\n' "$1" >>"$history"
  chmod 0640 "$history"
}

if [[ -f $STATE_DIR/in-progress.json ]]; then
  log "끝나지 않은 이전 배포 기록이 있습니다(강제 종료로 추정). 중단으로 남깁니다"
  left="$(cat "$STATE_DIR/in-progress.json")"
  append "${left%\}},\"result\":\"interrupted\",\"detected_at\":\"$started_at\",\"db_changes_rolled_back\":false}"
fi
printf '{"deploy_id":"%s","image":"%s","started_at":"%s"}\n' "$deploy_id" "$image" "$started_at" >"$STATE_DIR/in-progress.json"

finish() {
  local rc=$? failed_step=null active
  trap - EXIT
  if ((rc == 0)); then
    [[ $result == noop ]] || result=success
  elif ((interrupted)); then
    result=interrupted
  else
    result=failed
  fi
  ((rc == 0)) || failed_step="\"$step\""
  active="$(read_active)"
  local record
  record="$(printf '{"deploy_id":"%s","image":"%s","started_at":"%s","finished_at":"%s","result":"%s","exit_code":%d,"failed_step":%s,"prev_slot":"%s","prev_running":%s,"active_slot":"%s","rolled_back":%s,"db_changes_rolled_back":false}' \
    "$deploy_id" "$image" "$started_at" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$result" "$rc" "$failed_step" \
    "$prev_slot" "$prev_running" "$active" "$rolled_back")"
  append "$record"
  printf '%s\n' "$record" >"$STATE_DIR/last.json.tmp" && mv "$STATE_DIR/last.json.tmp" "$STATE_DIR/last.json"
  rm -f "$STATE_DIR/in-progress.json"
  log "끝: result=$result exit=$rc active=$active"
  exit "$rc"
}
trap finish EXIT
trap 'interrupted=1; exit 143' TERM
trap 'interrupted=1; exit 130' INT
trap 'interrupted=1; exit 129' HUP

log "시작: $deploy_id $image"

# ---- 1. 활성 슬롯
active="$(read_active)"
[[ $active != unknown ]] || die $EXIT_STATE "upstream이 blue·green 주소가 아닙니다. 손으로 확인하세요(런북 복구)"
prev_slot=$active
if [[ $active == none ]]; then
  target=blue
else
  target="$(other_slot "$active")"
  if c_running "$(slot_name "$active")"; then prev_running=true; fi
fi
target_name="$(slot_name "$target")"
target_port="$(slot_port "$target")"
log "활성=$active(실행 중=$prev_running) → 새 슬롯=$target(127.0.0.1:$target_port)"

for once in migrate grants; do
  n="$NAME_PREFIX-$once"
  if c_running "$n"; then die $EXIT_LEFTOVER "이전 $once 컨테이너가 아직 실행 중입니다. 끝난 뒤 다시 배포하세요"; fi
  if c_exists "$n"; then docker rm "$n" >/dev/null; fi
done

# ---- 2. 이미지: digest로 받고, 받은 이미지가 그 digest인지 확인한다
step=image
docker pull -q "$image" >/dev/null || die $EXIT_IMAGE "이미지를 받지 못했습니다"
docker image inspect -f '{{range .RepoDigests}}{{println .}}{{end}}' "$image" | grep -qxF "$image" ||
  die $EXIT_IMAGE "받은 이미지의 digest가 다릅니다"

if [[ $prev_running == true && "$(c_field "$(slot_name "$active")" '{{.Config.Image}}')" == "$image" ]]; then
  result=noop
  log "같은 이미지가 이미 활성 슬롯에서 실행 중입니다. 아무것도 바꾸지 않습니다"
  exit 0
fi

# ---- 3·4. Migration → GRANT (같은 이미지, 일회성, 비밀 폴더는 읽기 전용, 0이 아니면 새 앱을 시작하지 않는다)
[[ -d $MIGRATION_SECRETS_DIR ]] || {
  step=migrate
  die $EXIT_MIGRATE "Migration 비밀 폴더가 없습니다"
}
# 떼어 놓고 돌려 끝날 때까지(최대 제한 시간) 기다린다. 끝나면 출력(#173·#299: 값을 넣지 않는 문장)을 옮기고 지운다.
# 제한 시간을 넘기면 멈추고, 멈춘 것을 확인한 뒤에만 실패로 끝낸다. 멈춘 컨테이너는 docker logs로 보도록 남기고
# 다음 배포가 지운다. 멈추지 못하면 다음 배포는 그것이 끝날 때까지 종료 5로 거절한다(잠금이 풀려도 겹치지 않게)
run_once() {
  local name="$NAME_PREFIX-$1" limit=$3 code deadline
  docker run -d --name "$name" --pull never \
    --user "$MIGRATION_UID" --read-only --tmpfs /tmp:rw,nosuid,nodev,size=64m \
    --cap-drop ALL --security-opt no-new-privileges --memory "$APP_MEMORY" \
    --mount "type=bind,src=$MIGRATION_SECRETS_DIR,dst=/run/secrets/homestamp-migration,readonly" \
    -e TZ=UTC "$image" "$2" >/dev/null || return 1
  deadline=$((SECONDS + limit))
  while c_running "$name"; do
    if ((SECONDS >= deadline)); then
      log "$2가 ${limit}초 안에 끝나지 않았습니다(이 인자를 모르는 이미지면 웹 앱으로 뜸). 멈춥니다"
      docker stop -t "$STOP_TIMEOUT" "$name" >/dev/null 2>&1 || true
      if c_running "$name"; then
        log "$name을 멈추지 못했습니다. 끝날 때까지 다음 배포는 종료 5로 거절됩니다"
      else
        log "멈춘 $name은 남겨 둡니다(docker logs $name, 다음 배포가 지움)"
      fi
      return 1
    fi
    sleep 2
  done
  # inspect가 잠깐 실패해도 끝난 것으로 보지 않는다: 끝났음(exited)을 확인한 뒤에만 결과를 읽고 지운다
  if [[ "$(c_field "$name" '{{.State.Status}}')" != exited ]]; then
    log "$name 상태를 확인하지 못했습니다. 지우지 않고 남깁니다(실행 중이면 다음 배포는 종료 5)"
    return 1
  fi
  code="$(c_field "$name" '{{.State.ExitCode}}')"
  docker logs --tail 100 "$name" 2>&1 || true
  docker rm "$name" >/dev/null 2>&1 || true
  [[ $code == 0 ]]
}
step=migrate
log "--migrate"
run_once migrate --migrate "$MIGRATE_TIMEOUT" || die $EXIT_MIGRATE "--migrate가 실패했습니다. 새 앱을 시작하지 않습니다"
step=grants
log "--apply-grants"
run_once grants --apply-grants "$GRANTS_TIMEOUT" ||
  die $EXIT_GRANTS "--apply-grants가 실패했습니다. 새 앱을 시작하지 않습니다(Migration은 이미 반영됨, GRANT는 한 트랜잭션이라 바뀐 권한 없음)"

# ---- 5. 비활성 슬롯에 새 앱(이전에 멈춰 둔 앱이나 실패해 남긴 앱은 여기서 지운다)
park_new() { docker stop -t "$STOP_TIMEOUT" "$target_name" >/dev/null 2>&1 || true; }
step=start
if c_exists "$target_name"; then docker rm -f "$target_name" >/dev/null; fi
docker run -d --name "$target_name" --pull never --restart unless-stopped \
  -p "127.0.0.1:$target_port:8080" --memory "$APP_MEMORY" \
  --read-only --tmpfs /tmp:rw,nosuid,nodev,size=256m --cap-drop ALL --security-opt no-new-privileges \
  --log-driver json-file --log-opt max-size=10m --log-opt max-file=3 \
  --label "homestamp.slot=$target" --label "homestamp.deploy-id=$deploy_id" \
  -e SPRING_PROFILES_ACTIVE=prod -e TZ=UTC \
  "$image" >/dev/null || die $EXIT_START "새 앱 컨테이너를 만들지 못했습니다"

# ---- 6. 헬스 체크: 새 슬롯의 127.0.0.1 포트, /actuator/health가 200 + UP(DB 지표 포함, DOWN이면 503)
step=health
deadline=$((SECONDS + HEALTH_TIMEOUT))
while :; do
  state="$(c_field "$target_name" '{{.State.Status}} {{.RestartCount}}')"
  if [[ $state != "running 0" ]]; then
    step=start
    park_new
    die $EXIT_START "새 앱이 시작 직후 멈췄습니다(docker logs $target_name)"
  fi
  body="$(curl -fsS --max-time 3 "http://127.0.0.1:$target_port/actuator/health" 2>/dev/null || true)"
  [[ $body == *'"status":"UP"'* ]] && break
  if ((SECONDS >= deadline)); then
    park_new
    die $EXIT_HEALTH "${HEALTH_TIMEOUT}초 안에 헬스 체크를 통과하지 못했습니다. 이전 앱을 그대로 둡니다"
  fi
  sleep 3
done
log "헬스 체크 통과"

# ---- 7. nginx 전환(switch-upstream.sh: 검사·reload가 실패하면 스스로 이전 주소로 되돌리고 0이 아닌 값)
step=switch
if ! "$SWITCH_CMD" "127.0.0.1:$target_port"; then
  park_new
  die $EXIT_SWITCH "nginx 전환이 실패했습니다. 이전 앱을 그대로 둡니다"
fi

# ---- 8. 전환 뒤 확인: upstream이 그 슬롯이고, nginx를 거친 요청에 그 슬롯의 앱이 답한다.
#      응답 코드가 POST_SWITCH_EXPECT(기본 401)이고 nginx가 붙인 X-Homestamp-Upstream(127.0.0.1에서 온 요청에만, 앱이 보낸
#      같은 이름은 nginx가 지움)이 정확히 한 줄로 그 슬롯 주소일 때만 통과한다. 이전 슬롯의 401, nginx 자신의 404·503,
#      헤더 없음·여러 줄·다른 주소는 통과하지 못한다. 매번 새 연결(curl 새 실행)로 VERIFY_TIMEOUT초 안에서 다시 묻는다
step=verify
verify() { # <슬롯>
  [[ "$(read_active)" == "$1" ]] || return 1
  local want hdr code got n deadline=$((SECONDS + VERIFY_TIMEOUT))
  local args=(-sS -o /dev/null -D - --max-time 5)
  want="127.0.0.1:$(slot_port "$1")"
  [[ -z $POST_SWITCH_RESOLVE ]] || args+=(--resolve "$POST_SWITCH_RESOLVE")
  while :; do
    hdr="$(curl "${args[@]}" "$POST_SWITCH_URL" 2>/dev/null | tr -d '\r' || true)"
    code="$(awk 'NR==1{print $2}' <<<"$hdr")"
    n="$(grep -ci '^x-homestamp-upstream:' <<<"$hdr" || true)"
    got="$(grep -i '^x-homestamp-upstream:' <<<"$hdr" | head -n 1 | cut -d: -f2- | tr -d ' ' || true)"
    [[ $code == "$POST_SWITCH_EXPECT" && $n == 1 && $got == "$want" ]] && return 0
    if ((SECONDS >= deadline)); then
      log "전환 뒤 확인 실패($1): 응답 ${code:-없음}, 답한 앱 ${got:-알 수 없음}(헤더 ${n:-0}줄), 기대 $POST_SWITCH_EXPECT·$want"
      return 1
    fi
    sleep 1
  done
}
if ! verify "$target"; then
  log "전환 뒤 확인이 실패했습니다. 이 사이 새 앱이 받은 요청의 DB 변경은 그대로 남습니다"
  if [[ $prev_running == true ]]; then
    step=rollback
    # 되돌린 주소만으로 끝내지 않는다: 이전 앱이 nginx를 거쳐 다시 답해야 되돌린 것으로 본다
    if "$SWITCH_CMD" "127.0.0.1:$(slot_port "$active")" && verify "$active"; then
      rolled_back=true
      park_new
      step=verify
      die $EXIT_VERIFY "이전 슬롯($active)으로 되돌렸습니다"
    fi
    die $EXIT_ROLLBACK "되돌리기를 확인하지 못했습니다. 두 앱을 모두 남깁니다(런북 \"손으로 되돌리기\")"
  fi
  die $EXIT_VERIFY "되돌릴 이전 앱이 없어 새 앱을 그대로 둡니다"
fi
log "전환 확인: $target"

# ---- 9. 이전 앱은 멈추되 지우지 않는다(빠른 손 복구용, 다음 배포가 이 슬롯을 쓸 때 지움)
step=finish
if [[ $active != none ]] && c_exists "$(slot_name "$active")"; then
  docker stop -t "$STOP_TIMEOUT" "$(slot_name "$active")" >/dev/null || log "경고: 이전 앱을 멈추지 못했습니다"
fi
exit 0
