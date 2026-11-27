#!/usr/bin/env bash
# HomeStamp 화면(React) 배포 실행기(앱 EC2). CD가 화면 전용 SSM 문서로 부른다(런북 docs/runbooks/배포-화면.md).
#
#   deploy-front.sh [--config 파일] <IMAGE_REPOSITORY>@sha256:<64자>   배포
#   deploy-front.sh [--config 파일] --status                           지금 상태 보기
#   deploy-front.sh [--config 파일] --check-dir <site 폴더> <커밋 40자> 파일 계약만 검사(시험·손 확인용, 아무것도 바꾸지 않음)
#
# 이미지는 실행하지 않는다(FROM scratch, /site만). docker create → docker cp → docker rm으로 파일만 꺼낸다.
# 백엔드와 잠금 파일만 같이 쓰고 설정·상태는 따로 둔다. 앱 컨테이너·nginx 설정·DB·Migration 비밀 폴더는 건드리지 않는다.
#
# 자리(FRONT_ROOT, nginx가 읽기 전용으로 묶어 낸다)
#   releases/<배포 ID>/   index.html·version.json·public 파일(해시 없는 파일). 설치한 뒤에는 바꾸지 않는다
#   assets/               Vite가 만든 해시 이름 파일의 공용 폴더. 같은 이름은 같은 내용일 때만 다시 쓰고 덮어쓰지 않는다
#   current               releases/<배포 ID>를 가리키는 링크. 같은 파일시스템에서 mv -T로 한 번에 바꾼다
#   .incoming/            꺼낸 파일을 검사하는 곳(검사 전 파일은 releases·assets에 두지 않는다)
# 상태(STATE_DIR): in-progress(끝나지 않은 배포), last-good, releases.log, commits, manifests/<ID>.manifest, history.jsonl, last.json
#
# 순서: 잠금 → 앞 배포 상태 정리(끝나지 않았으면 확인해 마무리하거나 4) → 이미지 받기·확인 → 같은 이미지면 확인 뒤 noop
#       → 꺼내기·파일 검사 → 공용 assets 충돌 확인 → assets 설치 → 릴리스 설치 → current 전환 → HTTPS 확인
#       (실패하면 이전 current로 되돌리고 HTTPS로 다시 확인) → 기록 → 정리(보존 범위 밖만)
#
# 종료 코드: 0 성공·noop | 2 인자·설정 | 3 다른 배포 중 | 4 상태를 확정할 수 없음(아무것도 바꾸지 않음, 운영자 확인)
#            10 이미지 | 15 current 전환 실패(그대로) | 16 확인 실패 → 되돌리고 되돌린 상태를 확인함
#            17 되돌린 상태를 확인하지 못함(운영자 확인) | 22 파일 계약 위반 | 23 이미 있는 것과 충돌(같은 이름 다른 내용,
#            같은 커밋 다른 digest) | 24 크기·디스크 제한 | 129·130·143 신호로 중단
set -euo pipefail
set +x
umask 022
# 이름 검사·정렬·정규식이 로케일에 따라 달라지지 않게(CI check-front.sh와 같게)
export LC_ALL=C

readonly EXIT_USAGE=2 EXIT_BUSY=3 EXIT_STATE=4 EXIT_IMAGE=10 EXIT_SWITCH=15 EXIT_VERIFY=16 EXIT_ROLLBACK=17
readonly EXIT_CONTENT=22 EXIT_CONFLICT=23 EXIT_SPACE=24
readonly ID_RE='^[0-9]{8}T[0-9]{6}Z-[0-9]+$'
# Vite 결과의 이름: <이름>-<해시 8자>.<확장자> (assets/ 바로 아래, 하위 폴더 없음)
readonly ASSET_RE='^[A-Za-z0-9._-]+-[A-Za-z0-9_-]{8}\.(js|css|woff2|woff|svg|png|jpg|webp|ico)$'
# check-front.sh와 같은 허용 종류(같은 이미지로 두 검사를 맞추는 시험: infra/deploy/test/front-test.sh F12)
readonly EXT_RE='\.(html|js|css|woff2|woff|svg|png|jpg|webp|ico|txt|webmanifest)$'

log() { printf '%s deploy-front: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() {
  local code=$1
  shift
  log "실패: $*" >&2
  exit "$code"
}

here="$(cd "$(dirname "$0")" && pwd)"
config="$here/deploy-front.env"
mode=deploy
image=""
check_dir=""
check_rev=""
while (($#)); do
  case "$1" in
    --config)
      (($# >= 2)) || die $EXIT_USAGE "--config 뒤에 파일이 없습니다"
      config=$2
      shift 2
      ;;
    --status)
      mode=status
      shift
      ;;
    --check-dir)
      (($# >= 3)) || die $EXIT_USAGE "--check-dir <site 폴더> <커밋>"
      mode=check
      check_dir=$2
      check_rev=$3
      shift 3
      ;;
    -*) die $EXIT_USAGE "모르는 옵션입니다" ;;
    *)
      [[ -z $image ]] || die $EXIT_USAGE "이미지는 하나만 줍니다"
      image=$1
      shift
      ;;
  esac
done

# ---- 설정: KEY=value 줄만 읽는다(source 하지 않음). 비밀값은 넣지 않는 파일이다
KEYS="IMAGE_REPOSITORY FRONT_ROOT STATE_DIR LOCK_FILE VERIFY_URL VERIFY_RESOLVE VERIFY_TIMEOUT KEEP_RELEASES KEEP_EXPOSED
MAX_FILES MAX_FILE_BYTES MAX_RELEASE_BYTES MIN_FREE_BYTES"
FRONT_ROOT=/srv/homestamp/front
STATE_DIR=/var/lib/homestamp/deploy-front
LOCK_FILE=/run/lock/homestamp-deploy.lock
VERIFY_TIMEOUT=20
KEEP_RELEASES=3
KEEP_EXPOSED=2
# 실제 빌드(2026-11-27)는 파일 96개·약 4MB·가장 큰 파일 755KB. 넉넉히 두되 끝을 둔다(check-front.sh와 같은 값)
MAX_FILES=2000
MAX_FILE_BYTES=10485760
MAX_RELEASE_BYTES=52428800
# 꺼내기 전 FRONT_ROOT 파일시스템에 남아 있어야 할 공간(이미지 크기의 두 배를 더 본다). EBS 20GB 기준
MIN_FREE_BYTES=1073741824
IMAGE_REPOSITORY=""
VERIFY_URL=""
VERIFY_RESOLVE=""

if [[ $mode != check ]]; then
  [[ -f $config ]] || die $EXIT_USAGE "설정 파일이 없습니다: $config"
  while IFS= read -r line || [[ -n $line ]]; do
    [[ -z $line || $line == \#* ]] && continue
    [[ $line =~ ^([A-Z_]+)=(.*)$ ]] || die $EXIT_USAGE "설정 줄 형식이 아닙니다(KEY=value)"
    key=${BASH_REMATCH[1]}
    case " ${KEYS//$'\n'/ } " in
      *" $key "*) printf -v "$key" '%s' "${BASH_REMATCH[2]}" ;;
      *) die $EXIT_USAGE "모르는 설정입니다: $key" ;;
    esac
  done <"$config"
fi

check() { [[ $2 =~ $3 ]] || die $EXIT_USAGE "$1 값이 형식에 맞지 않습니다"; }
for key in VERIFY_TIMEOUT KEEP_RELEASES KEEP_EXPOSED; do check "$key" "${!key}" '^[1-9][0-9]{0,2}$'; done
for key in MAX_FILES MAX_FILE_BYTES MAX_RELEASE_BYTES MIN_FREE_BYTES; do check "$key" "${!key}" '^[1-9][0-9]{0,11}$'; done

# ---- 파일 계약(check-front.sh와 같은 규칙 + 서버 설치용으로 더 좁힌 것). 바꾸지 않고 보기만 한다. 위반이면 22
check_tree() { # <site 폴더> <커밋>
  local s=$1 rev=$2 x refs r n total
  [[ $rev =~ ^[0-9a-f]{40}$ ]] || die $EXIT_CONTENT "커밋은 40자 소문자 16진수입니다"
  [[ -d $s && ! -L $s ]] || die $EXIT_CONTENT "site 폴더가 없습니다"
  # 이름을 먼저 본다: 영문·숫자·._- 만(줄바꿈·공백·따옴표가 경로·출력에 들어가지 못하게), 점으로 시작하는 이름 금지
  x="$(find "$s" -mindepth 1 \( -name '*[!A-Za-z0-9._-]*' -o -name '.*' \) -print -quit)"
  [[ -z $x ]] || die $EXIT_CONTENT "허용하지 않는 이름: $(printf '%q' "${x#"$s"/}")"
  x="$(find "$s" -mindepth 1 ! -type f ! -type d -print -quit)"
  [[ -z $x ]] || die $EXIT_CONTENT "일반 파일·폴더가 아닌 것(링크·장치·FIFO 등): ${x#"$s"/}"
  x="$(find "$s" -type f -links +1 -print -quit)"
  [[ -z $x ]] || die $EXIT_CONTENT "하드링크: ${x#"$s"/}"
  x="$(find "$s" \( -name '*.map' -o -name node_modules -o -name '*.ts' -o -name '*.tsx' -o -name 'package*.json' \) -print -quit)"
  [[ -z $x ]] || die $EXIT_CONTENT "들어가면 안 되는 파일: ${x#"$s"/}"
  x="$(cd "$s" && find . -type f ! -path ./version.json -print | grep -vE "$EXT_RE" | head -n 1 || true)"
  [[ -z $x ]] || die $EXIT_CONTENT "정해 두지 않은 파일 종류: $x"
  [[ -f $s/index.html ]] || die $EXIT_CONTENT "index.html이 없습니다"
  [[ "$(cat "$s/version.json" 2>/dev/null)" == "{\"revision\":\"$rev\"}" ]] || die $EXIT_CONTENT "version.json이 커밋과 맞지 않습니다"
  if [[ -e $s/assets ]]; then
    x="$(find "$s/assets" -mindepth 1 -type d -print -quit)"
    [[ -z $x ]] || die $EXIT_CONTENT "assets/ 아래에 폴더가 있습니다"
    x="$(find "$s/assets" -mindepth 1 -type f -printf '%f\n' | grep -vE "$ASSET_RE" | head -n 1 || true)"
    [[ -z $x ]] || die $EXIT_CONTENT "assets/의 파일 이름이 해시 이름이 아닙니다: $x"
  fi
  # index.html의 "/assets/…" 참조는 모두 assets/ 바로 아래 해시 이름(경로·.. 없음)이고 그 파일이 있어야 한다
  refs="$(grep -oE '"/assets/[^"]*"' "$s/index.html" | tr -d '"' | sed 's|^/||' || true)"
  grep -qE '^assets/[^/]+\.js$' <<<"$refs" || die $EXIT_CONTENT "index.html이 /assets의 JS를 부르지 않습니다"
  grep -qE '^assets/[^/]+\.css$' <<<"$refs" || die $EXIT_CONTENT "index.html이 /assets의 CSS를 부르지 않습니다"
  while read -r r; do
    [[ ${r#assets/} =~ $ASSET_RE && -f $s/$r ]] || die $EXIT_CONTENT "index.html의 /assets 참조가 해시 이름이 아니거나 없습니다: $(printf '%q' "$r")"
  done <<<"$refs"
  ! grep -rqE 'https?://(localhost|127\.0\.0\.1)' "$s" || die $EXIT_CONTENT "개발용 API 주소(localhost)가 들어 있습니다"
  n="$(find "$s" -type f | wc -l)"
  ((n <= MAX_FILES)) || die $EXIT_SPACE "파일이 너무 많습니다($n > $MAX_FILES)"
  x="$(find "$s" -type f -size +"${MAX_FILE_BYTES}"c -print -quit)"
  [[ -z $x ]] || die $EXIT_SPACE "파일 하나가 너무 큽니다: ${x#"$s"/}"
  total="$(find "$s" -type f -printf '%s\n' | awk '{t+=$1} END{print t+0}')"
  ((total <= MAX_RELEASE_BYTES)) || die $EXIT_SPACE "전체 크기가 너무 큽니다($total > $MAX_RELEASE_BYTES)"
}

for tool in find mv; do
  # head로 파이프를 일찍 닫으면 pipefail에서 SIGPIPE(141)로 가끔 실패하므로 출력을 다 받은 뒤 본다
  ver="$("$tool" --version 2>/dev/null || true)"
  [[ ${ver%%$'\n'*} == *GNU* ]] || die $EXIT_USAGE "GNU ${tool}이 필요합니다(-printf, mv -T)"
done
if [[ $mode == check ]]; then
  check_tree "$check_dir" "$check_rev"
  echo "check-dir: 통과"
  exit 0
fi

check IMAGE_REPOSITORY "$IMAGE_REPOSITORY" '^[a-z0-9]([a-z0-9._:-]*[a-z0-9])?(/[a-z0-9]([a-z0-9._-]*[a-z0-9])?)+$'
for key in FRONT_ROOT STATE_DIR LOCK_FILE; do check "$key" "${!key}" '^/[A-Za-z0-9._/-]+$'; done
# 백엔드 deploy.sh의 상태 폴더를 같이 쓰면 서로의 in-progress를 중단으로 기록한다
[[ ${STATE_DIR%/} != /var/lib/homestamp/deploy ]] || die $EXIT_USAGE "STATE_DIR은 백엔드(/var/lib/homestamp/deploy)와 달라야 합니다"
# 확인은 실제 도메인·인증서로(TLS 검증을 끄지 않음), 이 서버의 nginx(127.0.0.1)로
check VERIFY_URL "$VERIFY_URL" '^https://[a-z0-9.-]+(:[0-9]+)?$'
[[ $VERIFY_URL =~ ^https://([a-z0-9.-]+)(:([0-9]+))?$ ]]
url_hostport="${BASH_REMATCH[1]}:${BASH_REMATCH[3]:-443}"
check VERIFY_RESOLVE "$VERIFY_RESOLVE" '^[a-z0-9.-]+:[0-9]+:127\.0\.0\.1$'
[[ ${VERIFY_RESOLVE%:127.0.0.1} == "$url_hostport" ]] || die $EXIT_USAGE "VERIFY_RESOLVE의 이름·포트가 VERIFY_URL과 다릅니다"

for tool in docker curl flock sha256sum tar df; do
  command -v "$tool" >/dev/null || die $EXIT_USAGE "$tool 명령이 없습니다"
done

releases="$FRONT_ROOT/releases"
pool="$FRONT_ROOT/assets"
current="$FRONT_ROOT/current"
incoming="$FRONT_ROOT/.incoming"
manifests="$STATE_DIR/manifests"

current_target() { # current 링크 값(raw). 없으면 -, 링크가 아니면 !
  if [[ -L $current ]]; then
    readlink "$current"
  elif [[ -e $current ]]; then
    echo '!'
  else
    echo -
  fi
}
managed_id() { # <링크 값> → 우리가 설치한 릴리스면 ID, 아니면 빈 값
  local id=${1#releases/}
  [[ $1 == releases/* && $id =~ $ID_RE && -f $manifests/$id.manifest ]] && echo "$id" || true
}
manifest_ok() { [[ -f $manifests/$1.manifest && "$(tail -n 1 "$manifests/$1.manifest")" == end ]]; }
mf() { awk -v k="$2" '$1==k{print $2; exit}' "$manifests/$1.manifest"; } # <ID> <키>
last_good() { if [[ -f $STATE_DIR/last-good ]]; then cat "$STATE_DIR/last-good"; else echo -; fi; }

if [[ $mode == status ]]; then
  [[ -z $image ]] || die $EXIT_USAGE "--status에는 이미지를 주지 않습니다"
  echo "current: $(current_target)"
  echo "last-good: $(last_good)"
  lg="$(last_good)"
  if [[ $lg != - ]] && manifest_ok "$lg"; then echo "last-good commit: $(mf "$lg" commit) digest: $(mf "$lg" digest)"; fi
  if [[ -f $STATE_DIR/in-progress ]]; then echo "in-progress(끝나지 않은 배포): $(tr '\n' ' ' <"$STATE_DIR/in-progress")"; fi
  if [[ -f $STATE_DIR/last.json ]]; then echo "last: $(cat "$STATE_DIR/last.json")"; fi
  echo "releases: $(find "$releases" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | wc -l | tr -d ' ') assets: $(find "$pool" -type f 2>/dev/null | wc -l | tr -d ' ')"
  exit 0
fi

[[ -n $image ]] || die $EXIT_USAGE "배포할 이미지가 없습니다"
[[ $image == "$IMAGE_REPOSITORY@sha256:"* && ${image#"$IMAGE_REPOSITORY@sha256:"} =~ ^[0-9a-f]{64}$ ]] ||
  die $EXIT_USAGE "이미지는 $IMAGE_REPOSITORY@sha256:<64자>만 받습니다(태그만으로는 배포하지 않음)"
digest=${image#"$IMAGE_REPOSITORY@"}

# ---- 잠금: 백엔드 deploy.sh와 같은 파일. 기다리지 않고 거절한다
mkdir -p "$(dirname "$LOCK_FILE")"
exec 9>>"$LOCK_FILE"
flock -n 9 || die $EXIT_BUSY "다른 배포(백엔드 또는 화면)가 진행 중입니다"

# root가 쓰고 지우는 자리: 위에서부터 있는 단계마다 링크가 아니고 root 소유이며 그룹·남이 쓸 수 없어야 한다
safe_path() { # <절대 경로>
  local p="" part mode
  local -a parts
  IFS=/ read -ra parts <<<"${1#/}"
  for part in "" "${parts[@]}"; do
    p="${p%/}/$part"
    [[ -e $p || -L $p ]] || return 0
    [[ ! -L $p && -d $p ]] || die $EXIT_USAGE "$p 가 폴더가 아니거나 링크입니다(root 소유 폴더여야 함)"
    mode="$(stat -c '%a' "$p")"
    [[ "$(stat -c '%u' "$p")" == 0 ]] && ! ((8#$mode & 8#022)) || die $EXIT_USAGE "$p 의 소유자·권한이 다릅니다(root, 그룹·남 쓰기 없음)"
  done
}
for d in "$FRONT_ROOT" "$releases" "$pool" "$incoming" "$STATE_DIR" "$manifests"; do safe_path "$d"; done
mkdir -p "$STATE_DIR" "$manifests" "$releases" "$pool" "$incoming"
for d in "$FRONT_ROOT" "$releases" "$pool" "$incoming" "$STATE_DIR" "$manifests"; do safe_path "$d"; done
chmod 0750 "$STATE_DIR"
chmod 0755 "$FRONT_ROOT" "$releases" "$pool"
chmod 0700 "$incoming"
history="$STATE_DIR/history.jsonl"
deploy_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
step=state
result=failed
commit=-
prev=-
prev_id=""
rolled_back=none
gc=none
own_inprogress=0
keep_inprogress=0
phase=""
interrupted=0
cid=""
tmpd="$(mktemp -d)"

append() {
  printf '%s\n' "$1" >>"$history"
  chmod 0640 "$history"
}
put() { # <파일> <내용>: 같은 폴더의 임시 파일에 쓰고 한 번에 바꾼다
  printf '%s\n' "$2" >"$1.tmp.$$" && mv -T "$1.tmp.$$" "$1"
}
write_inprogress() { # <phase>
  phase=$1
  put "$STATE_DIR/in-progress" "$(printf 'id=%s\nimage=%s\ncommit=%s\nprev=%s\nphase=%s' "$deploy_id" "$image" "$commit" "$prev" "$phase")"
  own_inprogress=1
}
record() { # <id> <image> <result> <exit> <failed_step> <note>
  append "$(printf '{"deploy_id":"%s","image":"%s","commit":"%s","started_at":"%s","finished_at":"%s","result":"%s","exit_code":%d,"failed_step":%s,"prev":"%s","current":"%s","rolled_back":"%s","gc":"%s","note":"%s"}' \
    "$1" "$2" "$commit" "$started_at" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$3" "$4" "$5" "$prev" "$(current_target)" "$rolled_back" "$gc" "$6")"
}
switch_to() { # <링크 값 또는 ->: current를 한 번에 바꾼다(- 이면 지운다)
  local tmpl="$FRONT_ROOT/.current.tmp.$$"
  if [[ $1 == - ]]; then
    rm -f "$current"
  else
    ln -sfn "$1" "$tmpl" && mv -T "$tmpl" "$current"
  fi
}

finish() {
  local rc=$? failed_step=null cur
  trap - EXIT
  set +e
  [[ -z $cid ]] || docker rm -f "$cid" >/dev/null 2>&1
  rm -rf "${incoming:?}/$deploy_id" "$releases/.tmp-$deploy_id" "$tmpd"
  rm -f "$pool"/.tmp."$deploy_id".* "$FRONT_ROOT/.current.tmp.$$"
  if ((rc == 0)); then
    [[ $result == noop ]] || result=success
  elif [[ $phase == confirmed ]]; then
    # 확정 뒤(정리·기록 중) 실패·신호: 배포는 그대로 두고 실패 코드만 남긴다. in-progress는 지운다(상태가 일치함)
    ((interrupted)) && result=interrupted || result=failed
    log "새 판($deploy_id)은 확정됐고(current = last-good) 그 뒤 단계($step)에서 멈췄습니다. 되돌리지 않습니다"
  elif ((interrupted)); then
    result=interrupted
    cur="$(current_target)"
    # 전환 뒤에 멈추면: 이전 current로 링크만 되돌리고(확인은 다음 실행이 한다) in-progress를 남긴다
    if [[ $phase == switched || $phase == rollback ]]; then
      if [[ $cur == "releases/$deploy_id" ]] && switch_to "$prev"; then
        rolled_back=unverified
        write_inprogress rollback
      fi
      keep_inprogress=1
    fi
  else
    result=failed
    cur="$(current_target)"
    # 전환 뒤 예상하지 못한 실패(기록 쓰기 실패·디스크 등): 새 판이 나가고 있으면 신호 때와 같이 이전으로 링크만 돌리고
    # in-progress를 남긴다(다음 실행이 실제 응답으로 판단). 이미 되돌린 길(16·17)은 위에서 정해 둔 대로
    if [[ ($phase == switched || $phase == rollback) && $cur == "releases/$deploy_id" ]]; then
      if switch_to "$prev"; then
        rolled_back=unverified
        write_inprogress rollback
      fi
      keep_inprogress=1
    elif [[ $phase == rollback && $rolled_back != verified ]]; then
      keep_inprogress=1
    fi
  fi
  ((rc == 0)) || failed_step="\"$step\""
  local rec
  rec="$(printf '{"deploy_id":"%s","image":"%s","commit":"%s","started_at":"%s","finished_at":"%s","result":"%s","exit_code":%d,"failed_step":%s,"prev":"%s","current":"%s","rolled_back":"%s","gc":"%s"}' \
    "$deploy_id" "$image" "$commit" "$started_at" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$result" "$rc" "$failed_step" "$prev" "$(current_target)" "$rolled_back" "$gc")"
  append "$rec"
  put "$STATE_DIR/last.json" "$rec"
  if ((own_inprogress && !keep_inprogress)); then rm -f "$STATE_DIR/in-progress"; fi
  log "끝: result=$result exit=$rc active=$(current_target)"
  exit "$rc"
}
trap finish EXIT
trap 'interrupted=1; exit 143' TERM
trap 'interrupted=1; exit 130' INT
trap 'interrupted=1; exit 129' HUP

log "시작: $deploy_id $image"

# ---- HTTPS 확인: 이 서버의 nginx를 실제 도메인·인증서로 거친다(-k 없음). 상태 코드만이 아니라 내용(SHA-256)·종류를 본다
fetch() { # <경로> → $tmpd/body, $tmpd/hdr, 상태 코드 출력
  curl -sS --proto =https --max-time 5 --resolve "$VERIFY_RESOLVE" -o "$tmpd/body" -D "$tmpd/hdr" -w '%{http_code}' \
    "$VERIFY_URL$1" 2>/dev/null || echo 000
}
hdr() { tr -d '\r' <"$tmpd/hdr" | awk -v k="$1" 'tolower($0) ~ "^"tolower(k)":" {sub(/^[^:]*:[ \t]*/,""); print; exit}'; }
body_sha() { sha256sum "$tmpd/body" | cut -d' ' -f1; }
why=""
check_release_once() { # <ID>: 그 릴리스가 nginx로 그대로 나가는지
  local id=$1 rev code
  rev="$(mf "$id" commit)"
  code="$(fetch /version.json)"
  [[ $code == 200 ]] || { why="version.json $code"; return 1; }
  [[ "$(hdr content-type)" == application/json* ]] || { why="version.json 종류 $(hdr content-type)"; return 1; }
  [[ "$(hdr cache-control)" == *no-store* ]] || { why="version.json no-store 없음"; return 1; }
  [[ "$(cat "$tmpd/body")" == "{\"revision\":\"$rev\"}" ]] || { why="version.json 판이 다름"; return 1; }
  code="$(fetch /)"
  [[ $code == 200 && "$(hdr content-type)" == text/html* ]] || { why="/ $code $(hdr content-type)"; return 1; }
  [[ "$(body_sha)" == "$(awk '$1=="file" && $3=="index.html"{print $2}' "$manifests/$id.manifest")" ]] || { why="index.html 내용이 다름"; return 1; }
  check_assets_once "$id"
}
check_assets_once() { # <ID>: 그 릴리스의 assets(동적 chunk 포함)가 공용 폴더에서 같은 내용·종류로 나가는지
  local code sha name ct
  while read -r sha name; do
    code="$(fetch "/assets/$name")"
    [[ $code == 200 ]] || { why="assets/$name $code"; return 1; }
    [[ "$(body_sha)" == "$sha" ]] || { why="assets/$name 내용이 다름"; return 1; }
    ct="$(hdr content-type)"
    case "$name" in
      *.js) [[ $ct == application/javascript* || $ct == text/javascript* ]] || { why="assets/$name 종류 $ct"; return 1; } ;;
      *.css) [[ $ct == text/css* ]] || { why="assets/$name 종류 $ct"; return 1; } ;;
    esac
  done < <(awk '$1=="asset"{print $2, $3}' "$manifests/$1.manifest")
  # 없는 asset은 404여야 한다(index.html로 대신 답하면 브라우저가 HTML을 JS로 받는다)
  code="$(fetch "/assets/hs-missing-$deploy_id-zzzzzzzz.js")"
  [[ $code == 404 ]] || { why="없는 assets가 $code"; return 1; }
}
retry() { # <함수> <인자...>: VERIFY_TIMEOUT 안에서 다시 묻는다
  local deadline=$((SECONDS + VERIFY_TIMEOUT))
  until "$@"; do
    if ((SECONDS >= deadline)); then
      log "확인 실패($*): $why"
      return 1
    fi
    sleep 1
  done
}
check_unmanaged_once() { # <링크 값>: 우리가 설치하지 않은 상태(없음·처음 자리표시)로 돌아갔는지
  [[ "$(current_target)" == "$1" ]] || { why="current가 $(current_target)"; return 1; }
  local code
  code="$(fetch /version.json)"
  if [[ $1 == - ]]; then
    [[ $code == 404 ]] || { why="current가 없는데 version.json $code"; return 1; }
  else
    [[ $code != 200 || "$(cat "$tmpd/body")" != "{\"revision\":\"$commit\"}" ]] || { why="아직 새 판이 나감"; return 1; }
  fi
}
check_target() { # <링크 값>: 그 상태가 실제로 나가는지(우리 릴리스면 내용까지)
  local id
  id="$(managed_id "$1")"
  if [[ -n $id ]]; then
    manifest_ok "$id" || { why="manifest 손상($id)"; return 1; }
    [[ "$(current_target)" == "$1" ]] || { why="current가 $(current_target)"; return 1; }
    check_release_once "$id"
  else
    check_unmanaged_once "$1"
  fi
}

# 끝나지 않은 배포를 성공으로 마무리할 때도 일반 배포와 같은 기준: 이전 릴리스(우리가 설치한 것)의 assets도 그대로 나가야 한다.
# 이전 manifest를 읽을 수 없으면 확인할 수 없으므로 통과로 보지 않는다(→ 4)
resolve_prev_assets_ok() { # <이전 링크 값>
  local pid
  pid="$(managed_id "$1")"
  [[ -z $pid ]] && return 0
  manifest_ok "$pid" || { log "이전 릴리스($pid) manifest가 없거나 손상돼 이전 assets를 확인할 수 없습니다"; return 1; }
  retry check_assets_once "$pid"
}

# ---- 0. 앞 배포 상태. 끝나지 않은 배포가 있으면 실제 응답으로 어느 쪽인지 확인해 마무리하고, 모르면 4(바꾸지 않음)
step=resolve
rm -rf "${incoming:?}"/* "$releases"/.tmp-* "$pool"/.tmp.* "$FRONT_ROOT"/.current.tmp.* 2>/dev/null || true
# SIGKILL 등으로 남은 꺼내기 컨테이너(실행한 적 없음)도 지운다(잠금 안이라 다른 화면 배포의 것이 아님)
left="$(docker ps -aq --filter 'name=^homestamp-front-extract-' 2>/dev/null || true)"
[[ -z $left ]] || docker rm -f $left >/dev/null 2>&1 || true
cur="$(current_target)"
[[ $cur != '!' ]] || die $EXIT_STATE "current가 링크가 아닙니다(손으로 만든 폴더?). 바꾸지 않습니다"
lg="$(last_good)"
if [[ -f $STATE_DIR/in-progress ]]; then
  ip="$(cat "$STATE_DIR/in-progress")"
  ip_id="$(awk -F= '$1=="id"{print $2}' <<<"$ip")"
  ip_prev="$(awk -F= '$1=="prev"{print $2}' <<<"$ip")"
  ip_phase="$(awk -F= '$1=="phase"{print $2}' <<<"$ip")"
  ip_image="$(awk -F= '$1=="image"{print $2}' <<<"$ip")"
  ip_commit="$(awk -F= '$1=="commit"{print $2}' <<<"$ip")"
  [[ $ip_id =~ $ID_RE && -n $ip_prev && $ip_phase =~ ^(prepare|switched|rollback)$ ]] ||
    die $EXIT_STATE "끝나지 않은 배포 기록(in-progress)이 손상됐습니다. 바꾸지 않습니다(런북 \"상태를 확정할 수 없음\")"
  log "끝나지 않은 이전 배포($ip_id, $ip_phase)가 있습니다. 실제 응답으로 확인합니다"
  prev=$ip_prev
  commit=$ip_commit
  if [[ $ip_phase == prepare && $cur == "$ip_prev" ]]; then
    record "$ip_id" "$ip_image" interrupted 143 '"install"' "전환 전에 멈춤(바뀐 것 없음, 다음 배포가 확인)"
  elif [[ $ip_phase == switched && $cur == "releases/$ip_id" ]] && manifest_ok "$ip_id" && retry check_target "$cur" &&
    resolve_prev_assets_ok "$ip_prev"; then
    printf '%s success\n' "$ip_id" >>"$STATE_DIR/releases.log"
    put "$STATE_DIR/last-good" "$ip_id"
    lg=$ip_id
    record "$ip_id" "$ip_image" success 0 null "기록 전에 멈췄지만 다음 배포가 확인해 마무리"
  elif [[ $cur == "$ip_prev" ]] && retry check_target "$cur"; then
    [[ $ip_phase == prepare ]] || printf '%s exposed\n' "$ip_id" >>"$STATE_DIR/releases.log"
    record "$ip_id" "$ip_image" interrupted 143 "\"$ip_phase\"" "이전 current가 나가는 것을 확인함"
  else
    die $EXIT_STATE "끝나지 않은 배포($ip_id, $ip_phase)의 상태를 확인하지 못했습니다(current=$cur). 바꾸지 않습니다(런북 \"상태를 확정할 수 없음\")"
  fi
  rm -f "$STATE_DIR/in-progress"
  commit=-
  cur="$(current_target)"
fi
if [[ $lg != - ]]; then
  [[ $cur == "releases/$lg" ]] || die $EXIT_STATE "current($cur)가 마지막 정상 릴리스($lg)가 아닙니다. 바꾸지 않습니다"
  manifest_ok "$lg" || die $EXIT_STATE "마지막 정상 릴리스의 manifest가 없거나 손상됐습니다. 바꾸지 않습니다"
else
  [[ -z "$(managed_id "$cur")" ]] || die $EXIT_STATE "마지막 정상 기록 없이 current가 설치한 릴리스($cur)를 가리킵니다. 바꾸지 않습니다"
fi
prev=$cur
prev_id="$(managed_id "$cur")"

# ---- 1. 이미지: digest로 받고, 받은 이미지가 그 digest·linux/amd64·레이어 하나·커밋 라벨인지
step=image
docker pull -q --platform linux/amd64 "$image" >/dev/null || die $EXIT_IMAGE "이미지를 받지 못했습니다"
docker image inspect -f '{{range .RepoDigests}}{{println .}}{{end}}' "$image" | grep -qxF "$image" ||
  die $EXIT_IMAGE "받은 이미지의 digest가 다릅니다"
[[ "$(docker image inspect -f '{{.Os}}/{{.Architecture}}' "$image")" == linux/amd64 ]] || die $EXIT_IMAGE "linux/amd64 이미지가 아닙니다"
[[ "$(docker image inspect -f '{{len .RootFS.Layers}}' "$image")" == 1 ]] || die $EXIT_IMAGE "레이어가 하나가 아닙니다(scratch 운반 이미지가 아님)"
commit="$(docker image inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$image" 2>/dev/null || true)"
[[ $commit =~ ^[0-9a-f]{40}$ ]] || {
  commit=-
  die $EXIT_IMAGE "커밋 라벨(org.opencontainers.image.revision)이 없습니다"
}
size="$(docker image inspect -f '{{.Size}}' "$image")"
[[ $size =~ ^[0-9]+$ ]] && ((size <= MAX_RELEASE_BYTES)) || die $EXIT_SPACE "이미지가 너무 큽니다($size > $MAX_RELEASE_BYTES)"

# 같은 커밋은 같은 digest여야 한다(sha-<커밋> 태그를 덮어쓰지 않는 발행 계약). 릴리스를 지워도 commits에 남는다
known="$(awk -v c="$commit" '$1==c{print $2; exit}' "$STATE_DIR/commits" 2>/dev/null || true)"
[[ -z $known || $known == "$digest" ]] || die $EXIT_CONFLICT "커밋 ${commit}은 이미 다른 digest($known)로 설치했습니다"

# ---- 2. 같은 이미지가 이미 정상 릴리스면: 실제 응답을 확인한 뒤에만 noop, 아니면 4(바꾸지 않음)
if [[ $lg != - && "$(mf "$lg" digest)" == "$digest" ]]; then
  step=noop-check
  retry check_target "releases/$lg" || die $EXIT_STATE "같은 이미지가 정상 릴리스($lg)인데 HTTPS 확인을 통과하지 못했습니다. noop으로 보지 않고 바꾸지 않습니다(런북 \"같은 이미지인데 확인 실패\")"
  result=noop
  log "같은 이미지가 이미 나가고 있고 확인을 통과했습니다. 새로 배포하지 않습니다"
  exit 0
fi

# ---- 2-1. 지금 나가는 상태(되돌릴 대상)가 HTTPS로 확인되는지 먼저 본다. 이미 깨져 있으면 전환해도 되돌린 상태를 확인할 수 없으므로
#           바꾸지 않는다(4). 전환한 뒤에 깨지면 되돌린 상태 확인이 실패해 17(복구 성공으로 기록하지 않음)
step=precheck
retry check_target "$cur" || die $EXIT_STATE "지금 나가는 상태($cur)가 HTTPS 확인을 통과하지 못했습니다. 되돌릴 대상을 확인할 수 없어 바꾸지 않습니다(런북 \"지금 상태 확인 실패\")"

# ---- 3. 꺼내기: 실행하지 않는 컨테이너 → .incoming(같은 파일시스템, root만) → 검사. 이미지 크기로 쓰는 양의 끝을 먼저 본다
step=extract
free="$(df -PB1 "$FRONT_ROOT" | awk 'NR==2{print $4}')"
[[ $free =~ ^[0-9]+$ ]] && ((free >= MIN_FREE_BYTES + 2 * size)) || die $EXIT_SPACE "디스크 공간이 모자랍니다(남은 ${free:-?}바이트)"
write_inprogress prepare
cid="$(docker create --platform linux/amd64 --network none --name "homestamp-front-extract-$deploy_id" "$image")" ||
  die $EXIT_IMAGE "꺼내기용 컨테이너를 만들지 못했습니다"
# 이미지 전체가 /site 밖에 아무것도 두지 않는지(check-front.sh와 같은 기준)
# 목록을 파일로 받고 실패를 본다(읽기 실패를 "밖에 아무것도 없음"으로 보지 않게)
docker export "$cid" | tar -tf - >"$tmpd/list" || die $EXIT_CONTENT "이미지 파일 목록을 읽지 못했습니다"
extra="$(grep -vE '^(site/.*|site|\.dockerenv|dev/|dev/(console|pts/|shm/)|etc/|etc/(hostname|hosts|mtab|resolv\.conf)|proc/|sys/)$' "$tmpd/list" | head -n 3 || true)"
[[ -z $extra ]] || die $EXIT_CONTENT "/site 밖에 파일이 있습니다: $(printf '%q' "$extra" | cut -c1-200)"
stage="$incoming/$deploy_id"
mkdir -m 0700 "$stage"
docker cp -q "$cid:/site" "$stage/" || die $EXIT_CONTENT "/site를 꺼내지 못했습니다"
docker rm -f "$cid" >/dev/null 2>&1 || true
cid=""
step=check
check_tree "$stage/site" "$commit"

# ---- 4. 공용 assets 충돌: 같은 이름이면 같은 내용이어야 한다(덮어쓰지 않음). 바꾸기 전에 모두 본다
step=conflict
: >"$tmpd/assets"
if [[ -d $stage/site/assets ]]; then
  while IFS= read -r -d '' f; do
    name="${f##*/}"
    sha="$(sha256sum "$f" | cut -d' ' -f1)"
    printf '%s %s\n' "$sha" "$name" >>"$tmpd/assets"
    if [[ -e $pool/$name || -L $pool/$name ]]; then
      [[ -f $pool/$name && ! -L $pool/$name && "$(sha256sum "$pool/$name" | cut -d' ' -f1)" == "$sha" ]] ||
        die $EXIT_CONFLICT "공용 assets/${name}이 이미 다른 내용으로 있습니다(덮어쓰지 않음)"
    fi
  done < <(find "$stage/site/assets" -type f -print0 | sort -z)
fi

# ---- 5. 설치: assets(새 이름만, 임시 이름 → mv) → 릴리스(임시 폴더 → mv). 파일은 새로 만들어 0644·root(꺼낸 권한을 믿지 않음)
step=install
grep -qx "$commit $digest" "$STATE_DIR/commits" 2>/dev/null || printf '%s %s\n' "$commit" "$digest" >>"$STATE_DIR/commits"
while read -r sha name; do
  [[ -e $pool/$name ]] && continue
  tmpf="$pool/.tmp.$deploy_id.$name"
  install -m 0644 "$stage/site/assets/$name" "$tmpf" && mv -T "$tmpf" "$pool/$name" || die $EXIT_SPACE "assets/${name}을 쓰지 못했습니다(디스크?)"
done <"$tmpd/assets"
rel_tmp="$releases/.tmp-$deploy_id"
mkdir -m 0755 "$rel_tmp"
: >"$tmpd/files"
(cd "$stage/site" && find . -mindepth 1 -path ./assets -prune -o -type d -printf '%P\n') | while IFS= read -r d; do
  mkdir -m 0755 "$rel_tmp/$d"
done
while IFS= read -r -d '' rel; do
  install -m 0644 "$stage/site/$rel" "$rel_tmp/$rel" || die $EXIT_SPACE "${rel}을 쓰지 못했습니다(디스크?)"
  printf 'file %s %s\n' "$(sha256sum "$rel_tmp/$rel" | cut -d' ' -f1)" "$rel" >>"$tmpd/files"
done < <(cd "$stage/site" && find . -mindepth 1 -path ./assets -prune -o -type f -printf '%P\0' | sort -z)
{
  printf 'id %s\ncommit %s\ndigest %s\ninstalled_at %s\n' "$deploy_id" "$commit" "$digest" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  awk '{print "asset", $1, $2}' "$tmpd/assets"
  cat "$tmpd/files"
  echo end
} >"$manifests/$deploy_id.manifest.tmp"
mv -T "$manifests/$deploy_id.manifest.tmp" "$manifests/$deploy_id.manifest"
mv -T "$rel_tmp" "$releases/$deploy_id"
rm -rf "${stage:?}"

# ---- 6. 전환: 먼저 in-progress에 "전환함"을 남기고(그 뒤 멈추면 다음 실행이 실제 응답으로 판단) current를 한 번에 바꾼다
step=switch
write_inprogress switched
switch_to "releases/$deploy_id" || die $EXIT_SWITCH "current를 바꾸지 못했습니다(그대로)"

# ---- 7. 확인: 새 판(version·index.html·모든 assets 내용·종류·없는 assets 404)과 이전 릴리스 assets가 그대로 나가는지
step=verify
if retry check_release_once "$deploy_id" && { [[ -z $prev_id ]] || retry check_assets_once "$prev_id"; }; then
  printf '%s success\n' "$deploy_id" >>"$STATE_DIR/releases.log"
  put "$STATE_DIR/last-good" "$deploy_id"
  # 배포 확정 지점: last-good까지 새 판으로 바뀐 뒤에는 되돌리지 않는다(뒤의 정리·기록이 실패하거나 신호를 받아도
  # current = last-good = 새 판으로 둔다. 되돌리면 last-good과 current가 어긋나 다음 실행이 4로 멈춘다)
  phase=confirmed
else
  # 되돌리기: 이전 current(또는 없음)로 링크를 돌리고, 그 상태가 실제로 나가는지 다시 확인한다
  step=rollback
  write_inprogress rollback
  printf '%s exposed\n' "$deploy_id" >>"$STATE_DIR/releases.log"
  if switch_to "$prev" && retry check_target "$prev"; then
    rolled_back=verified
    die $EXIT_VERIFY "새 판 확인에 실패해 이전 상태($prev)로 되돌렸고 확인했습니다. 새 판의 assets는 보존 범위에 남깁니다"
  fi
  rolled_back=unverified
  keep_inprogress=1
  die $EXIT_ROLLBACK "되돌린 상태($prev)를 확인하지 못했습니다. 운영자 확인 필요(런북 \"화면 되돌리기 실패\"). 다음 배포는 이 상태를 먼저 확인합니다"
fi

# ---- 8. 정리: 보존 범위 밖의 릴리스·manifest·assets만 지운다. 판단에 필요한 기록이 손상됐으면 assets는 지우지 않는다
step=gc
gc_run() {
  local keep=() protect=() id n
  mapfile -t keep < <(awk '$2=="success"{print $1}' "$STATE_DIR/releases.log" | tail -n "$KEEP_RELEASES")
  keep+=("$deploy_id")
  [[ -z $prev_id ]] || keep+=("$prev_id")
  mapfile -t protect < <(awk '$2=="exposed"{print $1}' "$STATE_DIR/releases.log" | tail -n "$KEEP_EXPOSED")
  protect+=("${keep[@]}")
  in_list() {
    local x=$1
    shift
    for y in "$@"; do [[ $x == "$y" ]] && return 0; done
    return 1
  }
  # 릴리스 폴더: ID 모양이고 보존 목록 밖인 것만(모르는 이름은 두고 경고)
  for d in "$releases"/*; do
    [[ -e $d ]] || continue
    id="${d##*/}"
    if [[ ! $id =~ $ID_RE ]]; then
      log "경고: releases/${id}는 모르는 이름이라 지우지 않습니다"
      continue
    fi
    in_list "$id" "${keep[@]}" || rm -rf "${releases:?}/$id"
  done
  for m in "$manifests"/*.manifest; do
    [[ -e $m ]] || continue
    id="$(basename "$m" .manifest)"
    in_list "$id" "${protect[@]}" || [[ -d $releases/$id ]] || rm -f "$m"
  done
  # assets: 보존할 manifest들의 assets 합집합만 남긴다. 하나라도 없거나 손상됐으면 지우지 않는다
  for id in "${protect[@]}"; do
    if ! manifest_ok "$id"; then
      log "경고: manifest($id)가 없거나 손상돼 assets를 정리하지 않습니다"
      return 2
    fi
  done
  for id in "${protect[@]}"; do awk '$1=="asset"{print $3}' "$manifests/$id.manifest"; done | sort -u >"$tmpd/keep-assets"
  n=0
  for f in "$pool"/*; do
    [[ -f $f && ! -L $f ]] || continue
    name="${f##*/}"
    [[ $name =~ $ASSET_RE ]] || continue
    grep -qxF "$name" "$tmpd/keep-assets" || {
      rm -f "$f"
      n=$((n + 1))
    }
  done
  log "정리: 릴리스 $(find "$releases" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')개 보존, assets ${n}개 지움"
}
if gc_run; then gc=ok; else gc=skipped; fi
log "배포 완료: $deploy_id (커밋 $commit)"
