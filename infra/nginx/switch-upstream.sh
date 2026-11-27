#!/usr/bin/env bash
# nginx가 보내는 앱을 바꾼다(블루/그린의 마지막 단계, 04 3-2·9장).
#   사용: switch-upstream.sh <host:port>               바꾸고 nginx -t → reload. 실패하면 이전으로 되돌린다
#         switch-upstream.sh --write-only <host:port>  파일만 만든다(render.sh의 첫 설치용)
#
# 부르는 쪽(배포 스크립트)의 약속: --migrate·--apply-grants가 둘 다 0으로 끝나고, 새 앱의 헬스 체크가
# 통과한 뒤에만 부른다. 이 스크립트는 헬스를 다시 보지 않는다. 0이 아니면 배포 스크립트는 새 앱을 멈추고
# 남긴 채(지우는 것은 다음 배포) 이전 앱을 그대로 둔다. DB 변경(앞 단계 Migration)은 되돌리지 않는다.
#
# 환경 변수(로컬 시험에서만 바꾼다)
#   NGINX_TEST    설정 검사 명령(기본: docker exec homestamp-nginx nginx -t -q)
#   NGINX_RELOAD  다시 읽기 명령(기본: docker exec homestamp-nginx nginx -s reload)
#   NGINX_WORKERS 작업 프로세스 PID 목록 명령(기본: homestamp-nginx 안에서 master의 자식 PID)
#   RELOAD_WAIT   reload 뒤 이전 작업 프로세스가 끝나기를 기다리는 초(기본 45, nginx worker_shutdown_timeout 30초보다 길게)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
dir="$here/run/upstream"
conf="$dir/upstream.conf"

write_only=false
if [[ "${1:-}" == "--write-only" ]]; then
  write_only=true
  shift
fi
target="${1:?usage: switch-upstream.sh [--write-only] <host:port>}"

# 설정 파일에 들어가므로 host:port 모양만 받는다
[[ "$target" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?:[0-9]{1,5}$ ]] \
  || { echo "host:port 형식이 아닙니다: '$target'" >&2; exit 2; }
port="${target##*:}"
(( port >= 1 && port <= 65535 )) || { echo "포트 범위가 아닙니다: $port" >&2; exit 2; }
# 파일을 바꾸기 전에 검사한다(잘못된 값으로 파일만 바뀐 채 끝나지 않게)
RELOAD_WAIT="${RELOAD_WAIT:-45}"
[[ "$RELOAD_WAIT" =~ ^[1-9][0-9]{0,2}$ ]] || { echo "RELOAD_WAIT 형식이 아닙니다" >&2; exit 2; }

mkdir -p "$dir"
tmp="$(mktemp "$dir/upstream.conf.XXXXXX")"
cat > "$tmp" <<EOF
# switch-upstream.sh가 쓴다. 손으로 고치지 않는다
upstream homestamp_app {
  server $target;
  keepalive 8;
}
EOF
chmod 644 "$tmp"

if $write_only; then
  mv -f "$tmp" "$conf"
  echo "upstream written: $target"
  exit 0
fi

# 파일이 아니라 폴더를 컨테이너에 묶어 두어야 mv로 바꾼 새 파일이 보인다(compose.yaml)
prev="$dir/upstream.conf.prev"
# 지난 실행의 사본으로 되돌리지 않게 지우고 지금 파일만 남긴다
rm -f "$prev"
if [[ -f "$conf" ]]; then cp -p "$conf" "$prev"; fi
mv -f "$tmp" "$conf"

NGINX_TEST="${NGINX_TEST:-docker exec homestamp-nginx nginx -t -q}"
NGINX_RELOAD="${NGINX_RELOAD:-docker exec homestamp-nginx nginx -s reload}"
workers() {
  if [[ -n "${NGINX_WORKERS:-}" ]]; then
    bash -c "$NGINX_WORKERS"
  else
    docker exec homestamp-nginx sh -c 'pgrep -P "$(cat /tmp/nginx.pid)"'
  fi
}

restore() {
  if [[ -f "$prev" ]]; then
    mv -f "$prev" "$conf"
    echo "되돌림: 이전 앱 주소로" >&2
  fi
}

if ! $NGINX_TEST; then
  echo "nginx -t 실패: 바꾸지 않음" >&2
  restore
  exit 1
fi
# reload는 신호만 보내고 바로 돌아온다. 이전 작업 프로세스는 처리 중인 요청을 마칠 때까지 옛 주소로 답하므로,
# 그 프로세스가 모두 끝나야 새 연결이 모두 새 주소로 간다(전환 뒤 확인이 이전 앱의 답을 받지 않게)
if ! old="$(workers)" || [[ -z "$old" ]]; then
  echo "nginx 작업 프로세스를 읽지 못함: 바꾸지 않음" >&2
  restore
  exit 1
fi
if ! $NGINX_RELOAD; then
  echo "reload 실패: 이전 설정으로 되돌리고 다시 읽음" >&2
  restore
  $NGINX_RELOAD || true
  exit 1
fi
deadline=$((SECONDS + RELOAD_WAIT))
while :; do
  if now="$(workers)" && [[ -n "$now" ]]; then
    left=0
    for pid in $old; do
      grep -qx "$pid" <<<"$now" && left=$((left + 1))
    done
    ((left == 0)) && break
  fi
  if ((SECONDS >= deadline)); then
    echo "reload가 ${RELOAD_WAIT}초 안에 끝나지 않음: 이전 설정으로 되돌리고 다시 읽음" >&2
    restore
    $NGINX_RELOAD || true
    exit 1
  fi
  sleep 0.5
done
echo "switched upstream: $target"
