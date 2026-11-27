import { Info } from "lucide-react"
import { CountChip } from "@/components/common/fold"
import { Panel, Timeline } from "@/components/common/blocks"
import type { Event } from "@/data/demo"
import { seoulDate } from "@/api/http-rooms"

// 경과(호실 기록의 행동 기록을 시간 순서로, GET /api/rooms/{id}/timeline)
// asOf: 공유를 그만둔 뒤면 그 시각까지의 기록이라 기준을 함께 보여요(03 6-6 9번, 06 4장 timeline asOf, #368 리뷰)
export function TimelinePage({
  events,
  asOf,
}: {
  events: Event[]
  asOf?: string | null
}) {
  const [, m, d] = asOf ? seoulDate(asOf).split("-") : []
  return (
    <Panel
      title={
        <>
          행동 기록 <CountChip>{events.length}</CountChip>
        </>
      }
    >
      {asOf && (
        <p className="mb-4 flex items-start gap-2 px-1 text-[13px] leading-[1.6] text-sub">
          <Info size={16} aria-hidden />
          {`${Number(m)}월 ${Number(d)}일 기준 기록이에요. 공유를 그만둔 뒤 상대가 남긴 것은 보이지 않아요.`}
        </p>
      )}
      <Timeline events={events} />
    </Panel>
  )
}
