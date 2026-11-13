import { useCallback, useEffect, useRef, useState, type ReactNode } from "react"
import { DetailMoreContext, type MoreItem } from "@/components/detail-more"
import {
  ArrowLeft,
  Camera,
  Eye,
  MoreHorizontal,
  Pencil,
  Plus,
  Share2,
} from "lucide-react"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import {
  other,
  type Event,
  type Repair,
  type Role,
  type Room,
} from "@/data/demo"
import {
  recordTimeMs,
  roomSoloReason,
  soloReasonText,
} from "@/lib/repair-rules"
import { daysSince, StatusTag, TurnBadge } from "@/components/home-dashboard"
import { cn } from "cn"
import { myTurn } from "@/lib/turn"
import { sideName, withSubject } from "@/lib/words"

const ackBadge: Record<Repair["ack"], { text: string; tone: string }> = {
  확인됨: { text: "하자가 있었다고 확인됨", tone: "green" },
  "확인 대기": { text: "하자 확인 대기", tone: "amber" },
  이견: { text: "하자에 이견 있음", tone: "rose" },
  "상대 미확인": {
    text: "확인 없음 · 72시간 동안 답이 오지 않았어요",
    tone: "rose",
  },
}
const REPAIR_STEPS: Repair["status"][] = [
  "확인 전",
  "확인 완료",
  "처리 중",
  "처리 완료",
]
const moneyTone = (r: Repair) => {
  const m = r.proposal?.status || "미정"
  return m === "합의됨" || m === "비용 없음"
    ? "green"
    : m === "이견"
      ? "rose"
      : "amber"
}
const statusTone = (s: Repair["status"]) =>
  s === "처리 완료" ? "green" : s === "처리 중" ? "blue" : "amber"
// 상태 색(기존 Status 색과 같아요)
const ackTag: Record<string, string> = {
  green: "bg-[#eaf7f0] text-[#3a9271]",
  amber: "bg-[#fff6e4] text-[#bc9146]",
  rose: "bg-[#fff0ed] text-[#cc7869]",
  blue: "bg-[#edf3ff] text-[#4779cd]",
}

// 수리 상태 한 줄 요약과 다음 할 일(수리 상태와 돈 상태는 따로 보여 줘요)
export function repairSummary(
  r: Repair,
  role: Role,
  room: Room,
  events: Event[],
) {
  const started =
    events
      .filter((e) => e.target?.id === r.id && /수리 시작/.test(e.text))
      .map((e) => recordTimeMs(e.at))
      .find((t) => t !== undefined) ?? recordTimeMs(r.date)
  const days = daysSince(started)
  const them = sideName(other(role))
  const themSubject = withSubject(them)
  const solo = roomSoloReason(room)
  if (r.status === "처리 완료")
    return r.soloClosed
      ? {
          title: `처리 완료(${sideName(r.soloClosed)} 확인)`,
          body:
            r.soloClosed === role
              ? `${themSubject} 종료를 확인하면 처리 완료(둘 다 확인)가 돼요.${r.soloReason ? ` ${soloReasonText[r.soloReason]}` : ""}`
              : "확인하거나, 아직 안 끝났다면 사유와 함께 다시 열 수 있어요.",
        }
      : {
          title: "처리 완료(둘 다 확인)",
          body: "같은 곳에 다시 문제가 생기면 이 건을 이어서 새 수리 건으로 남겨요.",
        }
  if (r.closeBy)
    return r.closeBy === role
      ? {
          title: "종료 확인을 기다려요",
          body: `${themSubject} 확인하면 끝나요. 아직이라면 ${themSubject} 사유를 남겨요.`,
        }
      : {
          title: `${themSubject} 종료를 요청했어요`,
          body: "다 고쳐졌으면 종료를 확인하고, 아직이면 사유를 남겨 주세요.",
        }
  if (r.status === "처리 중")
    return {
      title: `처리 중 · ${days ? `${days}일째` : "오늘 시작"}`,
      body: `다 고쳐지면 종료를 요청해 주세요. ${solo ? "기다리지 않고 끝낼 수 있어요." : `${themSubject} 확인하면 끝나요.`}`,
    }
  if (r.status === "확인 완료")
    return {
      title: "확인됐어요",
      body: "수리를 시작하면 진행 상황이 기록으로 남아요.",
    }
  return r.author === role
    ? {
        title: `${them} 확인 기다림${days ? ` · ${days}일째` : ""}`,
        body: solo
          ? soloReasonText[solo]
          : "확인 전에도 수리를 시작할 수 있어요. 시작하면 “상대 확인 전 수리 시작”으로 남아요.",
      }
    : {
        title: "하자 내용을 확인해 주세요",
        body: "사진과 설명이 맞으면 확인을, 다르면 사유와 함께 이견을 남겨 주세요.",
      }
}

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
  sameZone?: ReactNode
  notices?: ReactNode
  extra?: ReactNode
  photoSettings?: ReactNode
  onEdit?: () => void
  onAddPhotos?: () => void
  onNewRepair?: () => void
  onShare?: () => void
}
// 내 차례가 오른쪽 칸(수리비 · 지불) 카드에 있는 경우: 휴대폰에서 그 카드를 사진 카드 바로 아래로 올리고 주황 테두리로 강조
const turnCard = (turn?: string) =>
  turn === "비용 제안 응답" || turn === "바꾸자는 안 응답"
    ? "money"
    : turn === "지불 금액 확인"
      ? "payments"
      : undefined
const card =
  "min-w-0 gap-3.5 rounded-[18px] border border-line bg-white py-5 shadow-[0_1px_2px_#1720330a] ring-0"
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
  const ack = ackBadge[r.ack]
  const s = repairSummary(r, role, room, events)
  const turn = p.isGuest ? undefined : myTurn(r, role)
  const lift = turnCard(turn)
  const hasMore = Boolean(
    extra.length ||
    p.onEdit ||
    p.onAddPhotos ||
    p.onNewRepair ||
    p.photoSettings ||
    p.onShare,
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
  const rowLabel = "w-8 flex-none pt-0.5 text-[13px] font-bold text-sub"
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
            className="absolute top-full right-0 z-20 mt-2 flex w-72 max-w-[calc(100vw-36px)] flex-col overflow-hidden rounded-[12px] border border-line bg-white shadow-[0_8px_24px_#17203314]"
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
          </div>
        )}
      </header>
      {/* 하자 / 수리 / 돈 세 줄(서로 막지 않고 따로 움직여요). 상태 문구는 기존 그대로 */}
      <section
        aria-label="상태 요약"
        className="divide-y divide-line rounded-[18px] border border-line bg-white px-4 py-3.5 shadow-[0_1px_2px_#1720330a] md:px-5"
      >
        <div className={row}>
          <span className={rowLabel}>하자</span>
          <div className="flex min-w-0 flex-1 flex-wrap gap-1.5">
            <StatusTag raw className={ackTag[ack.tone]}>
              {ack.text}
            </StatusTag>
          </div>
        </div>
        <div className={row}>
          <span className={rowLabel}>수리</span>
          <div className="flex min-w-0 flex-1 flex-col gap-2">
            <div className="flex flex-wrap items-center gap-1.5">
              <StatusTag raw className={ackTag[statusTone(r.status)]}>
                {r.status}
              </StatusTag>
              {r.startFlag && (
                <StatusTag raw className={ackTag.amber}>
                  {r.startFlag}
                </StatusTag>
              )}
            </div>
            <ol
              className="m-0 flex list-none gap-1 p-0"
              aria-label={`수리 진행 단계 · ${r.status}`}
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
                    {x}
                  </span>
                </li>
              ))}
            </ol>
          </div>
        </div>
        {!p.isGuest && (
          <div className={row}>
            <span className={rowLabel}>돈</span>
            <div className="flex min-w-0 flex-1 flex-wrap items-center gap-x-2 gap-y-1">
              <StatusTag raw className={ackTag[moneyTone(r)]}>
                {r.proposal?.status || "미정"}
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
          <Card className={cn(card, "max-lg:order-7")}>
            <CardHeader className="flex items-start justify-between gap-3 px-5">
              <CardTitle className="text-[17px] leading-[1.45] font-bold text-ink">
                경과
              </CardTitle>
            </CardHeader>
            <CardContent className="px-5">{p.timeline}</CardContent>
          </Card>
          {p.sameZone && <div className="max-lg:order-8">{p.sameZone}</div>}
        </div>
        <div className="flex min-w-0 flex-col gap-4 max-lg:contents">
          {side("money", p.money, "max-lg:order-3")}
          {side("payments", p.payments, "max-lg:order-4")}
          {side("causes", p.causes, "max-lg:order-5")}
          {side("extra", p.extra, "max-lg:order-6")}
        </div>
      </div>
    </div>
  )
}
