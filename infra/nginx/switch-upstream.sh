#!/usr/bin/env bash
# nginx가 보내는 앱을 바꾼다(블루/그린의 마지막 단계, 04 3-2·9장).
#   사용: switch-upstream.sh <host:port>               바꾸고 nginx -t → reload. 실패하면 이전으로 되돌린다
#         switch-upstream.sh --write-only <host:port>  파일만 만든다(render.sh의 첫 설치용)
#
# 부르는 쪽(배포 스크립트)의 약속: --migrate·--apply-grants가 둘 다 0으로 끝나고, 새 앱의 헬스 체크가
# 통과한 뒤에만 부른다. 이 스크립트는 헬스를 다시 보지 않는다. 0이 아니면 배포 스크립트는 새 앱을 지우고
# 이전 앱을 그대로 둔다. DB 변경(앞 단계 Migration)은 되돌리지 않는다.
#
# 환경 변수(로컬 시험에서만 바꾼다)
#   NGINX_TEST    설정 검사 명령(기본: docker exec homestamp-nginx nginx -t -q)
#   NGINX_RELOAD  다시 읽기 명령(기본: docker exec homestamp-nginx nginx -s reload)
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
[[ -f "$conf" ]] && cp -p "$conf" "$prev"
mv -f "$tmp" "$conf"

NGINX_TEST="${NGINX_TEST:-docker exec homestamp-nginx nginx -t -q}"
NGINX_RELOAD="${NGINX_RELOAD:-docker exec homestamp-nginx nginx -s reload}"

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
if ! $NGINX_RELOAD; then
  echo "reload 실패: 이전 설정으로 되돌리고 다시 읽음" >&2
  restore
  $NGINX_RELOAD || true
  exit 1
fi
echo "switched upstream: $target"
