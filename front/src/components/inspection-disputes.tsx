import { useState } from 'react'
import { Button } from '@/components/ui/button'
import type { InspectionDispute } from '@/data/demo'
import { sideText } from '@/lib/words'

const LIMIT = 120

// 긴 사유는 120자까지만 보이고 펼칠 수 있어요. 이견 댓글과 부위·사진 변경 요청 댓글이 함께 씁니다.
export function LongText({ text, more = '이견 사유 펼치기' }: { text: string; more?: string }) {
  const [open, setOpen] = useState(false)
  const long = text.length > LIMIT
  return <>
    <p className="whitespace-pre-wrap [overflow-wrap:anywhere]">{long && !open ? text.slice(0, LIMIT) + '…' : text}</p>
    {long && <Button type="button" variant="link" className="h-auto p-0" aria-expanded={open} onClick={() => setOpen(v => !v)}>{open ? '접기' : more}</Button>}
  </>
}

function DisputeReason({ item }: { item: InspectionDispute }) {
  return <div className="dispute-callout mt-3">
    <small className="block">{sideText(item.by)} 이견 · <time>{item.at}</time></small>
    <LongText text={item.reason} />
  </div>
}

// 점검 하자 항목에 남은 이견 기록을 표시만 합니다. 원본 기록과 행동 기록은 바꾸지 않습니다.
export function InspectionDisputes({ disputes }: { disputes?: InspectionDispute[] }) {
  if (!disputes?.length) return null
  return <div>{disputes.map((d, n) => <DisputeReason key={n} item={d} />)}</div>
}
