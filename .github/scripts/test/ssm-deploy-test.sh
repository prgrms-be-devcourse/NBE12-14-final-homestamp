#!/usr/bin/env bash
# ssm-deploy.sh 시험: 가짜 aws 명령으로 SSM 응답을 흉내 낸다(AWS 호출 없음). macOS bash 3.2·Linux bash 5 모두.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
target="$here/../ssm-deploy.sh"
W="$(mktemp -d)"
trap 'rm -r "$W"' EXIT
mkdir -p "$W/bin"
cat >"$W/bin/aws" <<'AWS'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$MOCK_DIR/calls"
case "$1 $2" in
  "ssm send-command")
    [[ $MOCK_SEND == ok ]] || { echo "An error occurred (AccessDeniedException) when calling the SendCommand operation" >&2; exit 254; }
    echo 11111111-2222-3333-4444-555555555555 ;;
  "ssm get-command-invocation")
    if [[ "$*" == *StandardOutputContent* ]]; then echo "mock: deploy 출력"; exit 0; fi
    n=$(($(cat "$MOCK_DIR/polls" 2>/dev/null || echo 0) + 1)); echo "$n" >"$MOCK_DIR/polls"
    line="$(sed -n "${n}p" "$MOCK_DIR/seq")"; [[ -n $line ]] || line="$(tail -n 1 "$MOCK_DIR/seq")"
    [[ $line == NOTYET ]] && { echo "An error occurred (InvocationDoesNotExist)" >&2; exit 254; }
    printf '%s\n' "$line" ;;
  *) echo "mock: 모르는 호출" >&2; exit 99 ;;
esac
AWS
chmod +x "$W/bin/aws"

D=sha256:$(printf 'a%.0s' $(seq 1 64))
passed=0; failed=0
ok() { passed=$((passed + 1)); echo "ok   $1"; }
bad() { failed=$((failed + 1)); echo "FAIL $1"; }
run() { # 이름 기대코드 send seq... (DIGEST·INSTANCE_ID는 환경으로 바꿀 수 있음)
  local name=$1 want=$2 send=$3; shift 3
  rm -f "$W/calls" "$W/polls"; printf '%s\n' "$@" >"$W/seq"
  PATH="$W/bin:$PATH" MOCK_DIR="$W" MOCK_SEND="$send" DIGEST="${T_DIGEST-$D}" INSTANCE_ID="${T_INSTANCE:-i-0123456789abcdef0}" \
    DOCUMENT_NAME=homestamp-deploy AWS_REGION=ap-northeast-2 POLL_INTERVAL=0.2 POLL_LIMIT="${T_LIMIT:-5}" \
    bash "$target" >"$W/out" 2>&1
  local rc=$?
  if [[ $rc == "$want" ]]; then ok "$name ($rc)"; else bad "$name (기대 $want, 실제 $rc)"; cat "$W/out"; fi
}
tab=$'\t'
run "성공(처음엔 아직 없음 → 진행 중 → 성공)" 0 ok NOTYET "InProgress${tab}-1" "Success${tab}0"
grep -qF -- "--document-name homestamp-deploy --instance-ids i-0123456789abcdef0 --parameters Digest=$D --timeout-seconds 60" "$W/calls" && ok "보낸 인자: 문서·인스턴스·Digest 하나" || bad "보낸 인자"
grep -q 'mock: deploy 출력' "$W/out" && ok "서버 출력을 보여 줌" || bad "서버 출력"
run "deploy.sh 실패 코드 전파(14 헬스)" 14 ok "Failed${tab}14"
run "deploy.sh 실패 코드 전파(11 migrate)" 11 ok "Failed${tab}11"
run "deploy.sh 실패 코드 전파(12 grants)" 12 ok "Failed${tab}12"
run "서버 잠금으로 거절(3)" 3 ok "Failed${tab}3"
run "문서의 해시 검사 실패(21)" 21 ok "Failed${tab}21"
run "되돌리기 실패(17)" 17 ok "Failed${tab}17"
run "이상한 종료 코드는 1" 1 ok "Failed${tab}-1"
run "서버 실행 시간 초과" 40 ok "InProgress${tab}-1" "TimedOut${tab}-1"
run "EC2 꺼짐(전달 시간 초과)" 41 ok "Pending${tab}-1" "DeliveryTimedOut${tab}-1"
run "전달 불가" 41 ok "Undeliverable${tab}-1"
run "취소됨" 42 ok "Cancelled${tab}-1"
run "보내기 권한 거부" 30 denied
T_LIMIT=2 run "기다리다 그만둠" 43 ok "InProgress${tab}-1"
grep -q '서버 명령은 계속' "$W/out" && ok "그만둘 때 서버 명령이 계속된다고 안내" || bad "그만둠 안내"

for p in "$D;id" "$D\$(id)" "$D\`id\`" "$D"$'\n'"id" "sha256:${D#sha256:}0" "SHA256:${D#sha256:}" "" "$D " "sha256:$(printf 'g%.0s' $(seq 1 64))"; do
  T_DIGEST="$p" run "digest 거절: $(printf '%q' "$p" | cut -c1-30)" 2 ok "Success${tab}0"
  [[ -e $W/calls ]] && bad "거절된 digest로 aws를 부름" || ok "aws 호출 없음"
done
T_INSTANCE='i-123;id' run "인스턴스 ID 거절" 2 ok "Success${tab}0"

# 워크플로 취소(SIGTERM) → 서버 명령은 취소하지 않음
rm -f "$W/calls" "$W/polls"; printf 'InProgress\t-1\n' >"$W/seq"
PATH="$W/bin:$PATH" MOCK_DIR="$W" MOCK_SEND=ok DIGEST="$D" INSTANCE_ID=i-0123456789abcdef0 DOCUMENT_NAME=homestamp-deploy \
  AWS_REGION=ap-northeast-2 POLL_INTERVAL=1 POLL_LIMIT=60 bash "$target" >"$W/out" 2>&1 &
p=$!; sleep 3; kill -TERM $p; wait $p; rc=$?
[[ $rc == 130 ]] && ok "취소 시 130" || bad "취소 코드 $rc"
grep -q '취소하지 않고' "$W/out" && ok "취소 안내" || bad "취소 안내"
grep -q 'cancel-command' "$W/calls" && bad "cancel-command를 부름" || ok "cancel-command 부르지 않음"

echo "passed=$passed failed=$failed"
[[ $failed == 0 ]]
