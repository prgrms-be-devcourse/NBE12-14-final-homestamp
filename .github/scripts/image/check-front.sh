#!/usr/bin/env bash
# 화면 이미지(front/Dockerfile)가 운반 계약대로인지 본다. 실행하지 않고 docker create → docker export·cp 로 파일만 읽는다.
#   check-front.sh <로컬 이미지> <커밋 40자>
# 계약: linux/amd64 · 레이어 하나 · 커밋 라벨 == 커밋 · 파일은 /site 아래만 · /site/version.json == {"revision":"<커밋>"}
#       /site/index.html이 부르는 /assets/*.js·*.css가 있음 · 소스맵·node_modules·소스(.ts/.tsx)·package*.json·.env 없음
#       정해 둔 정적 파일 확장자만 · API 주소를 절대 주소로 박지 않음(같은 출처 /api)
#       일반 파일·폴더만(링크·하드링크·특수 파일 없음) · 이름은 영문·숫자·._-(점으로 시작 금지) · assets/는 하위 폴더 없이 해시 이름만
#       파일 2000개·하나 10MiB·전체 50MiB 이하(서버 설치 deploy-front.sh와 같은 규칙·값)
# 종료: 0 | 2 입력 | 30 계약 위반
set -euo pipefail
# 이름 검사·정규식이 로케일에 따라 달라지지 않게(서버 deploy-front.sh와 같게)
export LC_ALL=C
fail() {
  echo "check-front: $2" >&2
  exit "$1"
}
image="${1:-}"
rev="${2:-}"
[[ -n $image ]] || fail 2 "이미지를 주세요"
[[ $rev =~ ^[0-9a-f]{40}$ ]] || fail 2 "커밋은 40자입니다"
platform="${REQUIRED_PLATFORM:-linux/amd64}"
label=org.opencontainers.image.revision

got="$(docker image inspect -f '{{.Os}}/{{.Architecture}}' "$image" 2>/dev/null)" || fail 2 "로컬 이미지가 없습니다: $image"
[[ $got == "$platform" ]] || fail 30 "이미지가 ${platform}이 아닙니다($got)"
[[ "$(docker image inspect -f "{{index .Config.Labels \"$label\"}}" "$image")" == "$rev" ]] || fail 30 "커밋 라벨이 ${rev}가 아닙니다"
[[ "$(docker image inspect -f '{{len .RootFS.Layers}}' "$image")" == 1 ]] || fail 30 "레이어가 하나가 아닙니다(scratch 위 /site 하나여야 함)"

tmp="$(mktemp -d)"
cid=""
cleanup() {
  [[ -z $cid ]] || docker rm -f "$cid" >/dev/null 2>&1 || true
  rm -rf -- "$tmp"
}
trap cleanup EXIT
cid="$(docker create --platform "$platform" "$image")" || fail 30 "docker create 실패"

# Docker가 만들 때 넣는 항목(.dockerenv·dev·etc의 이름 파일·proc·sys) 말고는 /site 아래만 있어야 한다
docker export "$cid" | tar -t >"$tmp/list" || fail 30 "파일 목록을 읽지 못했습니다"
extra="$(grep -vE '^(site/.*|\.dockerenv|dev/|dev/(console|pts/|shm/)|etc/|etc/(hostname|hosts|mtab|resolv\.conf)|proc/|sys/)$' "$tmp/list" || true)"
[[ -z $extra ]] || fail 30 "/site 밖에 파일이 있습니다: $(head -n 3 <<<"$extra" | tr '\n' ' ')"
docker cp -q "$cid:/site" "$tmp/" || fail 30 "/site를 꺼내지 못했습니다"
s="$tmp/site"

[[ -f $s/index.html ]] || fail 30 "index.html이 없습니다"
[[ "$(cat "$s/version.json" 2>/dev/null)" == "{\"revision\":\"$rev\"}" ]] || fail 30 "version.json이 커밋과 맞지 않습니다"
# 아래는 서버 설치(infra/deploy/deploy-front.sh check_tree)와 같은 규칙이다. 한쪽만 바꾸지 않는다
# (CI front-contract-test.sh와 로컬 infra/deploy/test/front-test.sh F12가 같은 이미지로 맞춘다). 이름을 먼저 본다(뒤 메시지에 이상한 이름이 찍히지 않게)
bad="$(cd "$s" && find . -mindepth 1 \( ! -type f ! -type d -o -type f -links +1 -o -name '*[!A-Za-z0-9._-]*' -o -name '.*' \) -print | head -n 3)"
[[ -z $bad ]] || fail 30 "링크·특수 파일·하드링크·허용하지 않는 이름(점으로 시작 포함): $(printf '%q' "$bad" | cut -c1-200)"
bad="$(cd "$s" && find . \( -name '*.map' -o -name node_modules -o -name '*.ts' -o -name '*.tsx' -o -name 'package*.json' -o -name '.env*' -o -type l \) -print | head -n 3)"
[[ -z $bad ]] || fail 30 "들어가면 안 되는 파일: $(tr '\n' ' ' <<<"$bad")"
if [[ -e $s/assets ]]; then
  [[ -z "$(find "$s/assets" -mindepth 1 -type d | head -n 1)" ]] || fail 30 "assets/ 아래에 폴더가 있습니다"
  bad="$(find "$s/assets" -mindepth 1 -type f | sed 's|.*/||' | grep -vE '^[A-Za-z0-9._-]+-[A-Za-z0-9_-]{8}\.(js|css|woff2|woff|svg|png|jpg|webp|ico)$' | head -n 3 || true)"
  [[ -z $bad ]] || fail 30 "assets/의 파일 이름이 해시 이름이 아닙니다: $(tr '\n' ' ' <<<"$bad")"
fi
n="$(find "$s" -type f | wc -l | tr -d ' ')"
((n <= 2000)) || fail 30 "파일이 너무 많습니다($n > 2000)"
[[ -z "$(find "$s" -type f -size +10485760c | head -n 1)" ]] || fail 30 "10MiB를 넘는 파일이 있습니다"
total="$(find "$s" -type f -exec stat -c %s {} + | awk '{t+=$1} END{print t+0}')"
((total <= 52428800)) || fail 30 "전체 크기가 50MiB를 넘습니다($total)"
odd="$(cd "$s" && find . -type f ! \( -name '*.html' -o -name '*.js' -o -name '*.css' -o -name '*.woff2' -o -name '*.woff' \
  -o -name '*.svg' -o -name '*.png' -o -name '*.jpg' -o -name '*.webp' -o -name '*.ico' -o -name '*.txt' -o -name '*.webmanifest' \
  -o -path ./version.json \) -print | head -n 3)"
[[ -z $odd ]] || fail 30 "정해 두지 않은 파일 종류: $(tr '\n' ' ' <<<"$odd")"

# index.html의 "/assets/…" 참조는 모두 assets/ 바로 아래 해시 이름(경로·.. 없음)이고 그 파일이 있어야 한다
refs="$(grep -oE '"/assets/[^"]*"' "$s/index.html" | tr -d '"' | sed 's|^/||' || true)"
grep -qE '^assets/[^/]+\.js$' <<<"$refs" || fail 30 "index.html이 /assets의 JS를 부르지 않습니다"
grep -qE '^assets/[^/]+\.css$' <<<"$refs" || fail 30 "index.html이 /assets의 CSS를 부르지 않습니다"
while read -r r; do
  [[ ${r#assets/} =~ ^[A-Za-z0-9._-]+-[A-Za-z0-9_-]{8}\.(js|css|woff2|woff|svg|png|jpg|webp|ico)$ && -f $s/$r ]] ||
    fail 30 "index.html의 /assets 참조가 해시 이름이 아니거나 없습니다: $(printf '%q' "$r")"
done <<<"$refs"
grep -rqE 'https?://(localhost|127\.0\.0\.1)' "$s" && fail 30 "개발용 API 주소(localhost)가 들어 있습니다"

echo "check-front: 통과 files=$(find "$s" -type f | wc -l | tr -d ' ') revision=$rev"
