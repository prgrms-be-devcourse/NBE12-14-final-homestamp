import type { ReactNode } from "react"
import { ChevronDown } from "lucide-react"
import { cn } from "cn"
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible"

// 건수 칩(접기 제목 옆, 호실 묶음 제목 옆 등에 붙는 작은 숫자)
export function CountChip({
  children,
  className,
}: {
  children: ReactNode
  className?: string
}) {
  return (
    <span
      className={cn(
        "inline-flex h-5 min-w-[22px] items-center justify-center rounded-full bg-chip px-1.5 text-xs leading-[inherit] font-bold text-sub tabular-nums",
        className,
      )}
    >
      {children}
    </span>
  )
}

// 접고 펼치는 영역(경과·고친 이력·사진 관리 등). 한 줄 전체가 누르는 곳이고, 오른쪽 화살표로 열림을 보여 줘요.
// tone="amber": 내가 답해야 하는 고치기 요청처럼 눈에 띄어야 하는 묶음.
// 접기 안에 또 접기가 들어가면 안쪽 접기는 바탕을 조금 어둡게 해요(data-fold).
export function Fold({
  title,
  count,
  icon,
  meta,
  sub,
  tone,
  defaultOpen,
  className,
  children,
}: {
  title: ReactNode
  count?: number
  icon?: ReactNode
  meta?: ReactNode
  sub?: ReactNode
  tone?: "amber"
  defaultOpen?: boolean
  className?: string
  children: ReactNode
}) {
  const amber = tone === "amber"
  return (
    <Collapsible
      defaultOpen={defaultOpen}
      data-fold
      className={cn(
        "mt-2.5 overflow-hidden rounded-[12px] border",
        amber ? "border-warn-line bg-warn-soft" : "border-line bg-white",
        "in-data-fold:bg-[#fafbfe]",
        className,
      )}
    >
      <CollapsibleTrigger
        className={cn(
          "group/fold flex min-h-12 w-full cursor-pointer items-center gap-2.5 border-0 bg-transparent px-3.5 py-2.5 text-left text-ink focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-brand",
          amber ? "hover:bg-[#fff1dc]" : "hover:bg-hover",
        )}
      >
        {icon && (
          <span
            className={cn(
              "grid size-[30px] flex-none place-items-center rounded-[9px]",
              amber ? "bg-[#ffe8c7] text-warn-ink" : "bg-brand-soft text-brand",
            )}
            aria-hidden
          >
            {icon}
          </span>
        )}
        <span className="flex min-w-0 flex-1 flex-col gap-0.5">
          <span className="flex items-center gap-1.5 text-[15px] font-bold">
            {title}
            {count !== undefined && <CountChip>{count}</CountChip>}
          </span>
          {sub && <span className="truncate text-[13px] text-sub">{sub}</span>}
        </span>
        {meta && <span className="flex-none">{meta}</span>}
        <ChevronDown
          className="flex-none text-[#8e9bb0] transition-transform duration-[180ms] group-data-[panel-open]/fold:rotate-180"
          size={18}
          aria-hidden
        />
      </CollapsibleTrigger>
      <CollapsibleContent className="px-3.5 pt-1 pb-3.5">
        {children}
      </CollapsibleContent>
    </Collapsible>
  )
}
