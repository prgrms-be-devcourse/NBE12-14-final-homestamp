#!/usr/bin/env bash
# Trivy JSON 결과로 발행 허용을 정한다(검사는 scan.sh).
#   evaluate.sh <trivy.json> [예외 파일]
# 정책: CRITICAL은 고친 판이 없어도 막는다 / HIGH는 고친 판이 있으면 막고, 없으면 경고로 남긴다 / 비밀값은 하나라도 막는다
#       검사가 이미지를 읽지 못했으면(OS 정보 없음, 또는 Java 대상 없음) 통과로 보지 않는다
# 예외 파일 한 줄: <취약점 ID> <만료일 YYYY-MM-DD> <이유>. 만료일이 지나면 예외가 아니다
# 종료: 0 통과 | 10 취약점 | 11 비밀값 | 3 결과를 믿을 수 없음
set -euo pipefail
json="$1"
exceptions="${2:-}"
today="${TODAY:-$(date -u +%F)}"
jq -e . "$json" >/dev/null 2>&1 || { echo "evaluate: Trivy 결과가 JSON이 아닙니다"; exit 3; }

os="$(jq -r '.Metadata.OS.Family // ""' "$json")"
[[ -n $os ]] || { echo "evaluate: Trivy가 이미지의 OS를 알아내지 못했습니다(검사를 믿을 수 없음)"; exit 3; }
if [[ "${REQUIRE_JAVA:-1}" == 1 ]] && ! jq -e '[.Results[]? | select(.Class=="lang-pkgs" and .Type=="jar")] | length > 0' "$json" >/dev/null; then
  echo "evaluate: Java(jar) 대상이 없습니다(앱 의존성을 검사하지 못함)"
  exit 3
fi

allowed='[]'
if [[ -n $exceptions && -f $exceptions ]]; then
  allowed="$(awk -v t="$today" '!/^#/ && NF>=3 && $2 >= t {print $1}' "$exceptions" | jq -R . | jq -s .)"
fi

summary="$(jq -r --argjson allowed "$allowed" '
  [.Results[]?.Vulnerabilities[]? | select(.Severity=="HIGH" or .Severity=="CRITICAL")] as $v
  | ($v | map(select((.VulnerabilityID as $id | $allowed | index($id)) | not))) as $v
  | {
      block: [$v[] | select(.Severity=="CRITICAL" or ((.FixedVersion // "") != ""))],
      warn:  [$v[] | select(.Severity=="HIGH" and ((.FixedVersion // "") == ""))],
      secrets: [.Results[]?.Secrets[]?]
    }
  | "block=\(.block|length) warn=\(.warn|length) secrets=\(.secrets|length)",
    (.block[] | "막음 \(.Severity) \(.VulnerabilityID) \(.PkgName) \(.InstalledVersion) -> \(.FixedVersion // "고친 판 없음")"),
    (.warn[]  | "경고 \(.Severity) \(.VulnerabilityID) \(.PkgName) \(.InstalledVersion) (고친 판 없음)"),
    (.secrets[] | "비밀값 \(.RuleID) \(.Category)")
' "$json")"
echo "OS: $os"
echo "$summary"
counts="$(head -n 1 <<<"$summary")"
[[ $counts =~ secrets=([0-9]+) ]] && ((BASH_REMATCH[1] > 0)) && { echo "evaluate: 이미지에 비밀값이 있습니다"; exit 11; }
[[ $counts =~ block=([0-9]+) ]] && ((BASH_REMATCH[1] > 0)) && { echo "evaluate: 막아야 할 취약점이 있습니다"; exit 10; }
echo "evaluate: 통과"
