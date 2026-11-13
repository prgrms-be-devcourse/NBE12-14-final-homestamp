import { useState, type ReactNode } from 'react'
import { Camera, ChevronDown } from 'lucide-react'
import { cn } from 'cn'
import { Button } from '@/components/ui/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible'

// 퇴거 나란히 보기(요구사항명세 S-14·INSP-3b, 기획서 '같은 부위의 입주 사진, 수리 이력, 퇴거 사진을 한 줄로 비교').
// 부위 하나 = 접힌 한 줄(부위 이름 + 칸별 개수). 누르면 입주 · 수리 · 퇴거 세 칸이 펼쳐져요(5-0 '카드에는 한 줄, 자세한 건 펼쳐서').
export interface ComparisonColumn { key: string; label: string; hint?: string; items: ReactNode[]; empty: string }
const SHOW = 2

// 칸마다 색: 입주 파랑 · 수리 주황 · 퇴거 초록
const tone: Record<string, { chip: string; label: string }> = {
  in: { chip: 'bg-[#eaf0ff] text-brand', label: 'text-brand' },
  repair: { chip: 'bg-turn-soft text-turn-ink', label: 'text-turn-ink' },
  out: { chip: 'bg-ok-soft text-ok-ink', label: 'text-ok-ink' },
}

function Column({ c }: { c: ComparisonColumn }) {
  const [all, setAll] = useState(false)
  const shown = all ? c.items : c.items.slice(0, SHOW)
  return <div className="flex min-w-0 flex-col gap-2 rounded-[12px] bg-[#f7f9fc] p-2.5">
    <div className="flex flex-wrap items-center gap-1.5 text-[13px] text-sub"><strong className={cn('text-sm text-ink', tone[c.key]?.label)}>{c.label}</strong>{c.hint && <span>{c.hint}</span>}</div>
    {/* 칸 안의 기록 카드: 흰 바탕 · 둥근 모서리(기록 카드 rec-item은 테두리와 안쪽 여백도) */}
    {c.items.length ? <div className="flex min-w-0 flex-col gap-2 [&>button]:m-0 [&>button]:rounded-[10px] [&>button]:bg-white [&>div]:m-0 [&>div]:rounded-[10px] [&>div]:bg-white [&_.rec-item]:rounded-[10px] [&_.rec-item]:border [&_.rec-item]:border-line [&_.rec-item]:bg-white [&_.rec-item]:p-2.5">{shown}</div>
      : <div className="flex min-h-11 items-center gap-1.5 rounded-[10px] border border-dashed border-[#d7dfeb] bg-white px-2.5 py-2 text-[13px] text-sub"><Camera size={16} aria-hidden />{c.empty}</div>}
    {c.items.length > SHOW && <Button variant="ghost" size="sm" className="self-start text-brand" aria-expanded={all} onClick={() => setAll(v => !v)}>{all ? '접기' : `${c.items.length - SHOW}개 더 보기`}<ChevronDown size={14} className={all ? 'rotate-180' : ''} aria-hidden /></Button>}
  </div>
}

export function ComparisonRow({ zone, columns }: { zone: string; columns: ComparisonColumn[] }) {
  return <Collapsible data-slot="comparison" className="overflow-hidden rounded-[14px] border border-line bg-white">
    <CollapsibleTrigger className="group/fold flex min-h-14 w-full cursor-pointer items-center gap-3 border-0 bg-transparent px-4 py-3 text-left text-ink hover:bg-hover focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-brand max-sm:flex-wrap" aria-label={`${zone} 나란히 보기 펼치기`}>
      <span className="m-0 min-w-0 flex-1 text-base leading-[inherit] font-[750] text-ink [overflow-wrap:anywhere] max-sm:basis-[calc(100%-30px)]">{zone}</span>
      <span className="flex flex-wrap justify-end gap-1.5 max-sm:order-3 max-sm:basis-full max-sm:justify-start">{columns.map(c => <span key={c.key} className={cn('inline-flex h-6 items-center rounded-full px-[9px] text-xs leading-[inherit] font-bold whitespace-nowrap tabular-nums', c.items.length ? tone[c.key]?.chip : 'bg-[#f1f3f7] text-faint')}>{c.label} {c.items.length}</span>)}</span>
      <ChevronDown className="flex-none text-[#8e9bb0] transition-transform duration-[180ms] group-data-[panel-open]/fold:rotate-180" size={18} aria-hidden />
    </CollapsibleTrigger>
    <CollapsibleContent className="px-4 pb-4">
      <div className="grid grid-cols-3 items-start gap-3 max-[900px]:grid-cols-1">{columns.map(c => <Column key={c.key} c={c} />)}</div>
    </CollapsibleContent>
  </Collapsible>
}
