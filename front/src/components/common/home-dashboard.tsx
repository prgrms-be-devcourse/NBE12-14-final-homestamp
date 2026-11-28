import type { ReactNode } from "react"
import { ChevronRight, Plus } from "lucide-react"
import { Button } from "@/components/ui/button"
import { EmptyState } from "@/components/common/states"
import { HousePlan, type PlanSpot } from "@/components/common/house-plan"
import { Stamp } from "@/components/common/stamp"
import type { Event, Notice, Repair, Role, Room } from "@/data/demo"
import type { Page } from "@/lib/router"
import { myTurn, theirWaits } from "@/lib/turn"
import { cn } from "cn"
import {
  dropCheck,
  repairWord,
  statusText,
  statusTone,
  type StatusTone,
} from "@/lib/plain-words"
import { planArea } from "@/lib/plan-area"
import {
  daysSince,
  lastActivity,
  moneyLabel,
  staleReason,
} from "@/lib/activity"
import { connectionText } from "@/lib/workflows"

// ── 상태 표시(index.css 토큰 주석, lib/plain-words statusTone) ──
// 꼬리표: 내 차례 = 주황 채운 점, 아직 안 끝남 = 노랑 빈 원, 오래 멈춤 = 회색 점선, 진행 중 = 남색 빈 원, 끝남 = 초록 ✓.
// 색만으로 나누지 않게 점 모양과 글이 늘 함께 가요.

// 작은 상태 꼬리표(수리 줄 · 수리 상세 세 줄). 색 규칙은 Status와 같고 크기만 작게.
// raw: 받은 문구를 그대로(쉬운 말 바꾸기 없이). tone: 오래 멈춤처럼 글만으로 알 수 없는 상태를 부르는 쪽이 정해요.
export function StatusTag({
  children,
  className,
  raw,
  tone,
}: {
  children: string
  className?: string
  raw?: boolean
  tone?: StatusTone
}) {
  const toneOf = tone ?? statusTone(children)
  return (
    <span
      className={cn(
        "status px-1.5 py-0.5 text-[13px] leading-[1.5] whitespace-nowrap",
        toneOf,
        className,
      )}
    >
      {dropCheck(raw ? children : statusText(children), toneOf)}
    </span>
  )
}

// '내 차례 · ○○' 주황 꼬리표(수리 줄 · 수리 상세 제목 옆). 주황은 이 뜻에만 써요.
export function TurnBadge({
  children,
  className,
}: {
  children: ReactNode
  className?: string
}) {
  return (
    <span
      className={cn(
        "inline-flex h-[22px] items-center gap-1.5 self-start rounded-full bg-turn-soft px-[9px] text-xs leading-none font-bold whitespace-nowrap text-turn-ink before:size-1.5 before:rounded-full before:bg-turn before:content-['']",
        className,
      )}
    >
      {children}
    </span>
  )
}

// 작은 점(꼬리표가 아닌 한 줄 글 앞에 붙여요)
function ToneDot({ tone }: { tone: StatusTone }) {
  if (tone === "done") return null
  return (
    <span
      aria-hidden
      className={cn(
        "size-2 flex-none rounded-full",
        tone === "turn" && "bg-turn",
        tone === "wait" && "border-[1.5px] border-wait bg-wait-soft",
        tone === "stale" && "border-[1.5px] border-dashed border-stale",
        tone === "progress" && "border-[1.5px] border-brand",
      )}
    />
  )
}

// 하자 응답의 짧은 말(회색 줄용)
const ackWord = (ack: Repair["ack"]) =>
  (
    ({
      "확인 대기": "답 기다림",
      "보충 요청": "보충 요청됨",
      이견: "이견",
      "상대 미확인": "확인 없음",
    }) as Record<string, string>
  )[ack] ?? ack
const moneyDone = (r: Repair) =>
  moneyLabel(r) === "합의됨" || moneyLabel(r) === "비용 없음"

// 수리 건 한 줄(시안 B2): 왼쪽에 구역과 확인 표시, 가운데에 제목 + 색 꼬리표 하나 + 회색 한 줄, 오른쪽 화살표.
// 색 꼬리표는 지금 핵심 하나만: 내 차례 > (부르는 쪽이 준) 오래 멈춤 > 수리비 남음(수리 끝) > 수리 상태.
// 나머지(하자·수리·수리비)는 회색 줄로 낮추고 끝난 단계는 ✓만 붙여요(08 1장 세 줄은 글로 모두 남아요).
// 홈에서 바로 누르는 버튼은 홈 API가 줄마다 누를 수 있는 행동을 주면 켜요(지금은 화살표만, 08 0장 원칙 6).
export function RepairListItem({
  r,
  onOpen,
  extra,
  thumb,
  role,
  stale,
  action,
}: {
  r: Repair
  onOpen: () => void
  extra?: ReactNode
  thumb?: ReactNode
  role?: Role
  // 오래 멈춘 건이면 그 글(예: "고치는 중 · 18일째 새 기록 없음")
  stale?: string
  // 줄 오른쪽 버튼(진행 상황 묻기 등). 있으면 화살표 대신 둬요
  action?: ReactNode
}) {
  const turn = role ? myTurn(r, role) : undefined
  // 상대의 답을 기다리는 요청(서버 목록 waiting, 06 4-6). 목업은 없음
  const waits = role ? theirWaits(r, role) : []
  const done = r.status === "처리 완료"
  const money = `수리비 ${statusText(moneyLabel(r))}`
  const repair = repairWord(r.status)
  const lead: {
    tone: StatusTone
    text: string
    part: "repair" | "money" | ""
  } = turn
    ? { tone: "turn", text: `내 차례 · ${statusText(turn)}`, part: "" }
    : waits.length
      ? { tone: "wait", text: waits[0], part: "" }
      : stale
        ? { tone: "stale", text: stale, part: "" }
        : done && !moneyDone(r)
          ? { tone: "wait", text: money, part: "money" }
          : {
              tone: done ? "done" : "progress",
              text: repair,
              part: "repair",
            }
  const area = r.zone.split(" · ")[0] || r.zone
  // 사진이 있는 줄은 왼쪽 구역 칸 대신 사진이 오니 구역을 회색 줄 앞에 써요
  const rest = [
    thumb ? area : undefined,
    // 꼬리표에 못 담은 나머지 기다림(내 차례가 꼬리표면 전부)
    ...waits.slice(turn ? 0 : 1),
    r.ack === "확인됨" ? "✓ 하자 확인" : `하자 ${ackWord(r.ack)}`,
    lead.part === "repair" ? undefined : done ? "✓ 수리 끝" : repair,
    lead.part === "money" ? undefined : moneyDone(r) ? "✓ 수리비 정함" : money,
  ]
    .filter(Boolean)
    .join(" · ")
  const label = `${lead.text}. ${r.title}. ${r.zone}. ${rest}`
  const body = (
    <>
      {thumb ? null : (
        <span className="flex w-12 flex-none flex-col items-start gap-1.5">
          <span
            className={cn(
              "text-[13px] font-extrabold",
              turn ? "text-turn-ink" : "text-sub",
            )}
          >
            {area}
          </span>
          {r.ack === "확인됨" && <Stamp seed={r.id} size={38} />}
        </span>
      )}
      <span className="flex min-w-0 flex-1 flex-col gap-1.5">
        <strong className="text-base leading-snug font-bold text-ink [overflow-wrap:anywhere]">
          {r.title}
        </strong>
        <StatusTag raw tone={lead.tone} className="self-start font-bold">
          {lead.text}
        </StatusTag>
        <small className="text-[13px] text-hint">{rest}</small>
        {extra && <span className="mt-1 flex flex-wrap gap-1">{extra}</span>}
      </span>
    </>
  )
  // 카드는 모두 흰 바탕, 내 차례만 주황 테두리
  const shell = cn(
    "flex w-full items-center gap-3 rounded-[12px] bg-white px-3 py-3.5 text-left",
    turn
      ? "shadow-[inset_0_0_0_1.5px_var(--color-turn-line)]"
      : "shadow-[inset_0_0_0_1px_var(--color-line)]",
  )
  const focus =
    "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand"
  const chevron = (
    <ChevronRight
      size={18}
      className={cn("flex-none text-faint", thumb && "max-md:hidden")}
      aria-hidden
    />
  )
  if (!thumb && !action)
    return (
      <button
        type="button"
        className={cn(shell, focus, "hover:brightness-[0.985]")}
        onClick={onOpen}
        aria-label={label}
      >
        {body}
        {chevron}
      </button>
    )
  return (
    <div className={shell}>
      {thumb && (
        // !: 사진 갤러리의 옛 CSS(.gallery-compact 크기)보다 앞서게. 갤러리를 Tailwind로 옮기면 ! 를 지워요
        <div className="relative size-16 flex-none overflow-visible max-md:size-14 [&>*:first-child]:size-full! [&>*:first-child]:overflow-hidden [&>*:first-child]:rounded-[10px]">
          {thumb}
          {r.ack === "확인됨" && (
            <Stamp
              seed={r.id}
              size={30}
              className="absolute -right-2 -bottom-2 bg-white/85"
            />
          )}
        </div>
      )}
      <button
        type="button"
        className={cn(
          "flex min-w-0 flex-1 items-center gap-3 rounded-[10px] py-0.5 text-left",
          focus,
        )}
        onClick={onOpen}
        aria-label={label}
      >
        {body}
        {!action && chevron}
      </button>
      {action && <div className="flex-none">{action}</div>}
    </div>
  )
}

// 홈 묶음 제목(카드 테두리 없이 제목 + 오른쪽 링크)
function Group({
  title,
  action,
  children,
  id,
}: {
  title: string
  action?: ReactNode
  children: ReactNode
  id: string
}) {
  return (
    <section aria-labelledby={id} className="flex min-w-0 flex-col gap-2">
      <div className="flex items-baseline justify-between gap-3">
        <h2 id={id} className="m-0 text-base font-extrabold text-ink">
          {title}
        </h2>
        {action}
      </div>
      {children}
    </section>
  )
}
const linkBtn =
  "h-auto p-0 text-[13px] font-bold text-brand hover:text-brand-ink"

export interface HomeDashboardProps {
  role: Role
  room: Room
  rooms: Room[]
  repairs: Repair[]
  events: Event[]
  notices: Notice[]
  moneyLeft: Repair[]
  readOnly: boolean
  nextStep: (room: Room) => string
  // 이 계정이 그 호실에서 어느 쪽인지(사는 집 / 관리하는 호실 나누기)
  sideFor: (room: Room) => Role | undefined
  onOpenRepair: (id: string) => void
  onOpenRoom: (id: string) => void
  onGo: (p: Page) => void
  onNewRepair: () => void
  onNewRoom: () => void
  onOpenNotice: (n: Notice) => void
  moneyNotice: (r: Repair) => ReactNode
  thumb?: (r: Repair) => ReactNode
}

// 홈(S-03, 시안 B): 내가 답할 일 → 집 평면도 → 오래 멈춘 수리 · 수리비 정리 · 최근 수리 → 지금 보는 호실.
// 휴대폰에서는 답할 일이 반드시 맨 위(08 12장). PC에서는 평면도와 호실을 오른쪽 칸에 둬요.
export function HomeDashboard(p: HomeDashboardProps) {
  const { role, room, rooms, repairs, events, notices, moneyLeft } = p
  const toAnswer = notices.filter((n) => !n.read)
  const stale = repairs.filter((r) => staleReason(r, events))
  const moneyIds = new Set(moneyLeft.map((r) => r.id))
  const staleIds = new Set(stale.map((r) => r.id))
  const recent = repairs
    .filter((r) => !staleIds.has(r.id) && !moneyIds.has(r.id))
    .slice(0, 4)
  const staleText = (r: Repair) =>
    `${daysSince(lastActivity(r, events))}일째 새 기록 없음`
  // 평면도 점은 세 가지만: 내가 할 일 · 진행 중(오래 멈춤·수리비 남음 포함) · 끝남(수리 끝 + 수리비 정리 끝)
  const spots: PlanSpot[] = repairs.map((r) => ({
    area: planArea(r.zone),
    tone: myTurn(r, role)
      ? "turn"
      : r.status === "처리 완료" && !moneyIds.has(r.id)
        ? "done"
        : "progress",
  }))
  // 평면도와 호실 카드는 휴대폰(본문 순서 안)과 PC(오른쪽 칸)에 따로 그려요. 한쪽은 늘 숨겨져 있어요
  const planSection = (key: string, className: string) =>
    rooms.length > 0 && (
      <section
        aria-labelledby={`home-plan-${key}`}
        className={cn("flex min-w-0 flex-col gap-2.5", className)}
      >
        <h2
          id={`home-plan-${key}`}
          className="m-0 text-base font-extrabold text-ink"
        >
          {room.name}에서 손볼 곳
        </h2>
        <HousePlan spots={spots} title={room.name} />
      </section>
    )
  const roomSection = (key: string, className: string) => (
    <RoomSection p={p} id={`home-room-${key}`} className={className} />
  )
  return (
    <div className="grid grid-cols-1 items-start gap-5 lg:grid-cols-[minmax(0,1.4fr)_minmax(320px,1fr)]">
      <div className="flex min-w-0 flex-col gap-5">
        {/* 내가 답할 일: 주황 = 내 차례 */}
        <section
          aria-labelledby="home-answer"
          className="flex min-w-0 flex-col gap-3 rounded-[14px] bg-white p-4 shadow-[inset_0_0_0_1.5px_var(--color-turn)]"
        >
          <div className="flex items-center gap-2.5">
            <span
              className={cn(
                "inline-grid h-[34px] min-w-[34px] place-items-center rounded-full px-2 text-[17px] font-extrabold tabular-nums",
                toAnswer.length ? "bg-turn text-white" : "bg-tag text-sub",
              )}
            >
              {toAnswer.length}
            </span>
            <h2
              id="home-answer"
              className="m-0 text-lg font-extrabold text-ink"
            >
              내가 답할 일
            </h2>
          </div>
          {toAnswer.length ? (
            <div className="flex flex-col gap-2">
              {toAnswer.slice(0, 5).map((n) => (
                <button
                  type="button"
                  key={n.id}
                  className="flex min-h-14 w-full items-center gap-3 rounded-[12px] bg-white p-3 text-left shadow-[inset_0_0_0_1px_var(--color-turn-line)] hover:bg-turn-bg focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand"
                  onClick={() => p.onOpenNotice(n)}
                >
                  <ToneDot tone="turn" />
                  <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                    <strong className="text-[15px] font-bold text-ink [overflow-wrap:anywhere]">
                      {n.title}
                    </strong>
                    <small className="text-[13px] text-sub">
                      {rooms.find((r) => r.id === n.roomId)?.name ?? ""} · 내
                      답을 기다려요
                    </small>
                  </span>
                  <ChevronRight
                    size={18}
                    className="flex-none text-faint"
                    aria-hidden
                  />
                </button>
              ))}
              {toAnswer.length > 5 && (
                <Button
                  variant="link"
                  className={linkBtn}
                  onClick={() => p.onGo("notifications")}
                >
                  {toAnswer.length}건 모두 보기
                </Button>
              )}
            </div>
          ) : (
            <p className="m-0 text-sm text-sub">
              지금 답할 일이 없어요. 상대가 확인을 요청하면 여기에 먼저 떠요.
            </p>
          )}
        </section>

        {/* 휴대폰: 답할 일 바로 아래에 평면도 */}
        {planSection("m", "lg:hidden")}

        {/* 수리 묶음 */}
        <div className="flex min-w-0 flex-col gap-5">
          {stale.length > 0 && (
            <Group id="home-stale" title="오래 멈춘 수리">
              <div className="flex flex-col gap-2">
                {stale.map((r) => (
                  <RepairListItem
                    key={r.id}
                    r={r}
                    role={role}
                    stale={staleText(r)}
                    onOpen={() => p.onOpenRepair(r.id)}
                  />
                ))}
              </div>
            </Group>
          )}
          {moneyLeft.length > 0 && (
            <Group
              id="home-money"
              title="수리비 정리가 남은 수리"
              action={
                <Button
                  variant="link"
                  className={linkBtn}
                  onClick={() => p.onGo("money")}
                >
                  수리비 보기
                </Button>
              }
            >
              <div className="flex flex-col gap-2">
                {moneyLeft.slice(0, 3).map((r) => (
                  <div key={r.id} className="flex flex-col gap-2">
                    <RepairListItem
                      r={r}
                      role={role}
                      onOpen={() => p.onOpenRepair(r.id)}
                    />
                    {p.moneyNotice(r)}
                  </div>
                ))}
              </div>
            </Group>
          )}
          <Group
            id="home-recent"
            title="최근 수리"
            action={
              repairs.length > 0 && (
                <Button
                  variant="link"
                  className={linkBtn}
                  onClick={() => p.onGo("repairs")}
                >
                  {repairs.length}건 모두 보기
                </Button>
              )
            }
          >
            {repairs.length ? (
              recent.length ? (
                <div className="flex flex-col gap-2">
                  {recent.map((r) => (
                    <RepairListItem
                      key={r.id}
                      r={r}
                      role={role}
                      thumb={p.thumb?.(r)}
                      onOpen={() => p.onOpenRepair(r.id)}
                    />
                  ))}
                </div>
              ) : (
                <p className="m-0 text-sm text-sub">
                  남은 수리는 모두 위에 있어요.
                </p>
              )
            ) : (
              <EmptyState
                title="아직 남긴 하자 기록이 없어요"
                description="고장이나 하자를 사진과 함께 남겨 두면 상대와 같은 기록을 볼 수 있어요."
              />
            )}
          </Group>
        </div>

        {/* 휴대폰: 맨 아래에 지금 보는 호실 */}
        {roomSection("m", "lg:hidden")}
      </div>

      {/* PC: 오른쪽 칸에 평면도와 호실 */}
      <aside className="hidden min-w-0 flex-col gap-5 lg:flex">
        {planSection("d", "")}
        {roomSection("d", "")}
      </aside>
    </div>
  )
}

function RoomSection({
  p,
  id,
  className,
}: {
  p: HomeDashboardProps
  id: string
  className: string
}) {
  const { room, rooms } = p
  return (
    <section
      aria-labelledby={id}
      className={cn(
        "flex min-w-0 flex-col gap-2 rounded-[14px] bg-white p-4",
        className,
      )}
    >
      {!rooms.length ? (
        <EmptyState
          title="첫 호실 기록을 만들어 보세요"
          action={
            <Button onClick={p.onNewRoom}>
              <Plus />
              호실 기록 만들기
            </Button>
          }
        />
      ) : (
        <>
          <div className="flex items-baseline justify-between gap-3">
            <h2 id={id} className="m-0 text-[15px] font-extrabold text-ink">
              {room.name}
            </h2>
            {rooms.length > 1 && (
              <Button
                variant="link"
                className={linkBtn}
                onClick={() => p.onGo("rooms")}
              >
                다른 호실 {rooms.length - 1}곳
              </Button>
            )}
          </div>
          {room.address && (
            <p className="m-0 text-[13px] text-sub">{room.address}</p>
          )}
          <p className="m-0 text-[13px] text-sub">
            {statusText(room.status)} · {statusText(connectionText(room))}
          </p>
          <p className="m-0 text-sm font-bold text-brand">
            다음 할 일: {p.nextStep(room)}
          </p>
          <div className="mt-1 flex gap-2">
            <Button
              variant="outline"
              className="h-11 flex-1"
              onClick={() => p.onGo("inspection")}
            >
              점검 · 정산
            </Button>
            <Button
              variant="outline"
              className="h-11 flex-1"
              onClick={() => p.onGo("connection")}
            >
              함께 보는 사람
            </Button>
          </div>
        </>
      )}
    </section>
  )
}
