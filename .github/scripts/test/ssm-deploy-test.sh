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
    if [[ "$*" == *StandardOutputContent* ]]; then printf 'mock: deploy 출력\n%s\n' "${MOCK_RESULT:-끝: result=success exit=0 active=blue}"; exit 0; fi
    n=$(($(cat "$MOCK_DIR/polls" 2>/dev/null || echo 0) + 1)); echo "$n" >"$MOCK_DIR/polls"
    line="$(sed -n "${n}p" "$MOCK_DIR/seq")"; [[ -n $line ]] || line="$(tail -n 1 "$MOCK_DIR/seq")"
    [[ $line == NOTYET ]] && { echo "An error occurred (InvocationDoesNotExist)" >&2; exit 254; }
    [[ $line == DENIED ]] && { echo "An error occurred (AccessDeniedException) when calling the GetCommandInvocation operation" >&2; exit 254; }
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
  rm -f "$W/calls" "$W/polls" "$W/summary"; printf '%s\n' "$@" >"$W/seq"
  PATH="$W/bin:$PATH" MOCK_DIR="$W" MOCK_SEND="$send" DIGEST="${T_DIGEST-$D}" INSTANCE_ID="${T_INSTANCE:-i-0123456789abcdef0}" \
    DOCUMENT_NAME=homestamp-deploy AWS_REGION=ap-northeast-2 POLL_INTERVAL=0.2 POLL_LIMIT="${T_LIMIT:-5}" \
    GITHUB_STEP_SUMMARY="$W/summary" GITHUB_RUN_ID=777 MOCK_RESULT="${T_RESULT:-}" \
    bash "$target" >"$W/out" 2>&1
  local rc=$?
  if [[ $rc == "$want" ]]; then ok "$name ($rc)"; else bad "$name (기대 $want, 실제 $rc)"; cat "$W/out"; fi
}
tab=$'\t'
run "성공(처음엔 아직 없음 → 진행 중 → 성공)" 0 ok NOTYET "InProgress${tab}-1" "Success${tab}0"
grep -qF -- "--document-name homestamp-deploy --instance-ids i-0123456789abcdef0 --parameters Digest=$D --timeout-seconds 60" "$W/calls" && ok "보낸 인자: 문서·인스턴스·Digest 하나" || bad "보낸 인자"
grep -q 'mock: deploy 출력' "$W/out" && ok "서버 출력을 보여 줌" || bad "서버 출력"
grep -q '결과: \*\*성공\*\*' "$W/summary" && ok "요약: 성공" || bad "요약 성공"
grep -q '11111111-2222-3333-4444-555555555555' "$W/summary" && grep -q '실행: 777' "$W/summary" && grep -qF "$D" "$W/summary" && ok "요약: 실행 ID·명령 ID·digest" || bad "요약 식별자"
T_RESULT='끝: result=noop exit=0 active=blue' run "변경 없음(noop)도 0" 0 ok "Success${tab}0"
grep -q '변경 없음' "$W/summary" && ok "요약: 변경 없음으로 구분" || bad "noop 구분"
T_RESULT='(잘림)' run "결과 줄 없음" 0 ok "Success${tab}0"
grep -q '결과 줄을 찾지 못했습니다' "$W/out" && ok "결과 줄 없음 경고" || bad "결과 줄 없음 경고"
T_RESULT='끝: result=failed exit=14 active=blue' run "0인데 결과 줄이 failed면 실패" 1 ok "Success${tab}0"
T_RESULT='끝: result=interrupted exit=143 active=blue' run "0인데 결과 줄이 interrupted면 실패" 1 ok "Success${tab}0"
run "deploy.sh 실패 코드 전파(14 헬스)" 14 ok "Failed${tab}14"
run "deploy.sh 실패 코드 전파(11 migrate)" 11 ok "Failed${tab}11"
grep -q '자동 재시도 없음' "$W/out" && ok "11은 자동 재시도 없다고 안내" || bad "11 안내"
run "deploy.sh 실패 코드 전파(12 grants)" 12 ok "Failed${tab}12"
run "서버 잠금으로 거절(3)" 3 ok "Failed${tab}3"
run "문서의 해시 검사 실패(21)" 21 ok "Failed${tab}21"
run "되돌리기 실패(17)" 17 ok "Failed${tab}17"
grep -q '운영자 확인 필요' "$W/out" && grep -q '운영자 확인 필요' "$W/summary" && ok "17은 운영자 확인 필요로 보고" || bad "17 안내"
T_RESULT='끝: result=failed exit=4 active=green' run "활성 슬롯 확인 실패·noop-check(4)" 4 ok "Failed${tab}4"
grep -q 'noop-check' "$W/out" && grep -q '운영자 확인 필요' "$W/summary" && ok "4는 noop-check·운영자 확인 필요로 보고" || bad "4 안내"
run "전환 뒤 확인 실패(16)" 16 ok "Failed${tab}16"
run "신호로 멈춘 deploy.sh(143)" 143 ok "Failed${tab}143"
[[ $(grep -c 'send-command' "$W/calls") == 1 ]] && ok "실패해도 다시 보내지 않음(send-command 한 번)" || bad "재전송"
run "이상한 종료 코드는 1" 1 ok "Failed${tab}-1"
run "서버 실행 시간 초과" 40 ok "InProgress${tab}-1" "TimedOut${tab}-1"
run "EC2 꺼짐(전달 시간 초과)" 41 ok "Pending${tab}-1" "DeliveryTimedOut${tab}-1"
run "전달 불가" 41 ok "Undeliverable${tab}-1"
run "취소됨" 42 ok "Cancelled${tab}-1"
run "보내기 권한 거부" 30 denied
T_LIMIT=2 run "기다리다 그만둠" 43 ok "InProgress${tab}-1"
grep -q '서버 명령은 계속' "$W/out" && ok "그만둘 때 서버 명령이 계속된다고 안내" || bad "그만둠 안내"
T_LIMIT=2 run "결과를 한 번도 조회하지 못함(권한 등)" 44 ok DENIED
grep -q '결과 조회가 실패했습니다' "$W/out" && ok "조회 실패 경고" || bad "조회 실패 경고"
T_LIMIT=2 run "보낸 뒤 계속 InvocationDoesNotExist" 44 ok NOTYET
grep -q '결과 조회가 실패했습니다' "$W/out" && bad "InvocationDoesNotExist는 경고 없이 다시 묻기" || ok "InvocationDoesNotExist는 경고 없이 다시 묻기"
run "조회 실패 뒤 회복 → 성공" 0 ok DENIED "InProgress${tab}-1" "Success${tab}0"

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
