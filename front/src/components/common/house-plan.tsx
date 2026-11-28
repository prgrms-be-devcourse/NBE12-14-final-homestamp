import type { PlanArea } from "@/lib/plan-area"

// 집 평면도: 손볼 곳이 집의 어느 구역인지 점으로 보여 줘요(홈 시안 B2).
// 실제 평면이 아니라 기본 구역(V7 PART_ZONES)을 늘 같은 자리에 놓은 그림이에요.
// 점은 세 가지만: 내가 할 일(주황 채움) · 진행 중(남색 빈 원) · 끝남(흐린 회색). 세부 상태는 아래 수리 카드 글로 보여요.
export interface PlanSpot {
  area: PlanArea
  tone: "turn" | "progress" | "done"
}

const AREAS: {
  key: PlanArea
  label: string
  x: number
  y: number
  w: number
  h: number
}[] = [
  { key: "방", label: "방 · 거실", x: 2, y: 2, w: 196, h: 116 },
  { key: "주방", label: "주방", x: 198, y: 2, w: 130, h: 116 },
  { key: "욕실", label: "욕실", x: 2, y: 118, w: 106, h: 90 },
  { key: "현관", label: "현관", x: 108, y: 118, w: 110, h: 90 },
  { key: "기타", label: "설비 · 기타", x: 218, y: 118, w: 110, h: 90 },
]

const RANK: Record<PlanSpot["tone"], number> = { turn: 3, progress: 2, done: 1 }
const TONE_WORD: Record<PlanSpot["tone"], string> = {
  turn: "내가 할 일",
  progress: "진행 중",
  done: "끝남",
}

export function HousePlan({
  spots,
  title,
}: {
  spots: PlanSpot[]
  title: string
}) {
  // 구역마다 가장 눈에 띄어야 할 상태 하나와, 끝나지 않은 건수
  const byArea = new Map<PlanArea, { tone: PlanSpot["tone"]; open: number }>()
  for (const s of spots) {
    const cur = byArea.get(s.area)
    const open = s.tone === "done" ? 0 : 1
    if (!cur) byArea.set(s.area, { tone: s.tone, open })
    else
      byArea.set(s.area, {
        tone: RANK[s.tone] > RANK[cur.tone] ? s.tone : cur.tone,
        open: cur.open + open,
      })
  }
  const label = `${title} 평면도. ${
    AREAS.filter((a) => byArea.has(a.key))
      .map((a) => {
        const v = byArea.get(a.key)!
        return v.tone === "done"
          ? `${a.label} 끝남`
          : `${a.label} ${v.open}건 ${TONE_WORD[v.tone]}`
      })
      .join(", ") || "기록된 수리 없음"
  }`
  return (
    <figure className="m-0 rounded-[14px] bg-plan p-3.5">
      <svg
        viewBox="0 0 330 210"
        className="block h-auto w-full max-w-[460px]"
        role="img"
        aria-label={label}
      >
        <rect
          x="2"
          y="2"
          width="326"
          height="206"
          fill="var(--color-plan)"
          stroke="var(--color-brand)"
          strokeWidth="4"
        />
        <path
          d="M198 2v116M2 118h326M108 118v90M218 118v90"
          fill="none"
          stroke="var(--color-brand)"
          strokeWidth="3"
        />
        {/* 문틈 */}
        <path
          d="M198 60v24M40 118h28M140 118h28M260 118h28"
          fill="none"
          stroke="var(--color-plan)"
          strokeWidth="5"
        />
        {AREAS.map((a) => {
          const v = byArea.get(a.key)
          const cx = a.x + a.w / 2
          const cy = a.y + a.h / 2 + 8
          return (
            <g key={a.key}>
              <text
                x={a.x + 12}
                y={a.y + 22}
                fontSize="12"
                fontWeight="700"
                fill="var(--color-sub)"
              >
                {a.label}
              </text>
              {v && <Spot cx={cx} cy={cy} tone={v.tone} count={v.open} />}
            </g>
          )
        })}
      </svg>
      <figcaption>
        <ul className="m-0 mt-3 flex list-none flex-wrap gap-x-4 gap-y-2 p-0 text-xs text-ink">
          <li className="flex items-center gap-1.5">
            <span
              aria-hidden
              className="size-3 flex-none rounded-full bg-turn"
            />
            내가 할 일
          </li>
          <li className="flex items-center gap-1.5">
            <span
              aria-hidden
              className="size-2.5 flex-none rounded-full border-2 border-brand"
            />
            진행 중
          </li>
          <li className="flex items-center gap-1.5">
            <span
              aria-hidden
              className="size-2.5 flex-none rounded-full bg-done-dot"
            />
            끝남
          </li>
        </ul>
      </figcaption>
    </figure>
  )
}

function Spot({
  cx,
  cy,
  tone,
  count,
}: {
  cx: number
  cy: number
  tone: PlanSpot["tone"]
  count: number
}) {
  const n = count > 1 ? String(count) : ""
  if (tone === "done")
    return <circle cx={cx} cy={cy} r="9" fill="var(--color-done-dot)" />
  if (tone === "turn")
    return (
      <g>
        <circle
          cx={cx}
          cy={cy}
          r="19"
          fill="var(--color-turn)"
          opacity="0.22"
        />
        <circle cx={cx} cy={cy} r="13" fill="var(--color-turn)" />
        <text
          x={cx}
          y={cy + 4}
          textAnchor="middle"
          fontSize="12"
          fontWeight="800"
          fill="#fff"
        >
          {n}
        </text>
      </g>
    )
  return (
    <g>
      <circle
        cx={cx}
        cy={cy}
        r="12"
        fill="#fff"
        stroke="var(--color-brand)"
        strokeWidth="2.5"
      />
      <text
        x={cx}
        y={cy + 4}
        textAnchor="middle"
        fontSize="11"
        fontWeight="800"
        fill="var(--color-brand)"
      >
        {n}
      </text>
    </g>
  )
}
