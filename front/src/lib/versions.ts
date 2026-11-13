// 기록의 '모습 번호'(판). 앞모습이 바뀔 때마다 1씩 올라가요(상태와 규칙 G 저장 규칙 2, REP-7d·7j).
// 확인할 때 화면은 사용자가 본 번호를 함께 보내고, 그사이 바뀌었으면 다시 보고 확인하게 해요.
// 서버 연결 시: 확인 요청 본문에 seenVersion을 넣고, 409 VERSION_CONFLICT를 받으면 다시 불러온 뒤 같은 안내를 띄워요(api/errors의 shouldReload).
// 화면의 검사는 보조이고 최종 판단은 서버가 해요.
export const VERSION_CONFLICT_MESSAGE = '내용이 바뀌었어요. 다시 보고 확인해 주세요.'
export const versionOf = (x: { version?: number }) => x.version ?? 1
export function bumpVersion(x: { version?: number }) { x.version = versionOf(x) + 1 }
export function assertSeenVersion(x: { version?: number }, seen?: number) {
  if (seen !== undefined && versionOf(x) !== seen) throw new Error(VERSION_CONFLICT_MESSAGE)
}
