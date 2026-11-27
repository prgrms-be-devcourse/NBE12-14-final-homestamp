#!/usr/bin/env bash
# deploy.yml이 배포 전에 부른다: 넣은 digest가 이미지 발행 절차(image-publish.yml)가 main에서 올린 이미지인지 본다.
# 입력(환경변수): IMAGE_REPOSITORY(ghcr.io/<owner>/<이름>), DIGEST(sha256:<64자>), OWNER(github.repository_owner),
#                 GITHUB_REPOSITORY(owner/repo), GH_TOKEN(contents: read)
# 확인하는 것(공개 GHCR이라 자격 증명 없이 읽음, 04 9-3):
#   1 저장소가 이 GitHub 조직의 ghcr.io 아래
#   2 digest가 있고 linux/amd64(t3)를 담음
#   3 커밋 라벨(org.opencontainers.image.revision)이 40자
#   4 발행 절차가 단 태그 sha-<커밋>이 바로 이 digest(publish.sh는 이 태그를 덮어쓰지 않음)
#   5 그 커밋이 main에 있음(발행은 main에서만, image-publish gate.sh)
# 한계: 서명·빌드 증명(attestation) 검증은 아니다. GHCR 쓰기 권한이 있는 사람이 태그를 손으로 바꾸면 막지 못한다.
# 종료: 0 통과 | 2 입력 | 50 이미지 없음·읽기 실패 | 51 linux/amd64 아님 | 52 커밋 라벨 없음 | 53 sha-<커밋> 태그가 다른 이미지 | 54 커밋이 main에 없음
set -euo pipefail
set +x
fail() {
  echo "::error::$2"
  exit "$1"
}
[[ "${DIGEST:-}" =~ ^sha256:[0-9a-f]{64}$ ]] || fail 2 "digest는 sha256:<64자 소문자 16진수>만 받습니다"
owner="$(tr '[:upper:]' '[:lower:]' <<<"${OWNER:-}")"
[[ $owner =~ ^[a-z0-9-]+$ ]] || fail 2 "OWNER 형식이 아닙니다"
[[ "${IMAGE_REPOSITORY:-}" =~ ^ghcr\.io/${owner}/[a-z0-9]([a-z0-9._-]*[a-z0-9])?$ ]] ||
  fail 2 "IMAGE_REPOSITORY는 ghcr.io/$owner/<이름>이어야 합니다"
[[ "${GITHUB_REPOSITORY:-}" =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$ ]] || fail 2 "GITHUB_REPOSITORY 형식이 아닙니다"
image="$IMAGE_REPOSITORY@$DIGEST"
label=org.opencontainers.image.revision

# manifest 원문의 SHA-256(publish.sh와 같은 방법, 줄바꿈을 건드리지 않게 파이프로 바로 계산)
manifest_digest() { docker buildx imagetools inspect --raw "$1" 2>/dev/null | sha256sum | awk '{print "sha256:" $1}'; }
got="$(manifest_digest "$image")" || fail 50 "이미지를 찾지 못했습니다: $image"
[[ $got == "$DIGEST" ]] || fail 50 "manifest가 digest와 다릅니다"
info="$(docker buildx imagetools inspect "$image" --format '{{json .Image}}' 2>/dev/null)" || fail 50 "이미지 설정을 읽지 못했습니다"
# 목록(index)이면 플랫폼별 map, 단일 이미지면 설정 하나
read -r platform rev < <(jq -r --arg l "$label" '
  (if has("linux/amd64") then .["linux/amd64"] elif (.os? // null) != null then . else null end) as $i
  | if $i == null then "none -" else "\($i.os)/\($i.architecture) \($i.config.Labels[$l] // "-")" end' <<<"$info") ||
  fail 50 "이미지 설정을 해석하지 못했습니다"
echo "platform: $platform, revision: $rev"
[[ $platform == linux/amd64 ]] || fail 51 "linux/amd64 이미지가 아닙니다"
[[ $rev =~ ^[0-9a-f]{40}$ ]] || fail 52 "커밋 라벨($label)이 없습니다(발행 절차로 만든 이미지가 아님)"

tagged="$(manifest_digest "$IMAGE_REPOSITORY:sha-$rev")" ||
  fail 53 "발행 태그 sha-${rev}가 없습니다(발행 절차로 올린 이미지가 아님)"
[[ $tagged == "$DIGEST" ]] || fail 53 "발행 태그 sha-${rev}가 이 digest를 가리키지 않습니다"

status="$(gh api "repos/$GITHUB_REPOSITORY/compare/$rev...main" --jq .status 2>/dev/null)" ||
  fail 54 "커밋 ${rev}를 main과 비교하지 못했습니다"
case "$status" in
  identical | ahead) echo "commit $rev: main에 있음" ;;
  *) fail 54 "커밋 ${rev}가 main에 없습니다($status)" ;;
esac
