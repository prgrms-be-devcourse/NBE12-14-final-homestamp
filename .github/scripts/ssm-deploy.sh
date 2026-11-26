#!/usr/bin/env bash
# deploy.yml이 부른다: 배포 전용 SSM 문서(homestamp-deploy)를 앱 EC2 한 대에 보내고, 끝날 때까지 기다려 결과를 돌려준다.
# 입력(환경변수): DIGEST(sha256:<64자>), INSTANCE_ID(i-…), DOCUMENT_NAME, AWS_REGION.
#   선택: POLL_LIMIT(초, 기본 2700 = 문서 실행 제한 2400 + 전달 60 + 여유), POLL_INTERVAL(기본 10)
# 종료 코드: 0 성공·변경 없음 | 2 입력 | 서버 명령의 코드 그대로(deploy.sh 2~17·129·130·143, 문서 20·21)
#            30 보내기 실패(권한 거부 등) | 40 서버 실행 시간 초과 | 41 전달 실패(EC2 꺼짐·SSM 연결 없음) | 42 취소됨
#            43 기다리다 그만둠(서버 명령은 계속) | 44 결과를 조회하지 못함(서버 명령은 계속)
# 비밀값을 다루지 않는다. 서버 출력(deploy.sh 단계 문장, Migration·GRANT 출력 끝부분)만 보여 준다.
# 워크플로가 취소돼도 서버 명령은 취소하지 않는다. 실패한 배포를 다시 보내지 않는다(다시 배포는 사람이 정함).
set -euo pipefail
set +x

summary() { if [[ -n ${GITHUB_STEP_SUMMARY:-} ]]; then printf '%s\n' "$@" >>"$GITHUB_STEP_SUMMARY"; fi; }
fail() {
  echo "::error::$2"
  summary "- 결과: **실패** (종료 $1) — $2"
  exit "$1"
}
[[ "${DIGEST:-}" =~ ^sha256:[0-9a-f]{64}$ ]] || fail 2 "digest는 sha256:<64자 소문자 16진수>만 받습니다"
[[ "${INSTANCE_ID:-}" =~ ^i-[0-9a-f]{8,17}$ ]] || fail 2 "INSTANCE_ID 형식이 아닙니다"
[[ "${DOCUMENT_NAME:-}" =~ ^[A-Za-z0-9_.-]{3,128}$ ]] || fail 2 "DOCUMENT_NAME 형식이 아닙니다"
[[ "${AWS_REGION:-}" =~ ^[a-z]{2}-[a-z]+-[0-9]$ ]] || fail 2 "AWS_REGION 형식이 아닙니다"
limit="${POLL_LIMIT:-2700}"
interval="${POLL_INTERVAL:-10}"

errf="$(mktemp)"
command_id=""
on_cancel() {
  if [[ -n $command_id ]]; then
    echo "::warning::워크플로가 멈췄지만 서버 배포($command_id)는 취소하지 않고 끝까지 돕니다. EC2에서 deploy.sh --status로 확인하세요"
    summary "- 결과: **워크플로 멈춤**, 서버 명령 \`$command_id\`는 계속(EC2에서 \`deploy.sh --status\`)"
  fi
  exit 130
}
trap on_cancel INT TERM
trap 'rm -f "$errf"' EXIT

summary "### 배포" "- 실행: ${GITHUB_RUN_ID:-local}" "- 이미지 digest: \`$DIGEST\`"

# 전달 제한 60초: EC2가 꺼져 있으면 기다리지 않고 실패(04 8장 "CD는 EC2가 꺼져 있으면 중단")
if ! command_id="$(aws ssm send-command --region "$AWS_REGION" \
  --document-name "$DOCUMENT_NAME" --instance-ids "$INSTANCE_ID" \
  --parameters "Digest=$DIGEST" --timeout-seconds 60 \
  --comment "homestamp deploy run ${GITHUB_RUN_ID:-local}" \
  --query 'Command.CommandId' --output text)"; then
  command_id=""
  fail 30 "SSM 명령을 보내지 못했습니다(권한·문서·인스턴스 확인)"
fi
[[ $command_id =~ ^[0-9a-f-]{36}$ ]] || fail 30 "SSM 명령 ID를 받지 못했습니다"
echo "SSM 명령 $command_id → $INSTANCE_ID"
summary "- SSM 명령 ID: \`$command_id\`"

deadline=$((SECONDS + limit))
status=""
code=""
seen=false
while :; do
  if out="$(aws ssm get-command-invocation --region "$AWS_REGION" --command-id "$command_id" \
    --instance-id "$INSTANCE_ID" --query '[Status,ResponseCode]' --output text 2>"$errf")"; then
    seen=true
    read -r status code <<<"$out"
    case "$status" in
      Pending | InProgress | Delayed) ;;
      *) break ;;
    esac
  elif ! grep -q InvocationDoesNotExist "$errf" 2>/dev/null; then
    # 보낸 직후의 InvocationDoesNotExist 말고는(권한·조회 제한·네트워크) 경고만 남기고 다시 묻는다
    echo "::warning::결과 조회가 실패했습니다. 다시 묻습니다"
  fi
  if ((SECONDS >= deadline)); then
    if $seen; then
      fail 43 "${limit}초 동안 끝나지 않았습니다($status). 서버 명령은 계속 돌 수 있습니다(${command_id}, EC2에서 deploy.sh --status)"
    fi
    fail 44 "${limit}초 동안 결과를 조회하지 못했습니다. 서버 명령 ${command_id}는 돌고 있을 수 있습니다(EC2에서 deploy.sh --status)"
  fi
  sleep "$interval"
done

stdout="$(aws ssm get-command-invocation --region "$AWS_REGION" --command-id "$command_id" \
  --instance-id "$INSTANCE_ID" --query 'StandardOutputContent' --output text 2>/dev/null || true)"
echo "---- 서버 출력(deploy.sh, 끝부분) ----"
printf '%s\n' "$stdout"
echo "---- 끝: $status (code $code) ----"
# deploy.sh의 마지막 줄 "끝: result=<success|noop|failed|interrupted> exit=<코드>"(SSM 문서가 끝부분만 남겨 잘리지 않음)
result="$(grep -oE '끝: result=[a-z]+' <<<"$stdout" | tail -n 1 | cut -d= -f2 || true)"
summary "- 서버 상태: $status, 종료 코드: $code, deploy.sh 결과: ${result:-알 수 없음}"

case "$status" in
  Success)
    case "$result" in
      noop) echo "같은 이미지가 이미 활성이라 바꾼 것이 없습니다"; summary "- 결과: **변경 없음**(같은 이미지가 이미 활성)" ;;
      success) summary "- 결과: **성공**" ;;
      failed | interrupted) fail 1 "종료 코드는 0이지만 deploy.sh 결과 줄이 ${result}입니다. 성공으로 보지 않습니다(EC2에서 deploy.sh --status)" ;;
      *) echo "::warning::종료 코드는 0이지만 deploy.sh 결과 줄을 찾지 못했습니다(EC2에서 deploy.sh --status)"; summary "- 결과: 종료 0(결과 줄 없음, \`--status\`로 확인)" ;;
    esac
    exit 0 ;;
  Failed)
    [[ $code =~ ^[0-9]+$ ]] && ((code > 0 && code < 256)) || code=1
    case "$code" in
      17) fail 17 "되돌리기를 확인하지 못했습니다. 두 앱이 떠 있고 트래픽이 새 슬롯일 수 있습니다. **운영자 확인 필요**(런북 \"손으로 되돌리기\"). 다시 배포하지 마세요" ;;
      16) fail 16 "전환 뒤 확인 실패: 이전 슬롯으로 되돌렸거나(이전 앱 있음) 새 앱을 그대로 뒀습니다(첫 배포). 기록의 rolled_back을 확인하세요" ;;
      11 | 12) fail "$code" "$([[ $code == 11 ]] && echo '--migrate' || echo '--apply-grants') 실패·시간 초과: 새 앱은 시작하지 않았습니다. 원인을 본 뒤 사람이 다시 배포합니다(자동 재시도 없음)" ;;
      3) fail 3 "다른 배포가 서버에서 진행 중입니다. 끝난 뒤 다시 실행하세요" ;;
      5) fail 5 "이전 Migration·GRANT 컨테이너가 아직 실행 중입니다. 끝난 뒤 다시 실행하세요" ;;
      *) fail "$code" "배포 실패: 서버 종료 코드 $code(런북 종료 코드 표)" ;;
    esac ;;
  TimedOut) fail 40 "서버 실행 시간(2400초)을 넘었습니다. deploy.sh가 신호로 멈췄을 수 있습니다(EC2에서 deploy.sh --status)" ;;
  DeliveryTimedOut | Undeliverable | Terminated) fail 41 "명령이 EC2에 전달되지 않았습니다(꺼져 있거나 SSM 연결 없음): $status" ;;
  Cancelled | Cancelling) fail 42 "명령이 취소됐습니다: $status" ;;
  *) fail 1 "알 수 없는 상태: $status" ;;
esac
