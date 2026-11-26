#!/usr/bin/env bash
# homestamp-deploy 문서의 명령(infra/terraform/ssm/deploy-command.sh.tftpl) 시험.
# Linux 컨테이너 안에서 root로, 가짜 /opt/homestamp/deploy/deploy.sh를 두고 돌린다(AWS·EC2 없음).
#   .github/scripts/test/deploy-command-test.sh            (호스트: Docker 필요)
set -uo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd)"
IMAGE=debian:bookworm-slim@sha256:e67eeead736ff7bbe258ef088bff683635c561b1fcfefad0806c1df4ab49ce0e
if [[ "${1:-}" != --inside ]]; then
  exec docker run --rm --network none -v "$root:/src:ro" "$IMAGE" bash /src/.github/scripts/test/deploy-command-test.sh --inside
fi

tpl=/src/infra/terraform/ssm/deploy-command.sh.tftpl
passed=0; failed=0
ok() { passed=$((passed + 1)); echo "ok   $1"; }
bad() { failed=$((failed + 1)); echo "FAIL $1"; }
D=sha256:$(printf 'b%.0s' $(seq 1 64))

grep -q '{{' "$tpl" && bad "명령 본문에 {{ }} 치환 없음" || ok "명령 본문에 {{ }} 치환 없음"
[[ $(grep -o '\${[a-z_0-9]*}' "$tpl" | sort -u) == '${expected_sha256}' ]] && ok "템플릿 변수는 expected_sha256 하나" || bad "템플릿 변수"

setup() { # 정상 설치 상태
  rm -rf /opt/homestamp /tmp/pwned /tmp/ran /tmp/got-term
  mkdir -p /opt/homestamp/deploy && chmod 0755 /opt/homestamp /opt/homestamp/deploy
  cat >/opt/homestamp/deploy/deploy.sh <<'FAKE'
#!/bin/bash
echo "args: $*" > /tmp/ran
trap 'echo term >/tmp/got-term; echo "끝: result=interrupted exit=143"; exit 143' TERM
if [[ -n ${FAKE_BIG:-} ]]; then head -c 60000 /dev/zero | tr '\0' 'x'; echo; fi
if [[ -n ${FAKE_SLEEP:-} ]]; then sleep "$FAKE_SLEEP" & wait $!; fi
echo "끝: result=${FAKE_RESULT:-success} exit=${FAKE_EXIT:-0}"
exit "${FAKE_EXIT:-0}"
FAKE
  chmod 0750 /opt/homestamp/deploy/deploy.sh
  printf 'IMAGE_REPOSITORY=ghcr.io/example/app\nBLUE_PORT=8081\n' >/opt/homestamp/deploy/deploy.env
  chmod 0640 /opt/homestamp/deploy/deploy.env
  SHA="$(sha256sum /opt/homestamp/deploy/deploy.sh | cut -d' ' -f1)"
}
render() { sed "s/\${expected_sha256}/$1/" "$tpl" >/tmp/cmd.sh; }
run() { # 이름 기대코드 digest
  SSM_Digest="$3" bash /tmp/cmd.sh >/tmp/out 2>&1
  local rc=$?
  if [[ $rc == "$2" ]]; then ok "$1 ($rc)"; else bad "$1 (기대 $2, 실제 $rc)"; cat /tmp/out; fi
}

setup; render "$SHA"
run "정상" 0 "$D"
[[ "$(cat /tmp/ran)" == "args: --config /opt/homestamp/deploy/deploy.env ghcr.io/example/app@$D" ]] && ok "deploy.sh에 넘긴 인자" || bad "인자: $(cat /tmp/ran)"
setup; render "$SHA"; FAKE_EXIT=14 run "deploy.sh 종료 코드 전파" 14 "$D"
setup; render "$SHA"; FAKE_EXIT=17 run "종료 17 전파" 17 "$D"
setup; render "$SHA"; FAKE_BIG=1 FAKE_EXIT=11 run "긴 출력에서도 종료 코드 전파" 11 "$D"
size="$(wc -c </tmp/out)"; ((size <= 20200)) && ok "출력은 문서 머리줄 + deploy.sh 끝 20,000바이트 이하($size)" || bad "출력 크기 $size"
tail -n 1 /tmp/out | grep -q '끝: result=success exit=11' && ok "마지막 결과 줄이 남음(SSM 24,000자 제한에 잘리지 않음)" || bad "결과 줄: $(tail -n 1 /tmp/out | cut -c1-80)"
setup; render "$SHA"
FAKE_SLEEP=30 SSM_Digest="$D" bash /tmp/cmd.sh >/tmp/out 2>&1 &
p=$!; sleep 2; kill -TERM $p; wait $p; rc=$?
[[ $rc == 143 && -e /tmp/got-term ]] && ok "SSM의 TERM을 deploy.sh에 넘기고 그 코드(143)로 끝남" || bad "신호 전달(rc=$rc, term=$([[ -e /tmp/got-term ]] && echo y || echo n))"
grep -q 'result=interrupted' /tmp/out && ok "신호로 멈춰도 deploy.sh 출력이 남음" || bad "신호 뒤 출력"
grep -q 'args: --config' /tmp/ran && grep -q '신호로 멈춤' /tmp/out && ok "신호를 받자마자 그때까지의 출력을 냄" || bad "신호 직후 출력"

for p in "$D;touch /tmp/pwned" "\$(touch /tmp/pwned)" "$D\$(touch /tmp/pwned)" "$D\`touch /tmp/pwned\`" "$D"$'\n'"touch /tmp/pwned" "" "$D x"; do
  setup; render "$SHA"
  run "주입 거절: $(printf '%q' "$p" | cut -c1-40)" 2 "$p"
  [[ -e /tmp/pwned || -e /tmp/ran ]] && bad "명령이 실행됨" || ok "아무것도 실행 안 됨"
done

setup; render ""; run "승인 해시 없음" 20 "$D"; [[ -e /tmp/ran ]] && bad "실행됨" || ok "실행 안 됨"
setup; render "$SHA"; echo '# 변조' >>/opt/homestamp/deploy/deploy.sh; run "변조된 deploy.sh" 21 "$D"; [[ -e /tmp/ran ]] && bad "실행됨" || ok "실행 안 됨"
setup; render "$SHA"; chmod 0770 /opt/homestamp/deploy/deploy.sh; run "그룹 쓰기 deploy.sh" 21 "$D"
setup; render "$SHA"; chmod 0757 /opt/homestamp/deploy; run "남이 쓸 수 있는 폴더" 21 "$D"
setup; render "$SHA"; chown 1000 /opt/homestamp/deploy/deploy.env; run "root 아닌 deploy.env" 21 "$D"
setup; render "$SHA"; mv /opt/homestamp/deploy/deploy.sh /opt/homestamp/real.sh; ln -s /opt/homestamp/real.sh /opt/homestamp/deploy/deploy.sh; run "심볼릭 링크 deploy.sh" 21 "$D"
setup; render "$SHA"; printf 'BLUE_PORT=8081\n' >/opt/homestamp/deploy/deploy.env; run "IMAGE_REPOSITORY 없음" 2 "$D"

echo "passed=$passed failed=$failed"
[[ $failed == 0 ]]
