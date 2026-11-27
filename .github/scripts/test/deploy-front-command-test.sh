#!/usr/bin/env bash
# homestamp-deploy-front 문서의 명령(infra/terraform/ssm/deploy-front-command.sh.tftpl) 시험과 배포 역할 IAM의 정적 확인.
# Linux 컨테이너 안에서 root로, 가짜 /opt/homestamp/deploy/deploy-front.sh를 두고 돌린다(AWS·EC2 없음).
#   .github/scripts/test/deploy-front-command-test.sh            (호스트: Docker 필요)
set -uo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd)"
IMAGE=debian:bookworm-slim@sha256:e67eeead736ff7bbe258ef088bff683635c561b1fcfefad0806c1df4ab49ce0e
if [[ "${1:-}" != --inside ]]; then
  exec docker run --rm --network none -v "$root:/src:ro" "$IMAGE" bash /src/.github/scripts/test/deploy-front-command-test.sh --inside
fi

tpl=/src/infra/terraform/ssm/deploy-front-command.sh.tftpl
tf=/src/infra/terraform/deploy.tf
passed=0; failed=0
ok() { passed=$((passed + 1)); echo "ok   $1"; }
bad() { failed=$((failed + 1)); echo "FAIL $1"; }
D=sha256:$(printf 'c%.0s' $(seq 1 64))

grep -q '{{' "$tpl" && bad "명령 본문에 {{ }} 치환 없음" || ok "명령 본문에 {{ }} 치환 없음"
[[ $(grep -o '\${[a-z_0-9]*}' "$tpl" | sort -u) == '${expected_sha256}' ]] && ok "템플릿 변수는 expected_sha256 하나" || bad "템플릿 변수"
grep -v '^[[:space:]]*#' "$tpl" | grep -qE 'switch-upstream|SWITCH_CMD|nginx|docker' && bad "화면 문서가 nginx·docker를 직접 부름" || ok "화면 문서는 deploy-front.sh 하나만 실행"

setup() { # 정상 설치 상태(백엔드 deploy.env도 있음)
  rm -rf /opt/homestamp /tmp/pwned /tmp/ran /tmp/got-term
  mkdir -p /opt/homestamp/deploy && chmod 0755 /opt /opt/homestamp /opt/homestamp/deploy
  cat >/opt/homestamp/deploy/deploy-front.sh <<'FAKE'
#!/bin/bash
echo "args: $*" > /tmp/ran
trap 'echo term >/tmp/got-term; echo "끝: result=interrupted exit=143"; exit 143' TERM
if [[ -n ${FAKE_BIG:-} ]]; then head -c 60000 /dev/zero | tr '\0' 'x'; echo; fi
if [[ -n ${FAKE_SLEEP:-} ]]; then sleep "$FAKE_SLEEP" & wait $!; fi
echo "끝: result=${FAKE_RESULT:-success} exit=${FAKE_EXIT:-0}"
exit "${FAKE_EXIT:-0}"
FAKE
  chmod 0750 /opt/homestamp/deploy/deploy-front.sh
  printf 'IMAGE_REPOSITORY=ghcr.io/example/front\nFRONT_ROOT=/srv/homestamp/front\n' >/opt/homestamp/deploy/deploy-front.env
  printf 'IMAGE_REPOSITORY=ghcr.io/example/app\n' >/opt/homestamp/deploy/deploy.env
  chmod 0640 /opt/homestamp/deploy/deploy-front.env /opt/homestamp/deploy/deploy.env
  SHA="$(sha256sum /opt/homestamp/deploy/deploy-front.sh | cut -d' ' -f1)"
}
render() { sed "s/\${expected_sha256}/$1/" "$tpl" >/tmp/cmd.sh; }
run() { # 이름 기대코드 digest
  SSM_Digest="$3" bash /tmp/cmd.sh >/tmp/out 2>&1
  local rc=$?
  if [[ $rc == "$2" ]]; then ok "$1 ($rc)"; else bad "$1 (기대 $2, 실제 $rc)"; cat /tmp/out; fi
}

setup; render "$SHA"
run "정상" 0 "$D"
[[ "$(cat /tmp/ran)" == "args: --config /opt/homestamp/deploy/deploy-front.env ghcr.io/example/front@$D" ]] && ok "deploy-front.sh에 넘긴 인자(화면 저장소)" || bad "인자: $(cat /tmp/ran)"
for c in 4 16 17 22 23 24; do setup; render "$SHA"; FAKE_EXIT=$c run "종료 $c 전파" "$c" "$D"; done
setup; render "$SHA"; FAKE_BIG=1 FAKE_EXIT=16 run "긴 출력에서도 종료 코드 전파" 16 "$D"
size="$(wc -c </tmp/out)"; ((size <= 20200)) && ok "출력은 끝 20,000바이트 이하($size)" || bad "출력 크기 $size"
tail -n 1 /tmp/out | grep -q '끝: result=success exit=16' && ok "마지막 결과 줄이 남음" || bad "결과 줄"
setup; render "$SHA"
FAKE_SLEEP=30 SSM_Digest="$D" bash /tmp/cmd.sh >/tmp/out 2>&1 &
p=$!; sleep 2; kill -TERM $p; wait $p; rc=$?
[[ $rc == 143 && -e /tmp/got-term ]] && ok "SSM의 TERM을 deploy-front.sh에 넘기고 143" || bad "신호 전달(rc=$rc)"
grep -q 'result=interrupted' /tmp/out && ok "신호로 멈춰도 출력이 남음" || bad "신호 뒤 출력"

for p in "$D;touch /tmp/pwned" "\$(touch /tmp/pwned)" "$D\`touch /tmp/pwned\`" "$D"$'\n'"touch /tmp/pwned" "" "$D x"; do
  setup; render "$SHA"
  run "주입 거절: $(printf '%q' "$p" | cut -c1-40)" 2 "$p"
  [[ -e /tmp/pwned || -e /tmp/ran ]] && bad "명령이 실행됨" || ok "아무것도 실행 안 됨"
done
setup; render ""; run "승인 해시 없음" 20 "$D"; [[ -e /tmp/ran ]] && bad "실행됨" || ok "실행 안 됨"
setup; render "$SHA"; echo '# 변조' >>/opt/homestamp/deploy/deploy-front.sh; run "변조된 deploy-front.sh" 21 "$D"; [[ -e /tmp/ran ]] && bad "실행됨" || ok "실행 안 됨"
setup; render "$(printf 'd%.0s' $(seq 1 64))"; run "다른 해시(백엔드 해시 등) → 21" 21 "$D"
setup; render "$SHA"; chmod 0770 /opt/homestamp/deploy/deploy-front.sh; run "그룹 쓰기 deploy-front.sh" 21 "$D"
setup; render "$SHA"; chmod 0757 /opt/homestamp/deploy; run "남이 쓸 수 있는 폴더" 21 "$D"
setup; render "$SHA"; chmod 0757 /opt; run "/opt 남 쓰기" 21 "$D"; chmod 0755 /opt
setup; render "$SHA"; chown 1000 /opt/homestamp/deploy/deploy-front.env; run "root 아닌 deploy-front.env" 21 "$D"
setup; render "$SHA"; mv /opt/homestamp/deploy/deploy-front.sh /opt/homestamp/real.sh; ln -s /opt/homestamp/real.sh /opt/homestamp/deploy/deploy-front.sh; run "심볼릭 링크 deploy-front.sh" 21 "$D"
setup; render "$SHA"; rm /opt/homestamp/deploy/deploy-front.sh; mkdir /opt/homestamp/deploy/deploy-front.sh; run "deploy-front.sh가 폴더" 21 "$D"
setup; render "$SHA"; printf 'FRONT_ROOT=/srv/homestamp/front\n' >/opt/homestamp/deploy/deploy-front.env; run "IMAGE_REPOSITORY 없음" 2 "$D"
setup; render "$SHA"; printf 'IMAGE_REPOSITORY=ghcr.io/example/app\n' >/opt/homestamp/deploy/deploy-front.env; run "백엔드와 같은 저장소 → 2(섞지 않음)" 2 "$D"; [[ -e /tmp/ran ]] && bad "실행됨" || ok "실행 안 됨"
setup; render "$SHA"; rm /opt/homestamp/deploy/deploy.env; run "백엔드 deploy.env가 없어도 화면만 배포" 0 "$D"

echo "== 배포 역할 IAM(deploy.tf 정적 확인)"
allow="$(awk '/sid *= *"SendDeployDocumentToWebOnly"/{f=1} f&&/resources/{print; exit}' "$tf")"
deny="$(awk '/sid *= *"DenySendCommandElsewhere"/{f=1} f&&/not_resources/{print; exit}' "$tf")"
want='[aws_ssm_document.deploy.arn, aws_ssm_document.deploy_front.arn, aws_instance.web.arn]'
[[ $allow == *"= $want" ]] && ok "SendCommand 허용: 배포 문서 둘과 앱 EC2 하나만" || bad "허용 자원: $allow"
[[ $deny == *"= $want" ]] && ok "명시적 거부: 그 밖의 문서·인스턴스(범용 셸 문서 포함)" || bad "거부 자원: $deny"
grep -qE '"ssm:StartSession"|"ssm:UpdateDocument"|"ssm:CancelCommand"' "$tf" && ok "세션·문서 수정·취소 거부 유지" || bad "세션 거부"
[[ $(grep -c 'actions *= *\["ssm:SendCommand"\]' "$tf") == 2 ]] && ok "SendCommand 문장은 허용 하나·거부 하나" || bad "SendCommand 문장 수"
grep -qE 'secretsmanager|ssm:GetParameter|"\*" *\]' <(sed -n '/data "aws_iam_policy_document" "deploy" {/,/^}/p' "$tf" | grep -v 'resources = \["\*"\]') && bad "비밀값 읽기·넓은 권한" || ok "배포 역할에 비밀값 읽기 없음"
grep -q 'templatefile("${path.module}/ssm/deploy-front-command.sh.tftpl", { expected_sha256 = var.deploy_front_script_sha256 })' "$tf" && ok "화면 문서는 화면 스크립트 해시로 고정" || bad "화면 문서 해시"
grep -q 'timeoutSeconds = "600"' "$tf" && ok "화면 문서 실행 600초" || bad "화면 문서 시간"

echo "passed=$passed failed=$failed"
[[ $failed == 0 ]]
