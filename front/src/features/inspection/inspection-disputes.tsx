import { LongText } from "@/components/common/long-text"
import type { InspectionDispute } from "@/data/demo"
import { sideText } from "@/lib/words"

function DisputeReason({ item }: { item: InspectionDispute }) {
  return (
    <div className="dispute-callout mt-3">
      <small className="block">
        {sideText(item.by)} 이견 · <time>{item.at}</time>
      </small>
      <LongText text={item.reason} />
    </div>
  )
}

// 점검 하자 항목에 남은 이견 기록을 표시만 합니다. 원본 기록과 행동 기록은 바꾸지 않습니다.
export function InspectionDisputes({
  disputes,
}: {
  disputes?: InspectionDispute[]
}) {
  if (!disputes?.length) return null
  return (
    <div>
      {disputes.map((d, n) => (
        <DisputeReason key={n} item={d} />
      ))}
    </div>
  )
}
