# HTTPS 입구(nginx + Let's Encrypt) 런북

앱 EC2(`homestamp-web`)의 입구 nginx를 설치하고, Let's Encrypt 인증서를 처음 받고, 자동 갱신과 블루/그린 전환에 연결하는 절차다. 설정 원본은 `infra/nginx/`(04 3-2 "입구는 nginx 하나").

- 실행자: 운영 담당. EC2에는 관리 역할의 셸(`homestamp-shell` 문서)로 들어간다(04 8장). 들어갈 때마다 대장에 적는다
- 이 문서는 아직 실제 EC2에서 돌려 보지 않았다. 로컬 격리 시험(`infra/nginx/test/local-test.sh`)만 통과했다

```
브라우저 ─80/443─▶ nginx(컨테이너, network_mode: host)
                    ├─ /.well-known/acme-challenge/  → /var/www/certbot (80)
                    ├─ /api/                          → upstream homestamp_app (127.0.0.1:<blue|green>)
                    └─ 그 밖                          → /srv/homestamp/front/current (React)
certbot(컨테이너) ── 하루 한 번 systemd 타이머 ── renew → 바뀌면 nginx -t → reload
```

## 파일과 위치 (EC2)

| 위치 | 무엇 | 저장소 |
|---|---|---|
| `/opt/homestamp/nginx/` | `infra/nginx/`를 그대로 복사 | 원본 |
| `/opt/homestamp/nginx/homestamp.env` | 도메인·사진 버킷·첫 앱 주소·HSTS(비밀값 없음) | `homestamp.env.example`만 |
| `/opt/homestamp/nginx/run/` | `render.sh`·`switch-upstream.sh`가 만드는 실행 설정 | 올리지 않음 |
| `/etc/letsencrypt/` | 인증서와 키(certbot 기본 권한: `archive/`·`live/` 0700 root, 키 0600) | **절대 올리지 않음** |
| `/var/www/certbot/` | ACME 확인 파일(잠깐 생겼다 지워짐) | — |
| `/srv/homestamp/front/current` | React 빌드를 가리키는 링크(`releases/<버전>`) | 배포가 만든다 |

nginx 컨테이너는 읽기 전용 루트, `no-new-privileges`, 권한은 `NET_BIND_SERVICE·SETUID·SETGID·CHOWN`만 둔다. 인증서 폴더는 읽기 전용으로 묶는다. 접속 기록은 경로만(쿼리 문자열·Referer 없음) 표준 출력으로 내보내 Docker 로그 회전(10MB×3)을 따른다.

## 0. 시작 전 확인 (차단 조건)

- [ ] 도메인과 IP: `terraform output web_public_ip`(인프라 담당)와 `dig +short A homestamp.cloud`가 같다. IPv6(AAAA) 레코드는 두지 않는다(EC2에 IPv6 없음). DNS 변경은 등록 업체에서 인프라 담당이 한다
- [ ] 보안 그룹: 들어오는 80·443이 열려 있고(ACME 확인은 80), 나가는 443이 열려 있다(certbot → Let's Encrypt). 앱 포트·9091/9092는 바깥에 열지 않는다(`security_groups.tf`)
- [ ] EC2에 Docker Compose 플러그인이 있다: `docker compose version`. Amazon Linux 2023의 `docker` 패키지에 들어 있는지는 확인하지 못했다(EC2에서 `dnf info docker`로 확인). 없으면 Docker 공식 릴리스의 정해진 판을 받아 **체크섬을 확인한 뒤** `/usr/local/lib/docker/cli-plugins/docker-compose`에 둔다(판과 체크섬은 설치할 때 이 런북에 적는다)
- [ ] 앱이 `127.0.0.1:<포트>`에만 묶여 있다(바깥 IP로 묶지 않는다). 첫 앱 주소는 `homestamp.env`의 `APP_UPSTREAM`

## 1. 설치 (인증서 없음 → HTTP 전용)

```sh
sudo mkdir -p /opt/homestamp /etc/letsencrypt /var/lib/letsencrypt /var/www/certbot /srv/homestamp/front
sudo cp -R <저장소>/infra/nginx /opt/homestamp/nginx
cd /opt/homestamp/nginx
sudo cp homestamp.env.example homestamp.env      # DOMAIN·PHOTOS_BUCKET 확인, HSTS_MAX_AGE는 비워 둔다
sudo ./render.sh homestamp.env                   # rendered mode=http 가 나와야 한다
sudo docker compose --env-file images.env up -d
sudo docker exec homestamp-nginx nginx -t
curl -sI http://homestamp.cloud/ | head -1        # 503 (HTTPS 준비 중)
```

인증서가 없을 때는 ACME 확인 파일만 내주고 나머지는 503이다. 우리 도메인이 아닌 Host(IP로 직접 등)는 응답 없이 끊는다.

## 2. 첫 인증서 받기

먼저 **시험 서버로 연습**한다(Let's Encrypt 발급 횟수 제한에 걸리지 않게). 이메일은 선택이다(Let's Encrypt는 2025-06-04에 만료 안내 메일을 끝냈다. 만료 확인은 5단계).
```sh
cd /opt/homestamp/nginx && source images.env
sudo docker run --rm -v /etc/letsencrypt:/etc/letsencrypt -v /var/lib/letsencrypt:/var/lib/letsencrypt \
  -v /var/www/certbot:/var/www/certbot "$CERTBOT_IMAGE" certonly --webroot -w /var/www/certbot \
  -d homestamp.cloud --agree-tos --register-unsafely-without-email --non-interactive --dry-run
```
연습이 성공하면 같은 명령에서 `--dry-run`만 빼고 실제로 받는다. 받은 뒤:
```sh
sudo ./render.sh homestamp.env                   # rendered mode=https
sudo docker exec homestamp-nginx nginx -t && sudo docker exec homestamp-nginx nginx -s reload
```

## 3. HTTPS 확인

```sh
curl -sI http://homestamp.cloud/x | grep -iE '^(HTTP|location)'        # 301 → https://homestamp.cloud/x
curl -sI https://homestamp.cloud/ | grep -iE '^(HTTP|x-content-type|referrer|content-security)'
echo | openssl s_client -connect homestamp.cloud:443 -servername homestamp.cloud 2>/dev/null \
  | openssl x509 -noout -subject -issuer -enddate                       # CN·발급자(Let's Encrypt)·만료일
echo | openssl s_client -connect homestamp.cloud:443 -servername homestamp.cloud -tls1_1 2>&1 | grep -c 'alert'   # 1.1은 거절
curl -s -o /dev/null -w '%{http_code}\n' https://homestamp.cloud/actuator/health      # 404
```
- 외부 검사: SSL Labs 같은 공개 검사기로 체인·프로토콜을 한 번 확인한다(결과 등급만 대장에)

## 4. 자동 갱신 켜기

```sh
sudo cp systemd/homestamp-certbot-renew.service systemd/homestamp-certbot-renew.timer /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now homestamp-certbot-renew.timer
systemctl list-timers homestamp-certbot-renew.timer
sudo /opt/homestamp/nginx/renew.sh /opt/homestamp/nginx/homestamp.env   # 지금은 "바뀐 인증서 없음"
```
- 타이머는 매일 03:00 UTC(12:00 KST, 자동 일정으로 켜진 시간)에 돌고, 서버가 꺼져 놓치면 켜질 때 따라잡는다(`Persistent=true`). 갱신할지는 certbot이 만료일을 보고 정한다. Let's Encrypt는 인증서 수명을 줄인다(기본이 2027-02-10부터 64일, 2028-02-16부터 45일, Let's Encrypt 공지 "Decreasing Certificate Lifetimes to 45 Days"). 매일 도는 타이머는 그대로 쓰되, 그 전에 certbot을 올리며 갱신 시점을 다시 확인한다. 인증서가 바뀌었을 때만 `nginx -t` 뒤 reload한다
- 갱신 경로 연습: `certonly` 대신 `renew --dry-run`(2단계 명령과 같은 볼륨)

## 5. 만료 확인

- 주 1회(또는 배포 전): 3단계의 `openssl ... -enddate`. 남은 날이 20일보다 적으면 갱신이 실패하고 있는 것이다 → 6단계
- `sudo docker run --rm -v /etc/letsencrypt:/etc/letsencrypt "$CERTBOT_IMAGE" certificates`로 만료일과 경로를 본다
- 만료 감시를 모니터링(Grafana 알림)에 넣는 것은 배포 후 개선

## 6. 갱신 실패와 장애 복구

| 증상 | 확인 | 조치 |
|---|---|---|
| 타이머 실패 | `systemctl status homestamp-certbot-renew.service`, `journalctl -u homestamp-certbot-renew.service -n 50` | 아래 원인 확인 뒤 `renew.sh` 다시 실행 |
| ACME 확인 실패(404·timeout) | `curl http://homestamp.cloud/.well-known/acme-challenge/x`가 nginx의 404인지, DNS가 EIP인지, 보안 그룹 80 | DNS·보안 그룹 바로잡기. nginx가 http 모드인지 https 모드인지와 상관없이 80의 이 경로는 열려 있다 |
| 발급 횟수 제한 | certbot 오류 문장 | 연습은 `--dry-run`으로만. 제한이 풀릴 때까지 기다린다 |
| reload 뒤 nginx가 안 뜸 | `docker logs homestamp-nginx`, `docker exec homestamp-nginx nginx -t` | 설정 오류면 저장소의 이전 판으로 `render.sh` 다시. 인증서 파일 문제면 `certbot certificates` |
| 인증서가 만료됨 | 3단계 만료일 | `renew.sh`를 손으로 실행(만료된 인증서도 갱신된다). 그동안 HTTPS 접속이 경고로 막힌다 |
| EC2가 오래 꺼져 있었음 | 켜진 뒤 `list-timers`의 마지막 실행 | `Persistent=true`가 켜질 때 따라잡는다. 꺼진 동안 만료되면 켜진 직후 위 "만료됨"과 같다 |

nginx가 죽어도 앱 컨테이너는 그대로다. `docker compose --env-file images.env up -d`로 다시 띄운다(`restart: unless-stopped`라 EC2가 켜질 때도 자동으로 뜬다).

## 7. 블루/그린과의 연결

배포 스크립트(아직 없음, 04 9장)의 마지막 단계만 이 폴더를 쓴다.
```
1. --migrate         0이 아니면 멈춤(새 앱으로 바꾸지 않음)
2. --apply-grants    0이 아니면 멈춤
3. green 시작        127.0.0.1:<green 앱 포트>, 관리 포트 9092
4. 헬스 체크         관리 포트의 /actuator/health, 120초
5. 전환              /opt/homestamp/nginx/switch-upstream.sh 127.0.0.1:<green 앱 포트>
6. blue 종료
```
- `switch-upstream.sh`는 주소 모양을 검사하고 `nginx -t`가 통과할 때만 reload한다. 검사나 reload가 실패하면 이전 주소로 되돌리고 0이 아닌 값으로 끝난다 → 배포 스크립트는 green을 지우고 blue를 그대로 둔다
- 1~4 중 하나라도 실패하면 5를 부르지 않는다. **앞 단계에서 적용된 DB 변경(Migration)은 되돌리지 않는다**(스키마는 이전 앱과도 맞게, 04 8장)
- 앱 포트는 blue `127.0.0.1:8081`·green `127.0.0.1:8082`다(관리 포트 9091·9092와 짝, decisions 2026-11-25). 확정(#205)
- `switch-upstream.sh` 계약: 인자 `<host:port>` 하나(`--write-only`는 첫 설치용), 종료 0 성공·1 검사나 reload 실패(이전 주소로 되돌림, 처음이라 이전 파일이 없으면 되돌릴 것 없이 1)·2 주소 형식 오류(파일을 바꾸지 않음, 인자가 없으면 1). 지금 앱은 `run/upstream/upstream.conf`의 `server` 줄로 판단한다. 헬스는 다시 보지 않는다
- 화면(React)은 배포 스크립트가 `/srv/homestamp/front/releases/<버전>`에 풀고 `current` 링크만 바꾼다. nginx reload는 필요 없다. **아직 이 단계를 맡은 곳이 없다**(#205 `deploy.sh`·#206 `deploy.yml`에 프론트 빌드·풀기가 없음). 프론트는 백엔드와 따로 정적 파일 이미지(GHCR)로 발행하고 전용 배포 스크립트가 이 경로에 푸는 방식으로 정했고, 후속 PR `feat/common/front-image`·`feat/common/front-deploy`가 맡는다. 그 전에는 사용자 공개 배포를 하지 않는다
- 요청 제한(04 3-2 `limit_req`: 로그인·인증 문자·링크 조회)은 이 PR에 넣지 않았다. 경로와 기준을 정하는 후속 작업으로 둔다. 로그인·인증 문자의 횟수 제한은 앱이 따로 한다(`RATE_LIMITED`·`DAILY_LIMIT`). 링크 조회의 IP 제한이 비어 있다

## 8. HSTS 켜기 (HTTPS가 잘 도는 것을 확인한 뒤)

처음부터 켜지 않는다. 인증서 갱신이 한 번 이상 성공하고 HTTPS 접속에 문제가 없을 때:
1. `homestamp.env`의 `HSTS_MAX_AGE=300`(5분) → `render.sh` → `nginx -t` → reload → 며칠 확인
2. 문제가 없으면 `HSTS_MAX_AGE=31536000`(1년). `includeSubDomains`·`preload`는 쓰지 않는다(다른 하위 도메인이 생길 수 있고 되돌리기 어렵다)
- 끄려면 값을 비우고 render·reload. 이미 받은 브라우저는 max-age 동안 기억한다

## 9. 개인 계정으로 옮길 때

- 같은 파일을 그대로 쓴다. `homestamp.env`의 `DOMAIN`·`PHOTOS_BUCKET`만 바꾼다(`render.sh`가 형식을 검사)
- 새 도메인의 A 레코드를 새 EIP로 → 1~4단계를 처음부터. **인증서와 키는 옮기지 않고 새로 받는다**
- 교육 계정의 `/etc/letsencrypt`는 정리할 때 EC2와 함께 지운다

## 시험 (로컬, AWS 없음)

```sh
mkdir -p /tmp/hs-nginx-work && infra/nginx/test/local-test.sh /tmp/hs-nginx-work   # 빈 폴더를 준다
```
운영과 같은 nginx 이미지·설정·컨테이너 보안 옵션으로, 시험용 자체 CA 인증서를 써서 확인한다(52개): HTTP 전용 모드·ACME·모르는 Host 끊기, HTTPS 체인·이름·TLS 1.2/1.3·1.1 거절·모르는 SNI 거절, 화면·SPA·캐시, Actuator·점 파일 막기, 보안 헤더·HSTS 기본 꺼짐·`/api/`에서 앱 헤더 중복 없음(가짜 앱이 Spring Security 기본 헤더를 낸다)·503에도 보안 헤더, API 경로·가짜 X-Forwarded-* 덮어쓰기·413, 블루/그린 전환·실패 시 유지, 앱 꺼짐 503. 실제 Let's Encrypt·DNS·EC2·systemd는 시험하지 않는다.
