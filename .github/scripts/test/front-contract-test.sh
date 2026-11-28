#!/usr/bin/env bash
# 화면 파일 계약 시험: 같은 이미지를 CI 검사(.github/scripts/image/check-front.sh)와 서버 검사
# (infra/deploy/deploy-front.sh --check-dir)에 넣어 둘이 같은 판단(통과·거절)을 하는지 본다. 한쪽 규칙만 바뀌면 여기서 실패한다.
# 로컬 Docker만 쓴다(레지스트리·AWS 없음). GNU 도구가 있는 Linux 컨테이너 안에서 돌고, 이미지는 호스트 Docker로 만든다.
#   .github/scripts/test/front-contract-test.sh
set -uo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd -P)"
TOOLS=docker:29-cli@sha256:34507eb5069d8be82c95dd3b6987d4250fd5fee49b82e2509b7d7a7030740d6d
T=hs-fct
if [[ "${1:-}" != --inside ]]; then
  W="$(mktemp -d)"
  W="$(cd "$W" && pwd -P)"
  printf 'FROM %s\nRUN apk add --no-cache bash coreutils findutils tar grep sed\n' "$TOOLS" >"$W/Dockerfile"
  docker build -q -t $T-tools:local "$W" >/dev/null || { echo "도구 이미지 실패" >&2; exit 1; }
  docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$root:/src:ro" -v "$W:$W" \
    $T-tools:local bash /src/.github/scripts/test/front-contract-test.sh --inside "$W"
  rc=$?
  ids="$(docker image ls -q --filter "reference=$T-*")"
  [[ -z $ids ]] || docker image rm -f $ids >/dev/null 2>&1
  rm -r "$W"
  exit $rc
fi
W=$2
trap 'rm -rf "${W:?}"/*' EXIT # 컨테이너 안(root)에서 만든 파일은 여기서 지운다
passed=0
failed=0
ok() { passed=$((passed + 1)); echo "ok   $1"; }
bad() { failed=$((failed + 1)); echo "FAIL $1"; }
REV=0123456789abcdef0123456789abcdef01234567

site() { # <폴더>: 계약대로인 /site
  mkdir -p "$1/site/assets"
  printf '<!doctype html><script type="module" src="/assets/index-AbCd1234.js"></script><link rel="stylesheet" href="/assets/index-EfGh5678.css">\n' >"$1/site/index.html"
  printf 'import("/assets/lazy-IjKl9012.js")\n' >"$1/site/assets/index-AbCd1234.js"
  printf 'export const x=1\n' >"$1/site/assets/lazy-IjKl9012.js"
  printf 'body{}\n' >"$1/site/assets/index-EfGh5678.css"
  printf '{"revision":"%s"}\n' "$REV" >"$1/site/version.json"
  printf 'User-agent: *\n' >"$1/site/robots.txt"
}
case_() { # <이름> <기대 pass|reject> <준비 명령(site 안에서)>
  local name=$1 want=$2 d="$W/$1" img="$T-$1:local" c1 c2 cid
  mkdir -p "$d" && site "$d" && (cd "$d/site" && eval "$3") || { bad "$name 준비"; return; }
  printf 'FROM scratch\nCOPY site/ /site/\nCMD ["/not-runnable"]\n' >"$d/Dockerfile"
  docker build -q --platform linux/amd64 --label "org.opencontainers.image.revision=$REV" -t "$img" "$d" >/dev/null || { bad "$name 빌드"; return; }
  bash /src/.github/scripts/image/check-front.sh "$img" "$REV" >"$d/c1.out" 2>&1 && c1=pass || c1=reject
  mkdir -p "$d/out"
  cid="$(docker create --platform linux/amd64 "$img")" && docker cp -q "$cid:/site" "$d/out/" && docker rm -f "$cid" >/dev/null
  bash /src/infra/deploy/deploy-front.sh --check-dir "$d/out/site" "$REV" >"$d/c2.out" 2>&1 && c2=pass || c2=reject
  if [[ $c1 == "$want" && $c2 == "$want" ]]; then ok "$name: 둘 다 $want"; else bad "$name: 기대 $want, CI $c1, 서버 $c2"; cat "$d/c1.out" "$d/c2.out"; fi
}

case_ good pass 'true'
case_ public-dir pass 'mkdir -p img && printf x >img/logo.png'
case_ symlink reject 'ln -s index.html link.html'
case_ hardlink reject 'ln assets/index-EfGh5678.css same.css'
case_ dotfile reject 'printf x >.hidden.txt'
case_ space-name reject 'printf x >"a b.txt"'
case_ newline-name reject 'printf x >"$(printf "a\nb.txt")"'
case_ utf8-name reject 'printf x >"한글.txt"'
case_ bad-ext reject 'printf x >run.sh'
case_ sourcemap reject 'printf x >assets/index-AbCd1234.js.map'
case_ ts-source reject 'printf x >main.ts'
case_ package-json reject 'printf "{}" >package.json'
case_ assets-subdir reject 'mkdir assets/x && printf x >assets/x/y-abcdefgh.js'
case_ unhashed-asset reject 'printf x >assets/plain.js'
case_ version-mismatch reject 'printf "{\"revision\":\"%s\"}\n" ffffffffffffffffffffffffffffffffffffffff >version.json'
case_ no-index reject 'rm index.html'
case_ no-js-ref reject 'printf "<link rel=\"stylesheet\" href=\"/assets/index-EfGh5678.css\">\n" >index.html'
case_ missing-ref reject 'rm assets/index-AbCd1234.js'
case_ traversal-ref reject 'printf "<script src=\"/assets/../x.js\"></script>\n" >>index.html && printf x >x.js'
case_ unhashed-ref reject 'printf "<script src=\"/assets/plain.js\"></script>\n" >>index.html'
case_ localhost reject 'printf "fetch(\"http://localhost:8080/api\")\n" >>assets/index-AbCd1234.js'
case_ too-big reject 'head -c 10485761 /dev/zero >big.txt'

echo "passed=$passed failed=$failed"
[[ $failed == 0 ]]
