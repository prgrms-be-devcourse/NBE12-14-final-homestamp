import type { Repair } from "@/data/demo"
import { isHidden } from "@/lib/repair-rules"
import { maskOf } from "@/lib/photo-privacy"

// 잘못 올린 사진(PHO-9): 지우지 않고 흐리게 맨 뒤에 보여요.
export const splitWrong = (photos: string[], wrong?: number[]) => ({
  main: photos.filter((_, n) => !wrong?.includes(n)),
  wrong: photos.filter((_, n) => wrong?.includes(n)),
})
// 링크 화면에 보일 사진만 고릅니다. 올린 사람만 자기 사진을 숨길 수 있어요.
export const visibleBefore = (r: Repair) =>
  r.photos.filter((_, i) => !isHidden(r, `b:${i}`))
// 수리 전 사진: 잘못 올린 사진은 흐리게 맨 뒤로(링크 화면에서는 숨긴 사진 제외)
export function beforePhotos(r: Repair, guest?: boolean) {
  const list = r.photos
    .map((src, n) => ({ src, n }))
    .filter((x) => !guest || !isHidden(r, `b:${x.n}`))
  return {
    main: list.filter((x) => !r.wrongPhotos?.includes(x.n)).map((x) => x.src),
    wrong: list.filter((x) => r.wrongPhotos?.includes(x.n)).map((x) => x.src),
    wrongMasks: list
      .filter((x) => r.wrongPhotos?.includes(x.n))
      .map((x) => maskOf(r, `b:${x.n}`)),
  }
}
export const visibleAfter = (r: Repair, recordId: string, photos: string[]) =>
  photos.filter((_, i) => !isHidden(r, `a:${recordId}:${i}`))
