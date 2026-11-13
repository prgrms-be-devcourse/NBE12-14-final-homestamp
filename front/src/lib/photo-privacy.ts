import type { PhotoMask, Repair, Role } from '@/data/demo'
import { isHidden, nowText } from '@/lib/repair-rules'
import { sideText } from '@/lib/words'

// 개인정보 이유로 사진 가리기(팀 회의 결정). 삭제 기능은 두지 않아요.
// - 올린 사람만, 언제든 가릴 수 있고 되돌릴 수 없어요. 사진 자리는 남고 흐리게 "개인정보 이유로 가림"이 보여요.
// - 상대는 가린 데에 이견(사유 필수)을 덧붙일 수 있어요. 가린 기록과 이견은 나란히 남아요.
interface Masked { privacyMasks?: PhotoMask[] }

export const maskOf = (x: Masked, key: string) => x.privacyMasks?.find(m => m.key === key)
export const maskLabel = (m: PhotoMask) => `개인정보 이유로 가림 · ${sideText(m.by)} · ${m.at}`

export function maskPhoto(x: Masked, key: string, role: Role, uploader: Role) {
  if (uploader !== role) throw new Error('사진을 올린 사람만 개인정보 이유로 가릴 수 있어요.')
  if (maskOf(x, key)) throw new Error('이미 개인정보 이유로 가린 사진이에요.')
  const m: PhotoMask = { key, by: role, at: nowText() }
  x.privacyMasks = [...x.privacyMasks || [], m]
  return m
}

// 수리 건: 사진 키로 올린 사람 찾기(수리 전은 기록한 쪽, 수리 후는 그 기록을 남긴 쪽)
export const uploaderOf = (r: Repair, key: string): Role => key.startsWith('a:') ? r.afterRecords?.find(a => a.id === key.split(':')[1])?.by ?? r.author : r.author
// 화면에 보이는 사진 순서대로 가림 정보를 맞춰 줘요(PhotoGallery masks)
export const beforeMasks = (r: Repair, guest?: boolean) => r.photos.map((_, n) => n).filter(n => (!guest || !isHidden(r, `b:${n}`)) && !r.wrongPhotos?.includes(n)).map(n => maskOf(r, `b:${n}`))
export const afterMasks = (r: Repair, recordId: string, photos: string[], guest?: boolean) => photos.map((_, i) => i).filter(i => !guest || !isHidden(r, `a:${recordId}:${i}`)).map(i => maskOf(r, `a:${recordId}:${i}`))

export function objectToMask(x: Masked, key: string, role: Role, reason: string) {
  const m = maskOf(x, key)
  if (!m) throw new Error('가린 사진을 찾을 수 없어요.')
  if (m.by === role) throw new Error('가린 사진에 대한 이견은 상대만 남길 수 있어요.')
  if (!reason.trim()) throw new Error('이견 사유를 적어 주세요.')
  m.objections = [...m.objections || [], { by: role, reason: reason.trim(), at: nowText() }]
}
