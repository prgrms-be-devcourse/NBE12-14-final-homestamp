#!/usr/bin/env bash
# Let's Encrypt 인증서 갱신. systemd 타이머(homestamp-certbot-renew.timer)가 하루 한 번 부른다.
# certbot은 만료 30일 전부터만 실제로 갱신한다. 인증서가 바뀌었을 때만 nginx -t 뒤 reload한다.
# 실패하면 0이 아닌 값으로 끝나 systemd에 실패로 남는다(런북 "갱신 실패").
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
env_file="${1:-$here/homestamp.env}"
DOMAIN="$(grep -E '^DOMAIN=' "$env_file" | tail -n 1 | cut -d= -f2-)"
[[ "$DOMAIN" =~ ^[a-z0-9.-]+$ ]] || { echo "DOMAIN을 읽지 못했습니다" >&2; exit 2; }

CERTBOT_IMAGE="$(grep -E '^CERTBOT_IMAGE=' "$here/images.env" | cut -d= -f2-)"
chain="/etc/letsencrypt/live/$DOMAIN/fullchain.pem"
before="$(sha256sum "$chain" 2>/dev/null | cut -d' ' -f1 || true)"

docker run --rm --name homestamp-certbot \
  -v /etc/letsencrypt:/etc/letsencrypt \
  -v /var/lib/letsencrypt:/var/lib/letsencrypt \
  -v /var/www/certbot:/var/www/certbot \
  "$CERTBOT_IMAGE" renew --webroot -w /var/www/certbot --non-interactive --quiet

after="$(sha256sum "$chain" | cut -d' ' -f1)"
if [[ "$before" == "$after" ]]; then
  echo "renew: 바뀐 인증서 없음"
  exit 0
fi

docker exec homestamp-nginx nginx -t -q
docker exec homestamp-nginx nginx -s reload
echo "renew: 새 인증서로 nginx reload"
