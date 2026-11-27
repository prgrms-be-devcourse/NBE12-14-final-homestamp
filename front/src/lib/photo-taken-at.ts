// 촬영 시각 읽기(06 PHO-1f, 04 "사진" 올리기 ①). 다시 그리기 전 JPEG의 EXIF DateTimeOriginal(0x9003)과
// OffsetTimeOriginal(0x9011)을 읽어요. 서버가 확인할 수 없는 참고값이라 못 읽으면 null이에요(PNG·WebP·EXIF 없음).
// 시간대(Offset)가 없으면 이 기기의 시간대로 봐요.
export function exifTakenAt(buf: ArrayBuffer): string | null {
  const v = new DataView(buf)
  if (v.byteLength < 4 || v.getUint16(0) !== 0xffd8) return null
  let p = 2
  while (p + 4 <= v.byteLength) {
    const marker = v.getUint16(p)
    const size = v.getUint16(p + 2)
    if (marker === 0xffe1 && p + 10 <= v.byteLength) {
      // "Exif\0\0"
      if (v.getUint32(p + 4) === 0x45786966 && v.getUint16(p + 8) === 0)
        return fromTiff(v, p + 10)
    }
    if ((marker & 0xff00) !== 0xff00 || marker === 0xffda) return null
    p += 2 + size
  }
  return null
}

function fromTiff(v: DataView, t: number): string | null {
  try {
    const le = v.getUint16(t) === 0x4949
    const u16 = (o: number) => v.getUint16(t + o, le)
    const u32 = (o: number) => v.getUint32(t + o, le)
    const ascii = (o: number, n: number) => {
      let s = ""
      for (let i = 0; i < n; i++) {
        const c = v.getUint8(t + o + i)
        if (!c) break
        s += String.fromCharCode(c)
      }
      return s
    }
    // IFD에서 태그 값을 찾아요(ASCII는 4바이트 넘으면 오프셋)
    const find = (ifd: number, tag: number) => {
      const n = u16(ifd)
      for (let i = 0; i < n; i++) {
        const e = ifd + 2 + i * 12
        if (u16(e) !== tag) continue
        const count = u32(e + 4)
        if (u16(e + 2) === 4) return { value: u32(e + 8) }
        return { text: ascii(count > 4 ? u32(e + 8) : e + 8, count) }
      }
      return undefined
    }
    const exifIfd = find(u32(4), 0x8769)?.value
    if (exifIfd === undefined) return null
    const when = find(exifIfd, 0x9003)?.text
    const offset = find(exifIfd, 0x9011)?.text
    const m = when?.match(/^(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})/)
    if (!m) return null
    const [, y, mo, d, h, mi, s] = m.map(Number)
    if (offset && /^[+-]\d{2}:\d{2}$/.test(offset)) {
      const sign = offset[0] === "-" ? -1 : 1
      const mins =
        sign * (Number(offset.slice(1, 3)) * 60 + Number(offset.slice(4)))
      const ms = Date.UTC(y, mo - 1, d, h, mi, s) - mins * 60_000
      return Number.isNaN(ms) ? null : new Date(ms).toISOString()
    }
    const local = new Date(y, mo - 1, d, h, mi, s)
    return Number.isNaN(local.getTime()) ? null : local.toISOString()
  } catch {
    return null
  }
}
