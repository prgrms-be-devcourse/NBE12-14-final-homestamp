#!/usr/bin/env bash
# check-image.sh 시험: 가짜 docker(buildx imagetools)·gh로 레지스트리·GitHub 응답을 흉내 낸다(네트워크 없음). macOS bash 3.2·Linux bash 5.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
target="$here/../check-image.sh"
W="$(mktemp -d)"
trap 'rm -r "$W"' EXIT
mkdir -p "$W/bin" "$W/reg"
REV=0123456789abcdef0123456789abcdef01234567
# 가짜 레지스트리: $W/reg/<참조를 바꾼 이름>.raw(원문), .json(.Image 형식)
cat >"$W/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$MOCK_DIR/calls"
[[ "$1 $2 $3" == "buildx imagetools inspect" ]] || { echo "mock: 모르는 호출" >&2; exit 99; }
shift 3
if [[ $1 == --raw ]]; then ref=$2; kind=raw; else ref=$1; kind=json; fi
f="$MOCK_DIR/reg/$(printf '%s' "$ref" | tr '/:@' '___').$kind"
[[ -f $f ]] || { echo "ERROR: not found" >&2; exit 1; }
cat "$f"
DOCKER
cat >"$W/bin/gh" <<'GH'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$MOCK_DIR/calls"
[[ $1 == api && $2 == repos/acme/homestamp/compare/* ]] || { echo "mock: 모르는 호출" >&2; exit 99; }
[[ -n ${MOCK_COMPARE:-} ]] || exit 1
echo "$MOCK_COMPARE"
GH
chmod +x "$W/bin/docker" "$W/bin/gh"
REPO=ghcr.io/acme/homestamp-back
key() { printf '%s' "$1" | tr '/:@' '___'; }
put() { # 참조 원문 json
  printf '%s' "$2" >"$W/reg/$(key "$1").raw"
  printf '%s' "$3" >"$W/reg/$(key "$1").json"
}
dig() { printf '%s' "$1" | sha256sum | awk '{print "sha256:" $1}'; }
index_json() { printf '{"linux/amd64":{"os":"linux","architecture":"amd64","config":{"Labels":{"org.opencontainers.image.revision":"%s"}}},"unknown/unknown":{"os":"unknown","architecture":"unknown"}}' "$1"; }
single_json() { printf '{"os":"linux","architecture":"%s","config":{"Labels":%s}}' "$1" "$2"; }

passed=0; failed=0
ok() { passed=$((passed + 1)); echo "ok   $1"; }
bad() { failed=$((failed + 1)); echo "FAIL $1"; }
run() { # 이름 기대코드 digest [compare]
  rm -f "$W/calls"
  PATH="$W/bin:$PATH" MOCK_DIR="$W" MOCK_COMPARE="${4-ahead}" IMAGE_REPOSITORY="${T_REPO:-$REPO}" DIGEST="$3" \
    OWNER="${T_OWNER:-Acme}" GITHUB_REPOSITORY=acme/homestamp GH_TOKEN=x bash "$target" >"$W/out" 2>&1
  local rc=$?
  if [[ $rc == "$2" ]]; then ok "$1 ($rc)"; else bad "$1 (기대 $2, 실제 $rc)"; cat "$W/out"; fi
}

# 정상: 발행 절차의 목록(index) 이미지, sha-<커밋> 태그가 같은 원문
RAW_OK='{"mediaType":"application/vnd.oci.image.index.v1+json","manifests":["ok"]}'
D_OK="$(dig "$RAW_OK")"
put "$REPO@$D_OK" "$RAW_OK" "$(index_json $REV)"
put "$REPO:sha-$REV" "$RAW_OK" "$(index_json $REV)"
run "발행 절차 이미지(목록)" 0 "$D_OK"
grep -q "compare/$REV...main" "$W/calls" && ok "커밋을 main과 비교" || bad "main 비교 호출"
run "main과 같은 커밋(identical)" 0 "$D_OK" identical
run "main에 없는 커밋(diverged)" 54 "$D_OK" diverged
run "main에 없는 커밋(behind = main보다 앞선 다른 브랜치)" 54 "$D_OK" behind
run "비교 실패(없는 커밋)" 54 "$D_OK" ""

# 단일 이미지도 받는다
RAW_S='{"mediaType":"application/vnd.oci.image.manifest.v1+json","single":1}'
D_S="$(dig "$RAW_S")"
put "$REPO@$D_S" "$RAW_S" "$(single_json amd64 "{\"org.opencontainers.image.revision\":\"$REV\"}")"
put "$REPO:sha-$REV" "$RAW_S" "$(single_json amd64 "{\"org.opencontainers.image.revision\":\"$REV\"}")"
run "단일 이미지" 0 "$D_S"

# 태그가 다른 이미지를 가리키면(손으로 올린 digest)
put "$REPO:sha-$REV" "$RAW_OK" "$(index_json $REV)"
run "sha-<커밋> 태그가 다른 digest" 53 "$D_S"
rm -f "$W/reg/$(key "$REPO:sha-$REV").raw"
run "sha-<커밋> 태그 없음" 53 "$D_OK"
put "$REPO:sha-$REV" "$RAW_OK" "$(index_json $REV)"

RAW_ARM='{"arm":1}'; D_ARM="$(dig "$RAW_ARM")"
put "$REPO@$D_ARM" "$RAW_ARM" "$(single_json arm64 "{\"org.opencontainers.image.revision\":\"$REV\"}")"
run "arm64 이미지" 51 "$D_ARM"
RAW_NL='{"nolabel":1}'; D_NL="$(dig "$RAW_NL")"
put "$REPO@$D_NL" "$RAW_NL" "$(single_json amd64 '{}')"
run "커밋 라벨 없음(손으로 빌드)" 52 "$D_NL"
run "없는 digest" 50 "sha256:$(printf 'c%.0s' $(seq 1 64))"
# 레지스트리가 다른 원문을 주면(digest 재계산이 다름)
put "$REPO@$D_ARM" "$RAW_OK" "$(index_json $REV)"
run "원문이 digest와 다름" 50 "$D_ARM"

T_REPO=ghcr.io/other/homestamp-back run "다른 조직의 저장소 거절" 2 "$D_OK"
[[ -e $W/calls ]] && bad "거절된 저장소로 레지스트리를 부름" || ok "레지스트리 호출 없음"
T_REPO=docker.io/acme/homestamp-back run "ghcr.io가 아닌 저장소 거절" 2 "$D_OK"
T_REPO='ghcr.io/acme/x;id' run "저장소 이름 주입 거절" 2 "$D_OK"
for p in "$D_OK;id" "${D_OK}0" "SHA256:${D_OK#sha256:}" ""; do
  run "digest 거절: $(printf '%q' "$p" | cut -c1-30)" 2 "$p"
done
T_OWNER='a;b' run "OWNER 형식 거절" 2 "$D_OK"

echo "passed=$passed failed=$failed"
[[ $failed == 0 ]]
