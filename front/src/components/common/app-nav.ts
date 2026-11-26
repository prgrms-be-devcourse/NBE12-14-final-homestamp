import {
  Building2,
  ClipboardCheck,
  FileText,
  History,
  LayoutDashboard,
  Link2,
  Wallet,
  Wrench,
} from "lucide-react"
import type { Page } from "@/lib/router"

// 주 메뉴(데스크톱 옆 메뉴와 모바일 메뉴가 함께 써요)
export const nav = [
  { id: "home", label: "홈", icon: LayoutDashboard },
  { id: "rooms", label: "호실 기록", icon: Building2 },
  { id: "inspection", label: "입주 점검", icon: ClipboardCheck },
  { id: "repairs", label: "하자 · 수리 기록", icon: Wrench },
  { id: "money", label: "수리비 · 부담 합의", icon: Wallet },
  { id: "checkout", label: "퇴거 · 정산", icon: FileText },
  { id: "connection", label: "초대 및 연결", icon: Link2 },
  { id: "timeline", label: "행동 기록", icon: History },
] as const
// 화면 머리말: 각 화면이 무엇을 하는 곳인지 한 줄로 알려 줘요.
export const pageIntro: Partial<Record<Page, string>> = {
  rooms: "거주 기간마다 호실 기록이 하나씩 생겨요. 지금 볼 호실을 골라 주세요.",
  inspection:
    "입주 당시 상태를 부위별 사진으로 남기고, 상대 확인을 받아 기준 기록으로 잠가요.",
  repairs:
    "하자를 사진과 함께 남기고, 확인 · 수리 · 종료까지의 경과를 한곳에서 봐요.",
  money: "수리 진행과 별개로, 누가 얼마를 부담할지 제안 · 동의로 정해요.",
  checkout:
    "입주 기록과 지금을 같은 부위끼리 비교하고, 합의한 항목으로 보증금을 정산해요.",
  connection:
    "상대를 초대해 같은 기록을 함께 봐요. 연결은 번호 확인과 수락을 거쳐야 해요.",
  timeline: "이 호실에서 있었던 일을 시간순으로 모았어요.",
  notifications: "응답이 필요한 일과 새 소식을 모았어요.",
}
