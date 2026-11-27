#!/usr/bin/env bash
# nginx 설정을 만든다: 인증서가 있으면 HTTPS, 없으면 HTTP 전용(ACME 확인만).
#   사용: render.sh <env 파일>      (예: /opt/homestamp/nginx/homestamp.env)
# 바꾸는 곳은 이 폴더의 run/ 뿐이다. nginx를 다시 읽게 하는 일은 하지 않는다(런북에서 nginx -t 뒤 reload)
#
# env 파일 값
#   DOMAIN          서비스 도메인(예: homestamp.cloud). 개인 계정으로 옮기면 이 값만 바꾼다
#   PHOTOS_BUCKET   사진 버킷 이름(CSP의 S3 주소)
#   APP_UPSTREAM    run/upstream/upstream.conf가 없을 때 처음 가리킬 앱 주소(host:port). 있으면 건드리지 않는다
#   HSTS_MAX_AGE    비우면 HSTS를 보내지 않는다. 숫자면 그 초만큼(런북 "HSTS 켜기")
#   CERT_ROOT       인증서 위치(기본 /etc/letsencrypt). 로컬 시험에서만 바꾼다
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
env_file="${1:?usage: render.sh <env file>}"

# env 파일은 KEY=value 줄만 읽는다(셸로 실행하지 않는다)
read_value() {
  local key="$1" line
  line="$(grep -E "^${key}=" "$env_file" | tail -n 1 || true)"
  printf '%s' "${line#*=}"
}

DOMAIN="$(read_value DOMAIN)"
PHOTOS_BUCKET="$(read_value PHOTOS_BUCKET)"
APP_UPSTREAM="$(read_value APP_UPSTREAM)"
HSTS_MAX_AGE="$(read_value HSTS_MAX_AGE)"
CERT_ROOT="$(read_value CERT_ROOT)"
CERT_ROOT="${CERT_ROOT:-/etc/letsencrypt}"

# 설정 파일에 그대로 들어가므로 모양을 좁게 검사한다(세미콜론·공백·따옴표가 들어가지 못하게)
[[ "$DOMAIN" =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$ ]] \
  || { echo "DOMAIN 형식이 아닙니다: '$DOMAIN'" >&2; exit 2; }
[[ "$PHOTOS_BUCKET" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] \
  || { echo "PHOTOS_BUCKET 형식이 아닙니다: '$PHOTOS_BUCKET'" >&2; exit 2; }
[[ -z "$HSTS_MAX_AGE" || "$HSTS_MAX_AGE" =~ ^[0-9]{1,9}$ ]] \
  || { echo "HSTS_MAX_AGE는 비우거나 숫자여야 합니다" >&2; exit 2; }

run="$here/run"
mkdir -p "$run/conf.d" "$run/snippets" "$run/upstream"

if [[ -f "$CERT_ROOT/live/$DOMAIN/fullchain.pem" && -f "$CERT_ROOT/live/$DOMAIN/privkey.pem" ]]; then
  mode=https
else
  mode=http
fi

render() { # <원본> <결과>: 같은 폴더의 임시 파일에 쓰고 한 번에 바꾼다
  local tmp
  tmp="$(mktemp "$2.XXXXXX")"
  sed -e "s/__DOMAIN__/$DOMAIN/g" -e "s/__PHOTOS_BUCKET__/$PHOTOS_BUCKET/g" "$1" > "$tmp"
  chmod 644 "$tmp"
  mv -f "$tmp" "$2"
}

render "$here/templates/homestamp-$mode.conf.template" "$run/conf.d/homestamp.conf"
for f in "$here"/snippets/*.conf; do
  render "$f" "$run/snippets/$(basename "$f")"
done

hsts_tmp="$(mktemp "$run/snippets/hsts.inc.XXXXXX")"
if [[ -n "$HSTS_MAX_AGE" ]]; then
  printf 'add_header Strict-Transport-Security "max-age=%s" always;\n' "$HSTS_MAX_AGE" > "$hsts_tmp"
else
  printf '# HSTS 꺼짐(HSTS_MAX_AGE 비어 있음)\n' > "$hsts_tmp"
fi
chmod 644 "$hsts_tmp"
mv -f "$hsts_tmp" "$run/snippets/hsts.inc"

# 앱 주소는 처음 한 번만 만든다. 이후에는 switch-upstream.sh만 바꾼다(배포 중 render가 되돌리지 않게)
if [[ ! -f "$run/upstream/upstream.conf" ]]; then
  "$here/switch-upstream.sh" --write-only "${APP_UPSTREAM:?APP_UPSTREAM이 필요합니다(처음 한 번)}"
fi

echo "rendered mode=$mode domain=$DOMAIN hsts=${HSTS_MAX_AGE:-off}"
