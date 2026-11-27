#!/usr/bin/env bash
# 검사를 통과한 로컬 이미지를 GHCR에 올리고, 레지스트리에 실제로 올라간 manifest의 digest를 돌려준다.
# 환경: IMAGE_REPOSITORY(ghcr.io/…), LOCAL_IMAGE(검사한 이미지), REVISION(커밋 40자)
# 태그는 sha-<커밋> 하나. 이미 있으면 덮어쓰지 않는다:
#   같은 커밋 라벨·linux/amd64면 그 digest를 그대로 돌려줌(재실행) / 다르면 실패
# 확인: 로컬 이미지가 linux/amd64이고 커밋 라벨이 REVISION / 올린 뒤 태그의 digest == 이 로컬 이미지가 올라간 digest
#       레지스트리의 설정에서 다시 linux/amd64·커밋 라벨
# 종료: 0 | 2 입력 | 20 아키텍처 | 21 커밋 라벨 | 22 태그가 다른 이미지 | 23 레지스트리 조회 실패(인증 등) | 24 Push 실패 | 25 digest 확인 실패
set -euo pipefail
set +x
fail() {
  echo "publish: $2" >&2
  exit "$1"
}
[[ "${IMAGE_REPOSITORY:-}" =~ ^[a-z0-9.:-]+(/[a-z0-9]+([._-][a-z0-9]+)*)+$ ]] || fail 2 "IMAGE_REPOSITORY 형식이 아닙니다"
[[ "${REVISION:-}" =~ ^[0-9a-f]{40}$ ]] || fail 2 "REVISION은 커밋 40자입니다"
[[ -n "${LOCAL_IMAGE:-}" ]] || fail 2 "LOCAL_IMAGE가 없습니다"
platform="${REQUIRED_PLATFORM:-linux/amd64}"
label=org.opencontainers.image.revision
ref="$IMAGE_REPOSITORY:sha-$REVISION"

local_platform="$(docker image inspect -f '{{.Os}}/{{.Architecture}}' "$LOCAL_IMAGE")" || fail 2 "로컬 이미지가 없습니다"
[[ $local_platform == "$platform" ]] || fail 20 "이미지가 $platform이 아닙니다($local_platform)"
[[ "$(docker image inspect -f "{{index .Config.Labels \"$label\"}}" "$LOCAL_IMAGE")" == "$REVISION" ]] ||
  fail 21 "이미지의 커밋 라벨이 $REVISION이 아닙니다"

# 레지스트리의 manifest(digest는 원문 바이트의 SHA-256)와 그 설정(os/arch/라벨)
remote_digest() { docker buildx imagetools inspect --raw "$1" | sha256sum | awk '{print "sha256:" $1}'; }
remote_check() { # <ref> → "platform revision" (index면 linux/amd64 항목의 것)
  docker buildx imagetools inspect "$1" --format '{{json .Image}}' |
    jq -r --arg p "$platform" --arg l "$label" '
      (if has("architecture") then . else (.[$p] // {}) end)
      | "\(.os // "?")/\(.architecture // "?") \(.config.Labels[$l] // "")"'
}

if err="$(docker buildx imagetools inspect --raw "$ref" 2>&1 >/dev/null)"; then
  read -r rp rrev <<<"$(remote_check "$ref")"
  if [[ $rp == "$platform" && $rrev == "$REVISION" ]]; then
    digest="$(remote_digest "$ref")"
    echo "publish: 이 커밋의 이미지는 이미 발행됐습니다. 다시 올리지 않습니다"
  else
    fail 22 "태그 sha-$REVISION이 다른 이미지($rp ${rrev:-라벨 없음})를 가리킵니다. 덮어쓰지 않습니다"
  fi
else
  case "$err" in
    *"not found"* | *"NOT_FOUND"* | *"MANIFEST_UNKNOWN"* | *"manifest unknown"*) ;;
    *) fail 23 "레지스트리를 조회하지 못했습니다(인증·네트워크)" ;;
  esac
  docker tag "$LOCAL_IMAGE" "$ref"
  docker push -q "$ref" >/dev/null || fail 24 "Push하지 못했습니다"
  digest="$(remote_digest "$ref")" || fail 25 "올린 뒤 레지스트리에서 digest를 읽지 못했습니다"
  docker image inspect -f '{{range .RepoDigests}}{{println .}}{{end}}' "$LOCAL_IMAGE" | grep -qxF "$IMAGE_REPOSITORY@$digest" ||
    fail 25 "태그의 digest($digest)가 이 이미지가 올라간 digest가 아닙니다"
fi

[[ $digest =~ ^sha256:[0-9a-f]{64}$ ]] || fail 25 "digest 형식이 아닙니다"
# 목록(index)이면 linux/amd64(와 빌드 증명용 unknown/unknown)만 있어야 한다. 단일 이미지면 그대로
kind="$(docker buildx imagetools inspect --raw "$IMAGE_REPOSITORY@$digest" | jq -r '.mediaType // ""')"
case "$kind" in
  *image.index* | *manifest.list*)
    others="$(docker buildx imagetools inspect --raw "$IMAGE_REPOSITORY@$digest" |
      jq -r --arg p "$platform" '[.manifests[].platform | "\(.os)/\(.architecture)" | select(. != $p and . != "unknown/unknown")] | join(",")')"
    [[ -z $others ]] || fail 25 "목록에 $platform 말고 다른 플랫폼이 있습니다($others)"
    ;;
  *image.manifest* | *distribution.manifest*) ;;
  *) fail 25 "알 수 없는 manifest 형식입니다($kind)" ;;
esac
read -r rp rrev <<<"$(remote_check "$IMAGE_REPOSITORY@$digest")"
[[ $rp == "$platform" && $rrev == "$REVISION" ]] || fail 25 "레지스트리의 이미지 설정이 다릅니다($rp $rrev)"

echo "digest=$digest"
echo "image=$IMAGE_REPOSITORY@$digest"
echo "manifest=$kind"
if [[ -n ${GITHUB_OUTPUT:-} ]]; then
  { echo "digest=$digest"; echo "image=$IMAGE_REPOSITORY@$digest"; } >>"$GITHUB_OUTPUT"
fi
