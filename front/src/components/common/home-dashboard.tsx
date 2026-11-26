import type { ReactNode } from "react"
import {
  ArrowRight,
  Building2,
  ChevronRight,
  Clock,
  Handshake,
  Plus,
  Wallet,
  Wrench,
} from "lucide-react"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Status } from "@/components/common/blocks"
import { EmptyState } from "@/components/common/states"
import type { Event, Notice, Repair, Role, Room } from "@/data/demo"
import type { Page } from "@/lib/router"
import { myTurn } from "@/lib/turn"
import { cn } from "cn"
import { plainText } from "@/lib/plain-words"
import { daysSince, lastActivity, moneyLabel } from "@/lib/activity"
import { sideName } from "@/lib/words"
import { connectionText } from "@/lib/workflows"

// 홈 카드 한 장(제목 · 설명 · 오른쪽 버튼 + 내용). warm: 내가 답할 것처럼 맨 위에서 눈에 띄어야 하는 카드
function Section({
  title,
  description,
  action,
  children,
  icon,
  warm,
}: {
  title: string
  description?: string
  action?: ReactNode
  children: ReactNode
  icon?: ReactNode
  warm?: boolean
}) {
  return (
    <Card
      className={cn(
        "min-w-0 gap-3 rounded-[18px] border py-5 shadow-[0_1px_2px_#1720330a] ring-0",
        warm ? "border-[#f6dcb8] bg-[#fffaf3]" : "border-line bg-white",
      )}
    >
      <CardHeader className="flex items-start justify-between gap-3 px-5">
        <div>
          <CardTitle className="flex items-center gap-2 text-[17px] leading-[1.45] font-bold text-ink">
            {icon}
            {title}
          </CardTitle>
          {description && (
            <CardDescription className="mt-0.5 text-[13px] text-sub">
              {description}
            </CardDescription>
          )}
        </div>
        {action}
      </CardHeader>
      <CardContent className="px-5">{children}</CardContent>
    </Card>
  )
}

// 누르는 한 줄(수리 건·알림·호실 공통 모양)
const row =
  "flex min-h-14 w-full items-center gap-3 rounded-[14px] border p-3 text-left focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand"
const rowPlain =
  "border-line bg-white hover:border-[#cfdaf1] hover:bg-[#fafbfe]"
// 내가 답할 차례인 줄: 주황 테두리 + 왼쪽 띠(주황은 '내 차례'에만 써요)
const rowTurn =
  "border-turn-line bg-turn-bg shadow-[inset_3px_0_0_var(--color-turn)] hover:border-[#eeb46a] hover:bg-[#fff5e8]"
// 지금 보는 호실: 연한 파랑
const rowCurrent = "border-[#9db7ef] bg-[#f7f9ff] hover:bg-[#f2f6ff]"
const rowIcon = "grid size-[38px] flex-none place-items-center rounded-[11px]"
const rowMain =
  "flex min-w-0 flex-1 flex-col gap-[3px] [&_small]:text-sm [&_small]:text-sub [&_strong]:text-base [&_strong]:leading-[inherit] [&_strong]:font-bold [&_strong]:text-ink [&_strong]:[overflow-wrap:anywhere]"
const chevron = "flex-none text-faint"

// 수리 건 한 줄: 내가 답할 일이 있으면 '내 차례'를 맨 앞에, 수리 상태와 비용 상태는 따로(수리와 돈은 따로 끝나요).
// 사진이 있으면 카드 안 왼쪽에 정사각형 미리보기를 둬요. 사진은 줄 버튼 밖에 있어서 사진만 따로 크게 볼 수 있어요.
export function RepairListItem({
  r,
  onOpen,
  extra,
  thumb,
  role,
}: {
  r: Repair
  onOpen: () => void
  extra?: ReactNode
  thumb?: ReactNode
  role?: Role
}) {
  const turn = role ? myTurn(r, role) : undefined
  const label = `${turn ? `내 차례 ${turn} · ` : ""}${r.photos.length ? "" : "상태 사진 예시 "}${r.title} ${r.zone} ${r.date} ${r.status} 비용 · ${moneyLabel(r)}`
  const body = (
    <>
      <span className={rowMain}>
        {turn && <TurnBadge>내 차례 · {turn}</TurnBadge>}
        <strong>{r.title}</strong>
        <small>
          {r.zone} · {r.date} · {sideName(r.author)} 등록
        </small>
        <span className="mt-1 flex flex-wrap gap-1">
          <StatusTag>{`수리 · ${r.status}`}</StatusTag>
          <StatusTag>{`비용 · ${moneyLabel(r)}`}</StatusTag>
          {extra}
        </span>
      </span>
      {/* 휴대폰 사진 줄에서는 화살표를 빼서 상태 꼬리표가 한 줄에 들어가게 */}
      <ChevronRight
        size={18}
        className={cn(chevron, thumb && "max-md:hidden")}
        aria-hidden
      />
    </>
  )
  if (!thumb)
    return (
      <button
        type="button"
        className={cn(row, turn ? rowTurn : rowPlain)}
        onClick={onOpen}
        aria-label={label}
      >
        <span className={cn(rowIcon, "bg-brand-soft text-brand")} aria-hidden>
          <Wrench size={18} />
        </span>
        {body}
      </button>
    )
  return (
    <div
      className={cn(
        "flex items-center gap-3 rounded-[14px] border p-2.5",
        turn ? rowTurn : rowPlain,
      )}
    >
      {/* !: 사진 갤러리의 옛 CSS(.gallery-compact 크기)보다 앞서게. 갤러리를 Tailwind로 옮기면 ! 를 지워요 */}
      <div className="size-16 flex-none overflow-hidden rounded-[10px] max-md:size-14 [&>*]:size-full!">
        {thumb}
      </div>
      <button
        type="button"
        className="flex min-w-0 flex-1 items-center gap-3 rounded-[10px] py-0.5 text-left focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand"
        onClick={onOpen}
        aria-label={label}
      >
        {body}
      </button>
    </div>
  )
}

// 작은 상태 꼬리표(수리 줄 · 수리 상세 세 줄 요약). 글자와 색 규칙은 Status와 같고(plainText · 이견/대기/완료 색), 크기만 작게.
const tagTone = (t: string) =>
  /이견|미확인/.test(t)
    ? "bg-[#fff0ed] text-[#cc7869]"
    : /대기|미정|초대/.test(t)
      ? "bg-[#fff6e4] text-[#bc9146]"
      : /완료|합의됨|확인됨|연결됨/.test(t)
        ? "bg-[#eaf7f0] text-[#3a9271]"
        : "bg-[#edf3ff] text-[#4779cd]"
// raw: 받은 문구를 그대로(수리 상세 세 줄처럼 원래 쉬운 말 바꾸기 없이 보이던 곳)
export function StatusTag({
  children,
  className,
  raw,
}: {
  children: string
  className?: string
  raw?: boolean
}) {
  return (
    <span
      className={cn(
        "inline-flex items-center rounded-[5px] px-1.5 py-0.5 text-[13px] leading-[1.5] font-semibold whitespace-nowrap",
        tagTone(children),
        className,
      )}
    >
      {raw ? children : plainText(children)}
    </span>
  )
}

// '내 차례 · ○○' 주황 배지(수리 줄 · 수리 상세 제목 옆)
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
        "inline-flex h-[22px] items-center gap-1 self-start rounded-full bg-turn-soft px-[9px] text-xs leading-none font-bold whitespace-nowrap text-turn-ink before:size-1.5 before:rounded-full before:bg-turn before:content-['']",
        className,
      )}
    >
      {children}
    </span>
  )
}

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
// 홈(S-03): 내가 답할 것 → 핵심 2칸 요약 → 최근 하자·수리 + 지금 보는 호실(중복 카드·긴 이력 제거)
export function HomeDashboard(p: HomeDashboardProps) {
  const { role, room, rooms, repairs, events, notices, moneyLeft } = p
  const toAnswer = notices.filter((n) => !n.read)
  const stale = repairs.filter(
    (r) =>
      r.status === "처리 중" && (daysSince(lastActivity(r, events)) ?? 0) > 14,
  )
  const open = repairs.filter((r) => r.status !== "처리 완료")
  // '내가 답할 것'은 맨 위 강조 카드에 이미 있으므로 요약 칸에는 수리·돈 2개만 둬요
  const stats = [
    {
      label: "진행 중인 수리",
      value: open.length,
      icon: Wrench,
      go: "repairs" as Page,
    },
    {
      label: "돈이 남은 건",
      value: moneyLeft.length,
      icon: Wallet,
      go: "money" as Page,
    },
  ]
  const list = "flex flex-col gap-2"
  return (
    <div className="flex flex-col gap-4">
      {/* 확인할 일은 휴대폰에서 반드시 맨 위(화면 기본 틀 12장) */}
      <Section
        warm
        title="내가 답할 것"
        icon={
          <span
            className="inline-block size-2 rounded-full bg-turn"
            aria-hidden
          />
        }
        action={
          toAnswer.length > 0 ? (
            <span className="inline-flex h-7 min-w-7 items-center justify-center rounded-full bg-turn px-2 text-sm font-bold text-white tabular-nums">
              {toAnswer.length}
            </span>
          ) : undefined
        }
      >
        {toAnswer.length ? (
          <div className={list}>
            {toAnswer.slice(0, 5).map((n) => (
              <button
                type="button"
                key={n.id}
                className={cn(row, rowPlain)}
                onClick={() => p.onOpenNotice(n)}
              >
                <span
                  className={cn(rowIcon, "bg-warn-soft text-warn-ink")}
                  aria-hidden
                >
                  <Handshake size={18} />
                </span>
                <span className={rowMain}>
                  <strong>{n.title}</strong>
                  <small>
                    {rooms.find((r) => r.id === n.roomId)?.name ?? ""} · 답을
                    기다려요
                  </small>
                </span>
                <ChevronRight size={18} className={chevron} aria-hidden />
              </button>
            ))}
            {toAnswer.length > 5 && (
              <Button variant="link" onClick={() => p.onGo("notifications")}>
                전체 {toAnswer.length}건 보기
              </Button>
            )}
          </div>
        ) : (
          <p className="rounded-[12px] bg-white/75 px-3.5 py-2.5 text-sm text-sub">
            지금 답할 일이 없어요. 상대가 확인을 요청하면 여기에 먼저 떠요.
          </p>
        )}
      </Section>
      {/* 핵심 요약 2칸 */}
      <div className="grid grid-cols-2 gap-2.5">
        {stats.map((s) => (
          <button
            type="button"
            key={s.label}
            className="flex min-w-0 items-center gap-3 rounded-[16px] border border-line bg-white p-3.5 text-left hover:border-line-strong focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand md:p-4"
            onClick={() => p.onGo(s.go)}
          >
            <span
              className="grid size-9 flex-none place-items-center rounded-[10px] bg-brand-soft text-brand md:size-10"
              aria-hidden
            >
              <s.icon size={18} />
            </span>
            <span className="min-w-0 flex-1">
              <small className="block truncate text-[13px] text-sub">
                {s.label}
              </small>
              <strong className="block text-[22px] leading-tight font-extrabold text-ink tabular-nums md:text-[26px]">
                {s.value}
                <span className="ml-0.5 text-sm font-semibold text-sub">
                  건
                </span>
              </strong>
            </span>
            <ChevronRight
              size={16}
              className="flex-none text-faint max-sm:hidden"
              aria-hidden
            />
          </button>
        ))}
      </div>
      {/* PC: 2단(좌: 하자·수리 / 우: 지금 보는 호실) · 모바일: 1단 */}
      <div className="grid grid-cols-1 items-start gap-4 lg:grid-cols-[minmax(0,1.4fr)_minmax(300px,1fr)]">
        <div className="flex min-w-0 flex-col gap-4">
          {stale.length > 0 && (
            <Section
              title="오래 멈춘 건"
              icon={<Clock size={18} aria-hidden />}
              description="14일 넘게 움직임이 없는 수리 건이에요."
            >
              <div className={list}>
                {stale.map((r) => (
                  <RepairListItem
                    key={r.id}
                    r={r}
                    role={role}
                    onOpen={() => p.onOpenRepair(r.id)}
                    extra={
                      <Status>{`처리 중 · ${daysSince(lastActivity(r, events))}일째`}</Status>
                    }
                  />
                ))}
              </div>
            </Section>
          )}
          {moneyLeft.length > 0 && (
            <Section
              title="고쳤지만 돈이 남은 건"
              icon={<Wallet size={18} aria-hidden />}
              action={
                <Button variant="link" onClick={() => p.onGo("money")}>
                  비용 보기
                  <ChevronRight />
                </Button>
              }
            >
              <div className={list}>
                {moneyLeft.slice(0, 3).map((r) => (
                  <div
                    key={r.id}
                    className="flex flex-col gap-2 rounded-[16px] bg-soft p-2"
                  >
                    <RepairListItem
                      r={r}
                      role={role}
                      onOpen={() => p.onOpenRepair(r.id)}
                    />
                    {p.moneyNotice(r)}
                  </div>
                ))}
              </div>
            </Section>
          )}
          <Section
            title="최근 하자 · 수리"
            action={
              <Button variant="link" onClick={() => p.onGo("repairs")}>
                전체 보기
                <ChevronRight />
              </Button>
            }
          >
            {repairs.length ? (
              <div className={list}>
                {repairs.slice(0, 4).map((r) => (
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
              <EmptyState
                title="아직 남긴 하자 기록이 없어요"
                description="고장이나 하자를 사진과 함께 남겨 두면 상대와 같은 기록을 볼 수 있어요."
              />
            )}
          </Section>
        </div>
        <div className="flex min-w-0 flex-col gap-4">
          {!rooms.length ? (
            <Section title="내 호실">
              <EmptyState
                title="첫 호실 기록을 만들어 보세요"
                action={
                  <Button onClick={p.onNewRoom}>
                    <Plus />
                    호실 기록 만들기
                  </Button>
                }
              />
            </Section>
          ) : (
            <Section
              title="지금 보는 호실"
              action={
                <Button variant="link" onClick={() => p.onGo("rooms")}>
                  {rooms.length > 1 ? `전체 ${rooms.length}곳` : "관리"}
                  <ChevronRight />
                </Button>
              }
            >
              <button
                type="button"
                className={cn(row, rowCurrent)}
                onClick={() => p.onGo("rooms")}
              >
                <span
                  className={cn(rowIcon, "bg-brand-soft text-brand")}
                  aria-hidden
                >
                  <Building2 size={20} />
                </span>
                <span className={rowMain}>
                  <strong>{room.name}</strong>
                  <small>{room.address}</small>
                  <span className="badges mt-1">
                    <Status>{room.status}</Status>
                    <Status>{connectionText(room)}</Status>
                  </span>
                  <span className="mt-1 flex items-center gap-1.5 text-sm font-semibold text-brand">
                    <ArrowRight size={14} aria-hidden />
                    {p.nextStep(room)}
                  </span>
                </span>
              </button>
              <div className="mt-3 flex gap-2">
                <Button
                  variant="outline"
                  className="flex-1"
                  onClick={() => p.onGo("inspection")}
                >
                  점검 · 정산
                </Button>
                <Button
                  variant="outline"
                  className="flex-1"
                  onClick={() => p.onGo("connection")}
                >
                  상대 연결
                </Button>
              </div>
            </Section>
          )}
        </div>
      </div>
    </div>
  )
}
