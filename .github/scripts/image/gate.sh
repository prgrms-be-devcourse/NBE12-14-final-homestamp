#!/usr/bin/env bash
# 이 실행이 GHCR에 발행(Push)해도 되는지 정한다. 빌드·검사는 언제나 하고, 발행은 아래가 모두 맞을 때만.
#   EVENT_NAME   push 또는 workflow_dispatch (pull_request는 언제나 발행 안 함)
#   REF          refs/heads/main
#   PUBLISH_ENABLED  저장소 변수 IMAGE_PUBLISH_ENABLED == "true" (저장소 Admin만 바꾼다. 공개 승인 뒤에 켠다)
#   IMAGE_REPOSITORY 저장소 변수(ghcr.io/<owner>/<name>, 소문자). 정해지기 전에는 비어 있다
# 출력: publish=true|false 와 이유. 판단 자체는 언제나 0으로 끝난다(입력이 이상하면 false)
set -euo pipefail
reason=""
if [[ "${EVENT_NAME:-}" != push && "${EVENT_NAME:-}" != workflow_dispatch ]]; then
  reason="이벤트가 ${EVENT_NAME:-없음}(PR·그 밖에서는 발행하지 않음)"
elif [[ "${REF:-}" != refs/heads/main ]]; then
  reason="브랜치가 main이 아님(${REF:-없음})"
elif [[ "${PUBLISH_ENABLED:-}" != true ]]; then
  reason="IMAGE_PUBLISH_ENABLED가 true가 아님(공개 승인 전)"
elif [[ ! "${IMAGE_REPOSITORY:-}" =~ ^ghcr\.io/[a-z0-9]+([._-][a-z0-9]+)*(/[a-z0-9]+([._-][a-z0-9]+)*)+$ ]]; then
  reason="IMAGE_REPOSITORY가 없거나 형식이 아님"
fi
if [[ -n $reason ]]; then
  echo "publish=false"
  echo "reason=$reason"
else
  echo "publish=true"
  echo "reason=main·발행 허용·저장소 이름 확인"
fi
