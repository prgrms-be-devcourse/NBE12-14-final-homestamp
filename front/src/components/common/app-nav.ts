import {
  Building2,
  ClipboardCheck,
  LayoutDashboard,
  Wrench,
} from "lucide-react"
import type { Page } from "@/lib/router"

// 주 메뉴 4개(PC 좌측 사이드바와 모바일 하단 탭바가 함께 써요)
export const nav = [
  { id: "home", label: "홈", shortLabel: "홈", icon: LayoutDashboard },
  {
    id: "repairs",
    label: "하자 · 수리",
    shortLabel: "하자·수리",
    icon: Wrench,
  },
  {
    id: "inspection",
    label: "점검 · 정산",
    shortLabel: "점검·정산",
    icon: ClipboardCheck,
  },
  {
    id: "rooms",
    label: "호실 · 연결",
    shortLabel: "호실·연결",
    icon: Building2,
  },
] as const

export type NavTabId = (typeof nav)[number]["id"]

// 어떤 Page에 있든 4개 핵심 탭 중 하나에 매핑해요(URL 호환성 유지)
export function activeTabOf(page: Page): NavTabId {
  if (page === "repairs" || page === "money" || page === "detail")
    return "repairs"
  if (page === "inspection" || page === "checkout") return "inspection"
  if (page === "rooms" || page === "connection" || page === "timeline")
    return "rooms"
  return "home"
}

// 호실을 바꾼 뒤 갈 화면. 수리 건 상세는 그 호실의 건이라 새 호실의 수리 목록으로 가고(같은 건 ID로 남으면 '찾을 수 없음'),
// 나머지는 새 호실의 같은 화면으로 가요(#229 리뷰)
export function pageAfterRoomChange(page: Page): Page {
  return page === "detail" ? "repairs" : page
}

// 4개 탭 안에서 쓰는 보조 탭(기존 URL과 1:1 연결)
export const subTabsByGroup: Partial<
  Record<NavTabId, readonly { id: Page; label: string }[]>
> = {
  repairs: [
    { id: "repairs", label: "수리 진행" },
    { id: "money", label: "수리비 · 부담" },
  ],
  inspection: [
    { id: "inspection", label: "입주 점검" },
    { id: "checkout", label: "퇴거 · 정산" },
  ],
  rooms: [
    { id: "rooms", label: "내 호실" },
    { id: "connection", label: "상대 연결" },
    { id: "timeline", label: "활동 이력" },
  ],
}

export function pageTitleOf(page: Page): string {
  switch (page) {
    case "home":
      return "홈"
    case "repairs":
      return "하자 · 수리"
    case "money":
      return "하자 · 수리"
    case "detail":
      return "하자 상세"
    case "inspection":
      return "점검 · 정산"
    case "checkout":
      return "점검 · 정산"
    case "rooms":
      return "호실 · 연결"
    case "connection":
      return "호실 · 연결"
    case "timeline":
      return "호실 · 연결"
    case "notifications":
      return "알림"
  }
}

// 화면 한 줄 안내(PC에서만 간결히 노출하거나 필요한 곳에만 써요)
export const pageIntro: Partial<Record<Page, string>> = {
  rooms: "거주 기간마다 호실 기록이 하나씩 생겨요.",
  inspection: "입주 상태를 부위별 사진으로 남기고 기준 기록으로 잠가요.",
  repairs: "하자 등록부터 확인 · 수리 · 비용 합의까지 한곳에서 관리해요.",
  money: "수리 진행과 별개로 부담 합의와 지불 기록을 정리해요.",
  checkout: "입주와 퇴거를 비교하고 합의한 항목으로 보증금을 정산해요.",
  connection: "상대를 초대해 같은 기록을 함께 봐요.",
  timeline: "이 호실에 남긴 모든 행동을 시간순으로 확인해요.",
  notifications: "답할 일과 새 소식을 모았어요.",
}
