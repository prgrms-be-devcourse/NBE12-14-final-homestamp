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
import { daysSince } from "@/lib/activity"
import { sideName, withSubject } from "@/lib/words"

// 수리 상태 한 줄 요약과 다음 할 일
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
