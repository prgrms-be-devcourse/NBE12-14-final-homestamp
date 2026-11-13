export const MAX_WON = 100_000_000_000
export function formatMoneyInput(value: string | number) {
  const digits = String(value).replace(/[^0-9]/g, '').replace(/^0+(?=\d)/, '')
  return digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}
export function parseMoney(value: string) {
  const clean = value.trim().replace(/,/g, '')
  if (!/^\d+$/.test(clean)) return NaN
  const amount = Number(clean)
  return Number.isSafeInteger(amount) && amount <= MAX_WON ? amount : NaN
}
export function formatWon(value: number) { return `${value.toLocaleString('ko-KR')}원` }
// Locate the caret by the number of digits to its left, not formatted string length.
export function moneyCaret(formatted: string, digitsBeforeCaret: number) {
  if (!digitsBeforeCaret) return 0
  let seen = 0
  for (let i = 0; i < formatted.length; i++) if (/\d/.test(formatted[i]) && ++seen === digitsBeforeCaret) return i + 1
  return formatted.length
}
