#!/usr/bin/env bash
# deploy.yml이 부른다: 배포 전용 SSM 문서(homestamp-deploy)를 앱 EC2 한 대에 보내고, 끝날 때까지 기다려 결과를 돌려준다.
# 입력(환경변수): DIGEST(sha256:<64자>), INSTANCE_ID(i-…), DOCUMENT_NAME, AWS_REGION. 선택: POLL_LIMIT(초, 기본 1200), POLL_INTERVAL(기본 10)
# 종료 코드: 0 성공 | 2 입력 | 서버 명령의 코드 그대로(deploy.sh 2~17, 문서 20·21)
#            30 보내기 실패(권한 거부 등) | 40 서버 실행 시간 초과 | 41 전달 실패(EC2 꺼짐·SSM 연결 없음) | 42 취소됨 | 43 기다리다 그만둠(서버 명령은 계속)
# 비밀값을 다루지 않는다. 서버 출력(deploy.sh 단계 문장)만 보여 준다. 워크플로가 취소돼도 서버 명령은 취소하지 않는다.
set -euo pipefail
set +x

fail() {
  echo "::error::$2"
  exit "$1"
}
[[ "${DIGEST:-}" =~ ^sha256:[0-9a-f]{64}$ ]] || fail 2 "digest는 sha256:<64자 소문자 16진수>만 받습니다"
[[ "${INSTANCE_ID:-}" =~ ^i-[0-9a-f]{8,17}$ ]] || fail 2 "INSTANCE_ID 형식이 아닙니다"
[[ "${DOCUMENT_NAME:-}" =~ ^[A-Za-z0-9_.-]{3,128}$ ]] || fail 2 "DOCUMENT_NAME 형식이 아닙니다"
[[ "${AWS_REGION:-}" =~ ^[a-z]{2}-[a-z]+-[0-9]$ ]] || fail 2 "AWS_REGION 형식이 아닙니다"
limit="${POLL_LIMIT:-1200}"
interval="${POLL_INTERVAL:-10}"

command_id=""
on_cancel() {
  if [[ -n $command_id ]]; then
    echo "::warning::워크플로가 멈췄지만 서버 배포($command_id)는 취소하지 않고 끝까지 돕니다. EC2에서 deploy.sh --status로 확인하세요"
  fi
  exit 130
}
trap on_cancel INT TERM

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

deadline=$((SECONDS + limit))
status=""
code=""
while :; do
  if out="$(aws ssm get-command-invocation --region "$AWS_REGION" --command-id "$command_id" \
    --instance-id "$INSTANCE_ID" --query '[Status,ResponseCode]' --output text 2>/dev/null)"; then
    read -r status code <<<"$out"
    case "$status" in
      Pending | InProgress | Delayed) ;;
      *) break ;;
    esac
  fi
  # 보낸 직후에는 InvocationDoesNotExist가 올 수 있어 다시 묻는다
  if ((SECONDS >= deadline)); then
    fail 43 "${limit}초 동안 끝나지 않았습니다. 서버 명령은 계속 돌 수 있습니다(EC2에서 deploy.sh --status)"
  fi
  sleep "$interval"
done

echo "---- 서버 출력(deploy.sh) ----"
aws ssm get-command-invocation --region "$AWS_REGION" --command-id "$command_id" \
  --instance-id "$INSTANCE_ID" --query 'StandardOutputContent' --output text || true
echo "---- 끝: $status (code $code) ----"

case "$status" in
  Success) exit 0 ;;
  Failed)
    [[ $code =~ ^[0-9]+$ ]] && ((code > 0 && code < 256)) || code=1
    fail "$code" "배포 실패: 서버 종료 코드 $code(런북 종료 코드 표)" ;;
  TimedOut) fail 40 "서버 실행 시간(900초)을 넘었습니다. deploy.sh가 신호로 멈췄을 수 있습니다(--status)" ;;
  DeliveryTimedOut | Undeliverable | Terminated) fail 41 "명령이 EC2에 전달되지 않았습니다(꺼져 있거나 SSM 연결 없음): $status" ;;
  Cancelled | Cancelling) fail 42 "명령이 취소됐습니다: $status" ;;
  *) fail 1 "알 수 없는 상태: $status" ;;
esac
