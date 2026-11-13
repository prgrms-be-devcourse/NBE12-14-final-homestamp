import { Info, Search } from "lucide-react"
import { Input } from "@/components/ui/input"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Panel, Status } from "@/components/prototype"
import { EmptyState } from "@/components/states"
import { PhotoGallery } from "@/components/photo-gallery"
import { RepairListItem } from "@/components/home-dashboard"
import type { Repair, Role } from "@/data/demo"
import { myTurn } from "@/lib/turn"
import { plainText } from "@/lib/plain-words"

// 하자·수리 목록과 수리비(돈) 목록. 같은 목록을 탭과 검색으로 걸러 보여 줘요.
export function RepairsPage({
  page,
  role,
  repairs,
  moneyLeft,
  openCount,
  filter,
  setFilter,
  search,
  setSearch,
  tabCount,
  detail,
}: {
  page: "repairs" | "money"
  role: Role
  repairs: Repair[]
  moneyLeft: Repair[]
  openCount: number
  filter: string
  setFilter: (v: string) => void
  search: string
  setSearch: (v: string) => void
  tabCount: (v: string) => number
  detail: (id: string) => void
}) {
  return (
    <>
      {/* 상태 탭 + 검색. 휴대폰: 탭은 화면 폭 안에서만 옆으로 밀어 보고(오른쪽 끝 흐림), 검색은 아래 전체 폭 */}
      <div className="mb-[22px] flex min-w-0 flex-wrap items-center justify-between gap-5 max-[1000px]:flex-col max-[1000px]:items-stretch max-md:gap-3">
        <Tabs
          value={filter}
          onValueChange={(v) => setFilter(String(v))}
          className="w-full min-w-0 min-[1001px]:w-auto max-md:-mx-[18px] max-md:w-[calc(100%+36px)] max-md:overflow-x-auto max-md:px-[18px] max-md:py-0.5 max-md:[mask-image:linear-gradient(to_right,#000_calc(100%-24px),transparent)] max-md:[scrollbar-width:none] max-md:[&::-webkit-scrollbar]:hidden"
        >
          <TabsList className="flex h-auto w-auto flex-wrap items-center justify-start gap-2 rounded-none bg-transparent p-0 max-md:w-max max-md:flex-nowrap">
            {(page === "money"
              ? [
                  "전체",
                  "미정",
                  "합의 대기",
                  "이견",
                  "합의됨",
                  "비용 없음",
                  "돈이 남은 건",
                ]
              : [
                  "전체",
                  "내 차례",
                  "확인 전",
                  "확인 완료",
                  "처리 중",
                  "처리 완료",
                ]
            ).map((v) => (
              <TabsTrigger
                key={v}
                value={v}
                data-chip
                className="h-9 flex-none gap-1.5 rounded-full border border-line bg-white py-0 pr-3 pl-3.5 text-sm leading-none font-semibold whitespace-nowrap text-sub shadow-none hover:border-line-strong hover:text-ink data-active:border-brand data-active:bg-brand data-active:text-white group-data-[variant=default]/tabs-list:data-active:shadow-none"
              >
                {plainText(v)}
                <span
                  className={`inline-flex h-5 min-w-[22px] items-center justify-center rounded-full px-1.5 text-xs leading-none font-bold tabular-nums in-data-active:bg-white/20 in-data-active:text-white ${tabCount(v) ? "bg-chip text-sub" : "bg-[#f3f5f9] text-faint"}`}
                  aria-hidden
                >
                  {tabCount(v)}
                </span>
              </TabsTrigger>
            ))}
          </TabsList>
        </Tabs>
        <div className="relative max-[1000px]:w-full">
          <Search
            size={16}
            className="absolute top-3.5 left-2.5 text-[#9ba9bd]"
          />
          <Input
            className="h-11 w-[220px] bg-white pl-8 text-base max-[1000px]:w-full"
            aria-label="하자 기록 검색"
            placeholder="제목 · 부위로 검색"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
      </div>
      {/* 휴대폰에서는 카드 안쪽 여백을 줄여 목록 폭을 넓혀요(! : 아직 남은 .panel CSS보다 앞서게) */}
      <Panel
        className="max-md:[&>[data-slot=card-content]]:px-3.5! max-md:[&>[data-slot=card-header]]:px-3.5!"
        title={
          page === "money"
            ? "수리 건별 비용 부담 현황"
            : `수리 기록 전체 ${repairs.length}건 · 진행 중 ${openCount}건`
        }
      >
        {page === "money" && (
          <p className="mb-4 flex items-start gap-2 px-1 text-[13px] leading-[1.6] text-sub">
            <Info size={16} aria-hidden />
            지불한 사실, 부담하기로 한 합의, 수리 진행은 각각 따로 끝나요. 돈
            얘기가 남아도 수리는 끝낼 수 있어요.
          </p>
        )}
        {page === "money" &&
          moneyLeft.length > 0 &&
          filter !== "돈이 남은 건" && (
            <button
              type="button"
              className="mb-4 w-full rounded-[12px] bg-warn-soft px-3.5 py-3 text-left text-sm leading-[1.6] text-warn-ink"
              onClick={() => setFilter("돈이 남은 건")}
            >
              고쳤지만 돈이 남은 건이 {moneyLeft.length}건 있어요 · 모아 보기
            </button>
          )}
        {(() => {
          const q = search.trim()
          const items = repairs.filter(
            (r) =>
              (!q || r.title.includes(q) || r.zone.includes(q)) &&
              (filter === "전체" ||
                (filter === "내 차례"
                  ? Boolean(myTurn(r, role))
                  : page === "money"
                    ? filter === "돈이 남은 건"
                      ? moneyLeft.some((m) => m.id === r.id)
                      : (r.proposal?.status || "미정") === filter
                    : r.status === filter)),
          )
          return items.length ? (
            <div className="flex flex-col gap-2">
              {items.map((r) => (
                <RepairListItem
                  key={r.id}
                  r={r}
                  role={role}
                  thumb={
                    <PhotoGallery
                      title={r.title}
                      photos={r.photos}
                      art={r.art}
                      layout="compact"
                    />
                  }
                  onOpen={() => detail(r.id)}
                  extra={
                    page === "money" && r.payments?.length ? (
                      <Status>{`지불 기록 ${r.payments.length}건`}</Status>
                    ) : undefined
                  }
                />
              ))}
            </div>
          ) : (
            <EmptyState
              title={
                q
                  ? `‘${q}’에 맞는 기록이 없어요`
                  : repairs.length
                    ? "이 조건에 해당하는 기록이 없어요"
                    : "아직 남긴 하자 기록이 없어요"
              }
              description={
                repairs.length
                  ? "다른 탭을 고르거나 검색어를 바꿔 보세요."
                  : "고장이나 하자를 사진과 함께 남겨 두면 상대와 같은 기록을 볼 수 있어요."
              }
            />
          )
        })()}
      </Panel>
    </>
  )
}
