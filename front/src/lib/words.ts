// 화면에 보이는 말(요구사항명세 5-0 '사용자 말'): "측" 대신 "세입자 쪽 / 집주인 쪽".
// 저장 값(임차인·임대인)은 그대로 두고 보여 줄 때만 바꿔요.
export const sideText = (side?: string) =>
  !side
    ? "상대 쪽"
    : side === "임차인"
      ? "세입자 쪽"
      : side === "임대인"
        ? "집주인 쪽"
        : `${side} 쪽`
// 사람을 가리킬 때의 이름(GLOSSARY: 임차인·임대인은 호실을 만들 때 고르는 버튼 말고는 쓰지 않음).
export const sideName = (side?: string) =>
  side === "임차인" ? "세입자" : side === "임대인" ? "집주인" : (side ?? "상대")
// 받침에 맞는 주격 조사: "집주인이", "세입자가"
export const withSubject = (word: string) => {
  const code = word.charCodeAt(word.length - 1) - 0xac00
  const final = code >= 0 && code <= 11171 && code % 28 !== 0
  return `${word}${final ? "이" : "가"}`
}
