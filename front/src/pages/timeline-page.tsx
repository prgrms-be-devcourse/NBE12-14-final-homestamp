import { CountChip } from "@/components/fold"
import { Panel, Timeline } from "@/components/prototype"
import type { Event } from "@/data/demo"

// 경과(호실 기록의 행동 기록을 시간 순서로, GET /api/rooms/{id}/timeline)
export function TimelinePage({ events }: { events: Event[] }) {
  return (
    <Panel
      title={
        <>
          행동 기록 <CountChip>{events.length}</CountChip>
        </>
      }
    >
      <Timeline events={events} />
    </Panel>
  )
}
