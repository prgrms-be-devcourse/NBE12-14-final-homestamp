import type { ReactNode } from "react"
import { House, ClipboardCheck } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import type { Event } from "@/data/demo"
import { recordTimeMs } from "@/lib/repair-rules"
import { plainText } from "@/lib/plain-words"
import { sideText } from "@/lib/words"

export function Status({ children }: { children: ReactNode }) {
  const parts = Array.isArray(children) ? children : [children]
  const plain = parts.every(
    (p) => typeof p === "string" || typeof p === "number",
  )
  const t = plain ? parts.join("") : String(children)
  if (plain) children = plainText(t)
  const tone = /이견|미확인/.test(t)
    ? "rose"
    : /대기|미정|초대/.test(t)
      ? "amber"
      : /완료|합의됨|확인됨|연결됨/.test(t)
        ? "green"
        : "blue"
  return (
    <Badge variant="secondary" className={`status ${tone}`}>
      {children}
    </Badge>
  )
}
// 진행 단계 표시: current보다 앞은 끝난 단계, current는 지금 단계예요(-1이면 시작 전).
export function Stepper({
  steps,
  current,
  label,
}: {
  steps: string[]
  current: number
  label: string
}) {
  return (
    <ol className="svc-stepper" aria-label={label}>
      {steps.map((s, i) => (
        <li
          key={s}
          className={
            i < current ? "is-done" : i === current ? "is-current" : ""
          }
          aria-current={i === current ? "step" : undefined}
        >
          <span className="svc-stepper-dot" aria-hidden>
            {i < current ? "✓" : i + 1}
          </span>
          <span>{s}</span>
        </li>
      ))}
    </ol>
  )
}
export function Logo() {
  return (
    <div className="brand">
      <span className="logo-mark">
        <House size={25} />
        <span />
      </span>
      <div>
        HomeStamp<small>홈스탬프 · 함께 확인한 우리 집 기록</small>
      </div>
    </div>
  )
}
export function Panel({
  title,
  children,
  action,
  className = "",
}: {
  title: ReactNode
  children: ReactNode
  action?: ReactNode
  className?: string
}) {
  return (
    <Card className={`panel ${className}`}>
      <CardHeader className="panel-head">
        <CardTitle>{title}</CardTitle>
        {action}
      </CardHeader>
      <CardContent>{children}</CardContent>
    </Card>
  )
}
export function Art({
  art = "wall",
  src,
  large = false,
}: {
  art?: string
  src?: string
  large?: boolean
}) {
  return src ? (
    <img
      className={`record-photo ${large ? "large" : ""}`}
      src={src}
      alt="사용자가 첨부한 상태 기록"
    />
  ) : (
    <div
      className={`photo-art ${art} ${large ? "large" : ""}`}
      role="img"
      aria-label="상태 사진 예시"
    />
  )
}
export function Empty({ text }: { text: string }) {
  return (
    <div className="empty">
      <ClipboardCheck size={30} />
      <p>{text}</p>
    </div>
  )
}
// 경과(행동 기록): 최신순. 같은 사람이 같은 시각에 남긴 연관 행동은 한 카드로 묶어요.
// stripTitle: 수리 건 경과에서는 앞에 붙은 수리 건 제목을 떼고 '행동 · 내용'만 보여 줘요.
// 날짜 머리말(오늘·어제·날짜)과 시각만 따로 보여 줘요. 읽을 수 없는 시각은 원문 그대로.
const dayKey = (ms?: number) =>
  ms === undefined
    ? ""
    : new Date(ms).toLocaleDateString("sv-SE", { timeZone: "Asia/Seoul" })
function dayLabel(key: string) {
  if (!key) return "날짜 정보 없음"
  const today = dayKey(Date.now()),
    yesterday = dayKey(Date.now() - 864e5)
  if (key === today) return "오늘"
  if (key === yesterday) return "어제"
  const [y, m, d] = key.split("-").map(Number)
  return `${y}년 ${m}월 ${d}일`
}
const timeLabel = (ms: number | undefined, raw: string) =>
  ms === undefined
    ? raw
    : new Date(ms).toLocaleTimeString("ko-KR", {
        timeZone: "Asia/Seoul",
        hour: "numeric",
        minute: "2-digit",
      })

// 경과(행동 기록): 시간순(최신이 위)이고 날짜별로 묶어요. 같은 사람이 같은 시각에 남긴 연관 행동은 한 카드로 묶어요.
// stripTitle: 수리 건 경과에서는 앞에 붙은 수리 건 제목을 떼요. 호실 전체 경과에서는 어떤 기록의 일인지 제목을 작은 표로 보여 줘요.
// 기록 문장은 바꾸지 않고 화면에서만 쉬운 말로(부록 A). 원문은 title로 남겨요.
// compact: 홈처럼 좁은 곳에서 카드 없이 세로 선 하나에 점과 글로 잇는 가벼운 모양(내용·건수는 같아요)
export function Timeline({
  events,
  stripTitle,
  compact,
}: {
  events: Event[]
  stripTitle?: string
  compact?: boolean
}) {
  const sorted = events
    .map((e, i) => ({ e, i, t: recordTimeMs(e.at) }))
    .sort((a, b) => (b.t ?? 0) - (a.t ?? 0) || a.i - b.i)
  type Item = {
    id: string
    subject?: string
    action: string
    detail: string
    original: string
  }
  const days: {
    key: string
    groups: {
      key: string
      by: string
      at: string
      time: string
      items: Item[]
    }[]
  }[] = []
  for (const { e, t } of sorted) {
    let body =
      stripTitle && e.text.startsWith(stripTitle + " · ")
        ? e.text.slice(stripTitle.length + 3)
        : e.text
    let subject: string | undefined
    if (!stripTitle && e.target) {
      const [first, ...others] = body.split(" · ")
      if (others.length) {
        subject = first
        body = others.join(" · ")
      }
    }
    const [action, ...rest] = plainText(body).split(" · ")
    const item: Item = {
      id: e.id,
      subject,
      action,
      detail: rest.join(" · "),
      original: e.text,
    }
    const dk = dayKey(t)
    let day = days.at(-1)
    if (!day || day.key !== dk) {
      day = { key: dk, groups: [] }
      days.push(day)
    }
    const last = day.groups.at(-1)
    if (last && last.by === e.by && last.at === e.at) last.items.push(item)
    else
      day.groups.push({
        key: e.id,
        by: e.by,
        at: e.at,
        time: timeLabel(t, e.at),
        items: [item],
      })
  }
  if (!events.length) return <Empty text="아직 남긴 행동 기록이 없어요." />
  return (
    <div className="timeline history-days">
      {days.map((d) => (
        <section key={d.key || "none"} className="history-day">
          <h4 className="history-day-label">{dayLabel(d.key)}</h4>
          <ol
            className={
              compact
                ? "m-0 ml-1 flex list-none flex-col gap-3.5 border-l border-line p-0"
                : "history-list"
            }
          >
            {d.groups.map((g) => (
              <li
                className={compact ? "relative flex pl-4" : "history-card"}
                key={g.key}
              >
                <span
                  className={
                    compact
                      ? "absolute top-[7px] -left-[5px] size-[9px] rounded-full border-2 border-[#afc6ed] bg-white"
                      : "event-dot"
                  }
                  aria-hidden
                />
                <div className="min-w-0 flex-1">
                  <div className="history-meta">
                    <span className="history-who">
                      {g.by === "서버 자동" ? g.by : sideText(g.by)}
                    </span>
                    <time title={g.at}>{g.time}</time>
                  </div>
                  {g.items.map((it) => (
                    <div
                      className="history-entry"
                      key={it.id}
                      title={it.original}
                    >
                      {it.subject && (
                        <span className="history-subject">{it.subject}</span>
                      )}
                      <strong>{it.action}</strong>
                      {it.detail && <p>{it.detail}</p>}
                    </div>
                  ))}
                </div>
              </li>
            ))}
          </ol>
        </section>
      ))}
    </div>
  )
}
export function Choice({
  value,
  onChange,
  values,
  label,
}: {
  value: string
  onChange: (v: string) => void
  values: string[]
  label: string
}) {
  return (
    <Select value={value} onValueChange={(v) => v && onChange(v)}>
      <SelectTrigger className="w-full" aria-label={label}>
        <SelectValue>{value}</SelectValue>
      </SelectTrigger>
      <SelectContent>
        {values.map((v) => (
          <SelectItem key={v} value={v}>
            {v}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}
