#!/usr/bin/env bash
# 로컬 격리 시험: 운영과 같은 nginx 이미지·설정으로 HTTP 전용 → HTTPS(시험용 자체 CA) → 블루/그린 전환을 확인한다.
#   사용: infra/nginx/test/local-test.sh <빈 작업 폴더>
# 만드는 것: 작업 폴더 안의 파일, 이름이 hs-nginx-test-로 시작하는 컨테이너 3개와 네트워크 1개(끝나면 지운다).
# 다른 컨테이너(Preview 등)는 건드리지 않는다. 실제 인증서·AWS·DNS는 쓰지 않는다. 호스트에 docker·openssl 필요.
# 요청은 고정한 curl 컨테이너(OpenSSL)가 시험망 안에서 보낸다(macOS curl은 TLS 1.3을 못 함). 바깥 포트는 열지 않는다
set -euo pipefail

src="$(cd "$(dirname "$0")/.." && pwd)"
work="${1:?usage: local-test.sh <empty work dir>}"
[[ -d "$work" && -z "$(ls -A "$work")" ]] || { echo "빈 폴더를 주세요: $work" >&2; exit 2; }
work="$(cd "$work" && pwd)"

P=hs-nginx-test
NET="$P-net"
EDGE="$P-edge"
DOMAIN=homestamp.test
CURL_IMAGE=curlimages/curl:8.17.0@sha256:935d9100e9ba842cdb060de42472c7ca90cfe9a7c96e4dacb55e79e560b3ff40
# shellcheck disable=SC1091
source "$src/images.env"

pass=0
fail=0
ok() { echo "PASS $1"; pass=$((pass + 1)); }
ng() { echo "FAIL $1"; fail=$((fail + 1)); }
# 헤더 본문(CSP의 '…' 등)을 셸 문자열에 끼우지 않고 검사한다
has_all() { # <본문> <정규식...>
  local text="$1"; shift
  local re
  for re in "$@"; do grep -qiE "$re" <<<"$text" || return 1; done
}
check() { # <이름> <명령...>
  local name="$1"; shift
  if "$@"; then ok "$name"; else ng "$name"; fi
}

cleanup() {
  docker rm -f "$EDGE" "$P-blue" "$P-green" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

# ---------- 준비: 설정 복사, 가짜 화면, ACME 파일, 가짜 앱 ----------
cp -R "$src" "$work/nginx"
rm -rf "$work/nginx/run" "$work/nginx/test"
mkdir -p "$work/le" "$work/webroot/.well-known/acme-challenge" "$work/front/releases/v1/assets" "$work/front/assets"
echo "acme-ok" > "$work/webroot/.well-known/acme-challenge/token123"
echo "<html>homestamp-index</html>" > "$work/front/releases/v1/index.html"
# assets는 공용 폴더(front/assets)에서 나간다. 릴리스 안의 assets/는 내지 않는다(배포-화면.md)
echo "console.log(1)" > "$work/front/assets/app.js"
echo "console.log('release-copy')" > "$work/front/releases/v1/assets/only-in-release.js"
echo '{"revision":"v1"}' > "$work/front/releases/v1/version.json"
mkdir -p "$work/front/releases/v2" && echo "<html>v2</html>" > "$work/front/releases/v2/index.html"
ln -s releases/v1 "$work/front/current"
cat > "$work/nginx/homestamp.env" <<EOF
DOMAIN=$DOMAIN
PHOTOS_BUCKET=homestamp-photos
APP_UPSTREAM=$P-blue:8080
HSTS_MAX_AGE=
CERT_ROOT=$work/le
EOF

docker network create "$NET" >/dev/null
for color in blue green; do
  mkdir -p "$work/$color"
  cat > "$work/$color/default.conf" <<EOF
server {
  listen 8080;
  location / {
    default_type text/plain;
    # Spring Security 기본 헤더 흉내(nginx가 지우고 자기 값만 붙이는지 본다)
    add_header Cache-Control "no-cache, no-store, max-age=0, must-revalidate" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "DENY" always;
    add_header Strict-Transport-Security "max-age=31536000 ; includeSubDomains" always;
    # 앱이 확인 헤더와 같은 이름을 보내도 nginx가 지우는지 본다
    add_header X-Homestamp-Upstream "from-app" always;
    return 200 "$color xff=\$http_x_forwarded_for real=\$http_x_real_ip proto=\$http_x_forwarded_proto fwd=[\$http_forwarded] uri=\$request_uri\n";
  }
}
EOF
  docker run -d --name "$P-$color" --network "$NET" \
    -v "$work/$color/default.conf:/etc/nginx/conf.d/default.conf:ro" "$NGINX_IMAGE" >/dev/null
done

# ---------- 1. 인증서 없음: HTTP 전용 ----------
"$work/nginx/render.sh" "$work/nginx/homestamp.env"
check "render: 인증서 없으면 http 모드" grep -q "HTTPS 준비 중" "$work/nginx/run/conf.d/homestamp.conf"

# compose.yaml과 같은 보안 옵션(읽기 전용, tmpfs, 권한 최소화). 네트워크만 시험용 bridge
docker run -d --name "$EDGE" --network "$NET" \
  --read-only --tmpfs /tmp --tmpfs /var/cache/nginx \
  --security-opt no-new-privileges:true --cap-drop ALL \
  --cap-add NET_BIND_SERVICE --cap-add SETUID --cap-add SETGID --cap-add CHOWN \
  -e NGINX_ENTRYPOINT_QUIET_LOGS=1 \
  -v "$work/nginx/nginx.conf:/etc/nginx/nginx.conf:ro" \
  -v "$work/nginx/run/conf.d:/etc/nginx/conf.d:ro" \
  -v "$work/nginx/run/snippets:/etc/nginx/snippets:ro" \
  -v "$work/nginx/run/upstream:/etc/nginx/homestamp:ro" \
  -v "$work/le:/etc/letsencrypt:ro" \
  -v "$work/webroot:/var/www/certbot:ro" \
  -v "$work/front:/srv/homestamp/front:ro" \
  "$NGINX_IMAGE" >/dev/null
sleep 2
check "nginx -t (http 모드)" docker exec "$EDGE" nginx -t -q

IP="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$EDGE")"
H=80
S=443
mkdir -p "$work/ca"
# 시험망 안의 curl. bash -c 안에서도 쓰도록 내보낸다
curl() { docker run --rm -i --network "$NET" -v "$work/ca:/ca:ro" "$CURL_IMAGE" "$@"; }
export -f curl
export NET work CURL_IMAGE
R=(--resolve "$DOMAIN:$H:$IP" --resolve "$DOMAIN:$S:$IP" -s)

check "http: ACME 확인 파일 200" test "$(curl "${R[@]}" "http://$DOMAIN:$H/.well-known/acme-challenge/token123")" = "acme-ok"
check "http: 그 밖은 503" test "$(curl "${R[@]}" -o /dev/null -w '%{http_code}' "http://$DOMAIN:$H/")" = "503"
check "http: 모르는 Host는 응답 없이 끊음(444)" bash -c "! curl -s -o /dev/null -H 'Host: evil.example' http://$IP:$H/"
check "http: API는 열리지 않음" test "$(curl "${R[@]}" -o /dev/null -w '%{http_code}' "http://$DOMAIN:$H/api/x")" = "503"

# ---------- 2. 시험용 인증서 → HTTPS ----------
mkdir -p "$work/ca" "$work/le/live/$DOMAIN"
# 시험용 CA(2일). CA 확장을 넣어야 OpenSSL 3 클라이언트가 체인을 받는다
printf 'basicConstraints=critical,CA:TRUE\nkeyUsage=critical,keyCertSign,cRLSign\n' > "$work/ca/ca-ext.cnf"
openssl req -new -sha256 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -pkeyopt ec_param_enc:named_curve -nodes \
  -subj "/CN=hs-test-ca" -keyout "$work/ca/ca.key" -out "$work/ca/ca.csr" >/dev/null 2>&1
openssl x509 -req -sha256 -in "$work/ca/ca.csr" -signkey "$work/ca/ca.key" -days 2 \
  -extfile "$work/ca/ca-ext.cnf" -out "$work/ca/ca.pem" >/dev/null 2>&1
openssl req -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -pkeyopt ec_param_enc:named_curve -nodes -subj "/CN=$DOMAIN" \
  -keyout "$work/le/live/$DOMAIN/privkey.pem" -out "$work/ca/leaf.csr" >/dev/null 2>&1
printf 'subjectAltName=DNS:%s\nbasicConstraints=CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth\n' "$DOMAIN" > "$work/ca/ext.cnf"
openssl x509 -req -sha256 -in "$work/ca/leaf.csr" -CA "$work/ca/ca.pem" -CAkey "$work/ca/ca.key" -CAcreateserial \
  -days 2 -extfile "$work/ca/ext.cnf" -out "$work/ca/leaf.pem" >/dev/null 2>&1
cat "$work/ca/leaf.pem" "$work/ca/ca.pem" > "$work/le/live/$DOMAIN/fullchain.pem"
chmod 600 "$work/le/live/$DOMAIN/privkey.pem"
# 컨테이너 안에서는 /etc/letsencrypt 경로로 보인다
sed -i.bak "s#^CERT_ROOT=.*#CERT_ROOT=$work/le#" "$work/nginx/homestamp.env"
"$work/nginx/render.sh" "$work/nginx/homestamp.env"
check "render: 인증서 있으면 https 모드" grep -q "listen 443 ssl;" "$work/nginx/run/conf.d/homestamp.conf"
check "nginx -t (https 모드)" docker exec "$EDGE" nginx -t -q
docker exec "$EDGE" nginx -s reload; sleep 1

C=("${R[@]}" --cacert /ca/ca.pem)
check "http → https 301" test "$(curl "${C[@]}" -o /dev/null -w '%{http_code} %{redirect_url}' "http://$DOMAIN:$H/a?b=1")" = "301 https://$DOMAIN/a?b=1"
check "https 모드에서도 ACME 200(갱신용)" test "$(curl "${C[@]}" "http://$DOMAIN:$H/.well-known/acme-challenge/token123")" = "acme-ok"
check "https: 인증서 체인·이름 검증 통과" curl "${C[@]}" -o /dev/null "https://$DOMAIN:$S/"
check "https: 모르는 SNI는 핸드셰이크 거절" bash -c "! curl -sk --resolve other.test:$S:$IP -o /dev/null https://other.test:$S/"
check "TLS 1.2 접속" curl "${C[@]}" --tlsv1.2 --tls-max 1.2 -o /dev/null "https://$DOMAIN:$S/"
check "TLS 1.3 접속" bash -c "curl ${C[*]} --tlsv1.3 -v -o /dev/null https://$DOMAIN:$S/ 2>&1 | grep -q 'SSL connection using TLSv1.3'"
# 클라이언트 쪽 제한(SECLEVEL)을 풀어 TLS 1.1을 시도한다. 거절되면 서버가 막은 것
check "TLS 1.1 거절" bash -c "! curl ${C[*]} -k --tlsv1.1 --tls-max 1.1 --ciphers 'DEFAULT:@SECLEVEL=0' -o /dev/null https://$DOMAIN:$S/"

body="$(curl "${C[@]}" "https://$DOMAIN:$S/")"
check "화면: index.html" test "$body" = "<html>homestamp-index</html>"
check "화면: 없는 경로도 index.html(SPA)" test "$(curl "${C[@]}" "https://$DOMAIN:$S/repairs/12")" = "<html>homestamp-index</html>"
check "화면: /assets 오래 캐시" bash -c "curl ${C[*]} -D - -o /dev/null https://$DOMAIN:$S/assets/app.js | grep -qi 'cache-control: public, max-age=31536000, immutable'"
check "화면: 없는 /assets 파일은 404" test "$(curl "${C[@]}" -o /dev/null -w '%{http_code}' "https://$DOMAIN:$S/assets/nope.js")" = "404"
check "화면: /assets는 공용 폴더(릴리스 안의 assets는 안 나감)" test "$(curl "${C[@]}" -o /dev/null -w '%{http_code}' "https://$DOMAIN:$S/assets/only-in-release.js")" = "404"
check "화면: /assets JS 종류" bash -c "curl ${C[*]} -D - -o /dev/null https://$DOMAIN:$S/assets/app.js | grep -qiE 'content-type: (application|text)/javascript'"
check "화면: /version.json 내용·JSON" bash -c "test \"\$(curl ${C[*]} https://$DOMAIN:$S/version.json)\" = '{\"revision\":\"v1\"}' && curl ${C[*]} -D - -o /dev/null https://$DOMAIN:$S/version.json | grep -qi '^content-type: application/json'"
vh="$(curl "${C[@]}" -D - -o /dev/null "https://$DOMAIN:$S/version.json")"
check "화면: /version.json no-store 한 번, 보안 헤더" bash -c "test \"\$(grep -ci '^cache-control:' <<<'$vh')-\$(grep -ci '^cache-control: no-store' <<<'$vh')\" = 1-1 && grep -qi '^x-content-type-options: nosniff' <<<'$vh'"
ln -sfn releases/v2 "$work/front/current.tmp" && mv -T "$work/front/current.tmp" "$work/front/current" 2>/dev/null || { rm -f "$work/front/current"; ln -s releases/v2 "$work/front/current"; }
check "화면: current를 바꾸면 reload 없이 새 판" test "$(curl "${C[@]}" "https://$DOMAIN:$S/")" = "<html>v2</html>"
check "화면: version.json이 없으면 404(index.html로 대신 답하지 않음)" test "$(curl "${C[@]}" -o /dev/null -w '%{http_code}' "https://$DOMAIN:$S/version.json")" = "404"
check "화면: 판을 바꿔도 공용 assets 그대로" test "$(curl "${C[@]}" "https://$DOMAIN:$S/assets/app.js")" = "console.log(1)"
rm -f "$work/front/current" && ln -s releases/v1 "$work/front/current"
check "막기: /actuator 404" test "$(curl "${C[@]}" -o /dev/null -w '%{http_code}' "https://$DOMAIN:$S/actuator/health")" = "404"
check "막기: /api/../actuator 우회 안 됨" test "$(curl "${C[@]}" --path-as-is -o /dev/null -w '%{http_code}' "https://$DOMAIN:$S/api/../actuator/health")" = "404"
check "막기: 점 파일 404" test "$(curl "${C[@]}" -o /dev/null -w '%{http_code}' "https://$DOMAIN:$S/.env")" = "404"

hdr="$(curl "${C[@]}" -D - -o /dev/null "https://$DOMAIN:$S/")"
check "헤더: nosniff" grep -qi 'x-content-type-options: nosniff' <<<"$hdr"
check "헤더: Referrer-Policy 기본" grep -qi 'referrer-policy: strict-origin-when-cross-origin' <<<"$hdr"
check "헤더: CSP Report-Only + 사진 버킷" grep -qi 'content-security-policy-report-only: .*homestamp-photos.s3.ap-northeast-2.amazonaws.com' <<<"$hdr"
check "헤더: HSTS 기본 꺼짐" bash -c "! grep -qi strict-transport-security <<<'$hdr'"
check "헤더: 서버 버전 숨김" bash -c "! grep -qiE '^server: nginx/' <<<'$hdr'"
check "링크 화면 /l: no-referrer" bash -c "curl ${C[*]} -D - -o /dev/null https://$DOMAIN:$S/l | grep -qi 'referrer-policy: no-referrer'"

# ---------- 3. API 프록시 ----------
api="$(curl "${C[@]}" -H 'X-Forwarded-For: 6.6.6.6' -H 'X-Real-IP: 6.6.6.6' -H 'Forwarded: for=6.6.6.6' -H 'X-Forwarded-Proto: http' "https://$DOMAIN:$S/api/auth/login?x=1")"
check "API: blue로 감" grep -q '^blue ' <<<"$api"
check "API: 경로 그대로(/api/...)" grep -q 'uri=/api/auth/login?x=1' <<<"$api"
check "API: 가짜 X-Forwarded-For·X-Real-IP·Forwarded 덮어씀" bash -c "! grep -q '6.6.6.6' <<<'$api'"
check "API: proto=https" grep -q 'proto=https' <<<"$api"
check "API: 응답 Cache-Control no-store" bash -c "curl ${C[*]} -D - -o /dev/null https://$DOMAIN:$S/api/x | grep -qi 'cache-control: no-store'"
ah="$(curl "${C[@]}" -D - -o /dev/null "https://$DOMAIN:$S/api/x")"
check "API 헤더: Cache-Control 한 번(no-store)" test "$(grep -ci '^cache-control:' <<<"$ah")-$(grep -ci '^cache-control: no-store' <<<"$ah")" = "1-1"
check "API 헤더: nosniff 한 번" test "$(grep -ci '^x-content-type-options:' <<<"$ah")" = "1"
check "API 헤더: 밖의 요청에는 X-Homestamp-Upstream 없음(앱이 보낸 값도 지움)" test "$(grep -ci '^x-homestamp-upstream:' <<<"$ah")" = "0"
check "API 헤더: X-Frame-Options 한 번" test "$(grep -ci '^x-frame-options:' <<<"$ah")" = "1"
check "API 헤더: 앱의 HSTS는 지움(기본 꺼짐)" test "$(grep -ci '^strict-transport-security:' <<<"$ah")" = "0"
check "API 헤더: Referrer-Policy·CSP 있음" has_all "$ah" '^referrer-policy: strict-origin' '^content-security-policy-report-only:'
check "API: 1MB 넘는 본문 413" test "$(head -c 1100000 /dev/zero | curl "${C[@]}" -o /dev/null -w '%{http_code}' --data-binary @- "https://$DOMAIN:$S/api/x")" = "413"

# ---------- 4. 블루/그린 전환 ----------
export NGINX_TEST="docker exec $EDGE nginx -t -q" NGINX_RELOAD="docker exec $EDGE nginx -s reload"
export NGINX_WORKERS="docker exec $EDGE sh -c 'pgrep -P \"\$(cat /tmp/nginx.pid)\"'"
"$work/nginx/switch-upstream.sh" "$P-green:8080" >/dev/null
# 고정 sleep 없이: switch-upstream.sh가 이전 작업 프로세스가 끝나기를 기다렸으므로 바로 새 연결이 green으로 간다
check "전환: 끝나자마자 green으로" grep -q '^green ' <<<"$(curl "${C[@]}" "https://$DOMAIN:$S/api/x")"
check "RELOAD_WAIT 형식 오류: 2로 끝남" bash -c "RELOAD_WAIT=0 '$work/nginx/switch-upstream.sh' $P-blue:8080 >/dev/null 2>&1; test \$? -eq 2"
check "RELOAD_WAIT 형식 오류 뒤: 파일이 green 그대로(바꾸기 전에 검사)" grep -q "$P-green:8080" "$work/nginx/run/upstream/upstream.conf"
check "전환 실패(없는 주소): 0이 아닌 종료" bash -c "! '$work/nginx/switch-upstream.sh' nohost-xyz:8080 >/dev/null 2>&1"
check "전환 실패 뒤: 파일이 green 그대로" grep -q "$P-green:8080" "$work/nginx/run/upstream/upstream.conf"
check "전환 실패 뒤: 트래픽 green 그대로" grep -q '^green ' <<<"$(curl "${C[@]}" "https://$DOMAIN:$S/api/x")"
check "reload 실패: 되돌리고 0이 아닌 종료" bash -c "! NGINX_RELOAD=false '$work/nginx/switch-upstream.sh' $P-blue:8080 >/dev/null 2>&1"
check "reload 실패 뒤: 파일이 green 그대로" grep -q "$P-green:8080" "$work/nginx/run/upstream/upstream.conf"
check "형식 아닌 주소 거절(설정 주입)" bash -c "! '$work/nginx/switch-upstream.sh' 'x:80; } server { listen 9' >/dev/null 2>&1"
check "render는 현재 앱 주소를 되돌리지 않음" bash -c "'$work/nginx/render.sh' '$work/nginx/homestamp.env' >/dev/null && grep -q '$P-green:8080' '$work/nginx/run/upstream/upstream.conf'"

docker stop "$P-green" >/dev/null
code_ct="$(curl "${C[@]}" -o /dev/null -w '%{http_code} %{content_type}' "https://$DOMAIN:$S/api/x")"
check "앱 꺼짐: 503 problem+json" test "$code_ct" = "503 application/problem+json"
dh="$(curl "${C[@]}" -D - -o /dev/null "https://$DOMAIN:$S/api/x")"
check "앱 꺼짐: 503에도 보안 헤더" has_all "$dh" '^x-content-type-options: nosniff' '^referrer-policy:' '^cache-control: no-store'
docker start "$P-green" >/dev/null

# ---------- 5. HSTS 켜기, 입력 검사 ----------
sed -i.bak 's/^HSTS_MAX_AGE=.*/HSTS_MAX_AGE=300/' "$work/nginx/homestamp.env"
"$work/nginx/render.sh" "$work/nginx/homestamp.env" >/dev/null
docker exec "$EDGE" nginx -t -q && docker exec "$EDGE" nginx -s reload; sleep 1
check "HSTS 켜면 max-age=300" bash -c "curl ${C[*]} -D - -o /dev/null https://$DOMAIN:$S/ | grep -qi 'strict-transport-security: max-age=300'"
check "HSTS 켜면 API도 nginx 값 하나만" test "$(curl "${C[@]}" -D - -o /dev/null "https://$DOMAIN:$S/api/x" | grep -ci '^strict-transport-security: max-age=300')" = "1"
printf 'DOMAIN=a;b.test\nPHOTOS_BUCKET=homestamp-photos\n' > "$work/bad.env"
check "render: 잘못된 DOMAIN 거절" bash -c "! '$work/nginx/render.sh' '$work/bad.env' >/dev/null 2>&1"

echo "----"
echo "passed=$pass failed=$fail"
[[ $fail -eq 0 ]]
