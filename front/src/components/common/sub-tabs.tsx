import { cn } from "cn"
import type { Page } from "@/lib/router"

// 통합된 4탭 안에서 세부 화면(예: 수리 진행 / 수리비·부담)을 전환하는 슬림 세그먼트 바
export function SubTabs({
  items,
  value,
  badges,
  onChange,
}: {
  items: readonly { id: Page; label: string }[]
  value: Page
  badges?: Partial<Record<Page, number>>
  onChange: (page: Page) => void
}) {
  return (
    <div
      role="tablist"
      aria-label="세부 메뉴"
      className="mb-4 inline-flex max-w-full overflow-x-auto rounded-[12px] border border-line bg-white p-1 shadow-[0_1px_2px_#17203308] [scrollbar-width:none] max-md:flex max-md:w-full [&::-webkit-scrollbar]:hidden"
    >
      {items.map((item) => {
        const active = item.id === value
        const badge = badges?.[item.id] ?? 0
        return (
          <button
            key={item.id}
            type="button"
            role="tab"
            aria-selected={active}
            onClick={() => onChange(item.id)}
            className={cn(
              "inline-flex min-h-10 flex-1 items-center justify-center gap-1.5 rounded-[9px] px-4 text-sm font-semibold whitespace-nowrap transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand md:flex-none",
              active
                ? "bg-brand text-white shadow-[0_1px_2px_#17203318]"
                : "text-sub hover:bg-hover hover:text-ink",
            )}
          >
            {item.label}
            {badge > 0 && (
              <span
                className={cn(
                  "inline-flex h-5 min-w-5 items-center justify-center rounded-full px-1.5 text-xs leading-none font-bold tabular-nums",
                  active
                    ? "bg-white/20 text-white"
                    : "bg-turn-soft text-turn-ink",
                )}
              >
                {badge}
              </span>
            )}
          </button>
        )
      })}
    </div>
  )
}
