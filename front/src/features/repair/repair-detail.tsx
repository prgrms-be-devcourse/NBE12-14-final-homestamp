import { useCallback, useEffect, useRef, useState, type ReactNode } from "react"
import { DetailMoreContext, type MoreItem } from "@/features/repair/detail-more"
import {
  ArrowLeft,
  Camera,
  Eye,
  History,
  Link2,
  MoreHorizontal,
  Pencil,
  Plus,
  Share2,
} from "lucide-react"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Fold } from "@/components/common/fold"
import { type Event, type Repair, type Role, type Room } from "@/data/demo"
import { StatusTag, TurnBadge } from "@/components/common/home-dashboard"
import { Stamp } from "@/components/common/stamp"
import { repairSummary } from "@/features/repair/repair-summary"
import { cn } from "cn"
import { myTurn } from "@/lib/turn"
import { ackText, sideName } from "@/lib/words"
import { repairWord, statusText, type StatusTone } from "@/lib/plain-words"

// 하자 줄의 상태 표시(문구는 공통 ackText, 08-화면-기본-틀 1장). 기다림·보충 요청은 노랑(아직 안 끝남),
// 이견·확인 없음은 색 없이 글로만 보여요
const ackTone: Record<Repair["ack"], StatusTone> = {
  확인됨: "done",
  "확인 대기": "wait",
  "보충 요청": "wait",
  이견: "progress",
  "상대 미확인": "progress",
}
const REPAIR_STEPS: Repair["status"][] = [
  "확인 전",
  "확인 완료",
  "처리 중",
  "처리 완료",
]
// 진행 단계의 화면 말(서버 값은 그대로)
const STEP_WORD: Record<Repair["status"], string> = {
  "확인 전": "하자 확인 전",
  "확인 완료": "하자 확인",
  "처리 중": "고치는 중",
  "처리 완료": "수리 끝",
}
const moneyTone = (r: Repair): StatusTone => {
  const m = r.proposal?.status || "미정"
  // 정하기 전·답 기다림은 노랑, 이견은 색 없이
  return m === "합의됨" || m === "비용 없음"
    ? "done"
    : m === "이견"
      ? "progress"
      : "wait"
}
const repairTone = (s: Repair["status"]): StatusTone =>
  s === "처리 완료" ? "done" : "progress"

// 수리 상태 한 줄 요약과 다음 할 일(수리 상태와 돈 상태는 따로 보여 줘요)

export interface RepairDetailProps {
  r: Repair
  room: Room
  role: Role
  events: Event[]
  isGuest?: boolean
  onBack: () => void
  backLabel: string
  gallery: ReactNode
  description: ReactNode
  actions?: ReactNode
  corrections: ReactNode
  timeline: ReactNode
  money?: ReactNode
  payments?: ReactNode
  causes?: ReactNode
  // 진행 상황(REP-6, 08 5-2). 원인 설명 다음, 경과 앞(06 S-12 상세 영역 순서)
  progress?: ReactNode
  sameZone?: ReactNode
  notices?: ReactNode
  extra?: ReactNode
  photoSettings?: ReactNode
  onEdit?: () => void
  onAddPhotos?: () => void
  onNewRepair?: () => void
  onShare?: () => void
  onCopyLink?: () => void
}
// 내 차례가 오른쪽 칸(수리비 · 지불) 카드에 있는 경우: 휴대폰에서 그 카드를 사진 카드 바로 아래로 올리고 주황 테두리로 강조
const turnCard = (turn?: string) =>
  turn === "비용 제안 응답" || turn === "바꾸자는 안 응답"
    ? "money"
    : turn === "지불 금액 확인"
      ? "payments"
      : undefined
const card =
  "min-w-0 gap-3.5 rounded-[18px] border border-line bg-white py-5 shadow-[0_1px_2px_#1f2a330a] ring-0"
const menuItem =
  "flex min-h-12 w-full items-center gap-2.5 border-t border-line px-4 text-left text-[15px] text-ink first:border-t-0 hover:bg-hover focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-brand"

// 수리 건 상세(S-12): 왼쪽 기록·상태·경과, 오른쪽 수리비 부담·실제로 낸 돈·원인(모바일은 한 줄)
// 맨 위에는 하자 / 수리 / 돈 세 줄 요약(화면 기본 틀 1장). 크게 보이는 행동 버튼은 상태 버튼만, 고치기·사진 추가 등은 '더 보기'에.
export function RepairDetail(p: RepairDetailProps) {
  const { r, role, room, events } = p
  const [more, setMore] = useState(false)
  const [showPhotos, setShowPhotos] = useState(false)
  // 상태 버튼 중 두 개를 넘는 행동(RepairActions가 등록). 같은 목록이면 다시 그리지 않아요.
  const [extra, setExtra] = useState<MoreItem[]>([])
  const register = useCallback(
    (items: MoreItem[]) =>
      setExtra((prev) =>
        prev.map((i) => i.key + i.label).join("|") ===
        items.map((i) => i.key + i.label).join("|")
          ? prev
          : items,
      ),
    [],
  )
  // 메뉴 바깥을 누르거나(마우스·터치) Esc를 누르면 닫혀요
  const menuRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!more) return
    const away = (e: PointerEvent) => {
      if (!menuRef.current?.contains(e.target as Node)) setMore(false)
    }
    const esc = (e: KeyboardEvent) => {
      if (e.key === "Escape") setMore(false)
    }
    document.addEventListener("pointerdown", away)
    document.addEventListener("keydown", esc)
    return () => {
      document.removeEventListener("pointerdown", away)
      document.removeEventListener("keydown", esc)
    }
  }, [more])
  const s = repairSummary(r, role, room, events)
  const turn = p.isGuest ? undefined : myTurn(r, role)
  const lift = turnCard(turn)
  const hasMore = Boolean(
    extra.length ||
    p.onEdit ||
    p.onAddPhotos ||
    p.onNewRepair ||
    p.photoSettings ||
    p.onShare ||
    p.onCopyLink,
  )
  const step =
    r.status === "처리 완료"
      ? REPAIR_STEPS.length
      : REPAIR_STEPS.indexOf(r.status)
  const close = (fn?: () => void) => () => {
    setMore(false)
    fn?.()
  }
  // 오른쪽 칸 카드 감싸기: 휴대폰 순서(order)와 내 차례 강조
  const side = (key: string, node: ReactNode, order: string) =>
    node ? (
      <div
        key={key}
        className={cn(
          lift === key
            ? "max-lg:order-2 rounded-[16px] ring-2 ring-turn-line ring-offset-2 ring-offset-page"
            : order,
        )}
      >
        {node}
      </div>
    ) : null
  const row = "flex items-start gap-3 py-2.5 first:pt-0 last:pb-0"
  const rowLabel = "w-11 flex-none pt-0.5 text-[13px] font-bold text-sub"
  return (
    <div className="flex flex-col gap-4">
      {!p.isGuest && (
        <Button
          variant="link"
          className="self-start text-[15px] text-brand"
          onClick={p.onBack}
        >
          <ArrowLeft />
          {p.backLabel}
        </Button>
      )}
      <header ref={menuRef} className="relative flex flex-col gap-1.5">
        <div className="flex items-start gap-3">
          <div className="flex min-w-0 flex-1 flex-col items-start gap-1.5">
            {turn && <TurnBadge>내 차례 · {turn}</TurnBadge>}
            <h1 className="text-[26px] leading-[1.3] font-extrabold tracking-[-.5px] text-ink [overflow-wrap:anywhere] md:text-3xl">
              {r.title}
            </h1>
            <p className="text-[15px] text-sub">
              {r.zone.replace(" · ", " › ")} · {r.date} {sideName(r.author)}{" "}
              등록
              {r.recurrenceOf ? " · 이전 수리 건 이후 재발" : ""}
            </p>
          </div>
          {hasMore && (
            <Button
              variant="outline"
              size="icon"
              className="size-10 flex-none rounded-[10px]"
              aria-label="더 보기"
              aria-haspopup="menu"
              aria-expanded={more}
              onClick={() => setMore((v) => !v)}
            >
              <MoreHorizontal />
            </Button>
          )}
        </div>
        {more && (
          <div
            className="absolute top-full right-0 z-20 mt-2 flex w-72 max-w-[calc(100vw-36px)] flex-col overflow-hidden rounded-[12px] border border-line bg-white shadow-[0_8px_24px_#1f2a3314]"
            role="menu"
          >
            {extra.map((it) => (
              <button
                key={it.key}
                type="button"
                role="menuitem"
                className={menuItem}
                onClick={close(it.onSelect)}
              >
                {it.icon ? (
                  <it.icon size={16} aria-hidden />
                ) : (
                  <span className="w-4" aria-hidden />
                )}
                {it.label}
              </button>
            ))}
            {p.onEdit && (
              <button
                type="button"
                role="menuitem"
                className={menuItem}
                onClick={close(p.onEdit)}
              >
                <Pencil size={16} aria-hidden />
                고치기
              </button>
            )}
            {p.onAddPhotos && (
              <button
                type="button"
                role="menuitem"
                className={menuItem}
                onClick={close(p.onAddPhotos)}
              >
                <Camera size={16} aria-hidden />
                사진 추가
              </button>
            )}
            {p.onNewRepair && (
              <button
                type="button"
                role="menuitem"
                className={menuItem}
                onClick={close(p.onNewRepair)}
              >
                <Plus size={16} aria-hidden />
                다른 하자도 있어요
              </button>
            )}
            {p.photoSettings && (
              <button
                type="button"
                role="menuitem"
                className={menuItem}
                onClick={close(() => setShowPhotos((v) => !v))}
              >
                <Eye size={16} aria-hidden />
                링크로 보이는 범위 · 내 사진 관리
              </button>
            )}
            {p.onShare && (
              <button
                type="button"
                role="menuitem"
                className={menuItem}
                onClick={close(p.onShare)}
              >
                <Share2 size={16} aria-hidden />
                상대에게 알리기 · 이 기록으로 초대
              </button>
            )}
            {p.onCopyLink && (
              <button
                type="button"
                role="menuitem"
                className={menuItem}
                onClick={close(p.onCopyLink)}
              >
                <Link2 size={16} aria-hidden />
                공유 링크 복사
              </button>
            )}
          </div>
        )}
      </header>
      {/* 하자 / 수리 / 돈 세 줄(서로 막지 않고 따로 움직여요). 상태 문구는 기존 그대로 */}
      <section
        aria-label="상태 요약"
        className="divide-y divide-line rounded-[18px] border border-line bg-white px-4 py-3.5 shadow-[0_1px_2px_#1f2a330a] md:px-5"
      >
        <div className={row}>
          <span className={rowLabel}>하자</span>
          <div className="flex min-w-0 flex-1 flex-wrap gap-1.5">
            <StatusTag raw tone={ackTone[r.ack]}>
              {r.ack === "상대 미확인"
                ? `${ackText(r.ack)} · 72시간 동안 답이 오지 않았어요`
                : ackText(r.ack)}
            </StatusTag>
          </div>
          {r.ack === "확인됨" && <Stamp seed={r.id} size={36} />}
        </div>
        <div className={row}>
          <span className={rowLabel}>수리</span>
          <div className="flex min-w-0 flex-1 flex-col gap-2">
            <div className="flex flex-wrap items-center gap-1.5">
              <StatusTag raw tone={repairTone(r.status)}>
                {repairWord(r.status)}
              </StatusTag>
              {r.startFlag && (
                <StatusTag raw tone="progress">
                  {r.startFlag}
                </StatusTag>
              )}
            </div>
            <ol
              className="m-0 flex list-none gap-1 p-0"
              aria-label={`수리 진행 단계 · ${STEP_WORD[r.status]}`}
            >
              {REPAIR_STEPS.map((x, i) => (
                <li key={x} className="flex min-w-0 flex-1 flex-col gap-1">
                  <span
                    className={cn(
                      "h-1.5 rounded-full",
                      i <= step ? "bg-brand" : "bg-chip",
                    )}
                    aria-hidden
                  />
                  <span
                    className={cn(
                      "truncate text-xs",
                      i === step
                        ? "font-bold text-brand"
                        : i < step
                          ? "text-ink"
                          : "text-faint",
                    )}
                  >
                    {STEP_WORD[x]}
                  </span>
                </li>
              ))}
            </ol>
          </div>
        </div>
        {!p.isGuest && (
          <div className={row}>
            <span className={rowLabel}>수리비</span>
            <div className="flex min-w-0 flex-1 flex-wrap items-center gap-x-2 gap-y-1">
              <StatusTag raw tone={moneyTone(r)}>
                {statusText(r.proposal?.status || "미정")}
              </StatusTag>
              <small className="text-[13px] text-sub">
                수리 진행과 따로 정해져요
              </small>
            </div>
          </div>
        )}
      </section>
      {p.notices}
      {/* 넓은 화면: 두 칸. 휴대폰: 한 줄로 쌓고(칸 상자를 풀어서) 순서를 사진·행동 → 내 차례 카드 → 수리비 → 지불 → 원인 → 경과 → 같은 부위로 */}
      <div className="flex flex-col gap-4 lg:grid lg:grid-cols-[minmax(0,1.1fr)_minmax(340px,1fr)] lg:items-start">
        <div className="flex min-w-0 flex-col gap-4 max-lg:contents">
          <Card className={cn(card, "max-lg:order-1")}>
            <CardContent className="space-y-4 px-5 max-md:px-4">
              {p.gallery}
              {p.description}
              {!p.isGuest && (
                <div
                  className="rounded-[12px] bg-soft px-4 py-3.5"
                  role="status"
                >
                  <strong className="block text-base text-ink">
                    {s.title}
                  </strong>
                  <p className="mt-1 text-sm leading-[1.6] text-sub">
                    {s.body}
                  </p>
                </div>
              )}
              {p.corrections}
              {/* 상태 버튼(지금 단계에서 할 일). 휴대폰에서는 두 칸씩 고르게 */}
              {p.actions && (
                <DetailMoreContext.Provider value={register}>
                  <div className="max-sm:[&_.actions>[data-slot=button]]:flex-1">
                    {p.actions}
                  </div>
                </DetailMoreContext.Provider>
              )}
              {showPhotos && p.photoSettings}
            </CardContent>
          </Card>
          {/* 지난 이력은 접어 둬요(08-화면-기본-틀 0장 원칙 1) */}
          <div className="max-lg:order-7">
            <Fold
              title="경과"
              count={events.length}
              icon={<History size={16} />}
              className="mt-0 rounded-[18px] shadow-[0_1px_2px_#1f2a330a]"
            >
              {p.timeline}
            </Fold>
          </div>
          {p.sameZone && <div className="max-lg:order-8">{p.sameZone}</div>}
        </div>
        <div className="flex min-w-0 flex-col gap-4 max-lg:contents">
          {side("money", p.money, "max-lg:order-3")}
          {side("payments", p.payments, "max-lg:order-4")}
          {side("causes", p.causes, "max-lg:order-5")}
          {side("progress", p.progress, "max-lg:order-5")}
          {side("extra", p.extra, "max-lg:order-6")}
        </div>
      </div>
    </div>
  )
}
