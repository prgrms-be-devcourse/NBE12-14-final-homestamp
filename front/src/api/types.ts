import type { Notice, Room } from "@/data/demo"
import type { RepairCasesApi } from "./repair-cases"
import type { RecordsApi } from "./records"
import type { AvailableActionsApi } from "./available-actions"
import type { DownloadsApi } from "./downloads"

// 화면이 쓰는 API 모양. Mock과 HTTP가 같은 모양을 구현합니다.
// 명세에 응답 본문 형식이 아직 정해지지 않은 곳은 화면 모델(data/demo의 타입)을 그대로 쓰고, 백엔드 연결 때 변환 함수만 바꿉니다.
// 로그인한 계정. 호실마다 어느 측인지는 호실 연결(room.members)로 정해져요.
export interface Session {
  accountId: string
}
// 법정동 한 줄(06 4-3 GET /api/regions/search). 호실 주소는 법정동 코드와 별칭만 저장해요(decisions 2026-11-13).
// 세종특별자치시처럼 시·군·구가 없는 곳은 sigungu가 null이에요.
export interface Region {
  code: string
  sido: string
  sigungu: string | null
  dong: string
}
// 단계 선택 한 칸(06 4-3 GET /api/regions?parent=). 세 단계 모두 코드와 이름이에요.
// 시·군·구가 없는 세종은 parent=36에 이름이 null인 칸(36110) 하나가 와요. 화면은 그 칸을 저절로 고르고 단계를 건너뛰어요.
export interface RegionOption {
  code: string
  name: string | null
}
export interface HomeStampApi {
  mode: "mock" | "http"
  // GET /api/rooms
  listRooms(session: Session): Promise<Room[]>
  // GET /api/me/notifications(응답 필요 먼저)
  listNotifications(session: Session): Promise<Notice[]>
  // POST /api/me/notifications/{id}/read — 읽음은 응답이 아니라 기다림 타이머는 그대로
  readNotification(session: Session, id: string): Promise<void>
  readAllNotifications(session: Session): Promise<void>
  // GET /api/regions/search?q= — 동 이름으로 법정동 찾기
  searchRegions(q: string): Promise<Region[]>
  // GET /api/regions?parent= — 없으면 시·도, 2자리면 시·군·구, 5자리면 읍·면·동
  listRegions(parent?: string): Promise<RegionOption[]>
  // 수리 건(06 4장 /api/repair-cases). 도메인마다 이렇게 하나씩 더해 가요.
  repairCases: RepairCasesApi
  // 호실 기록 읽기와, 수리 건 API 밖의 기록 바꾸기(지불·고치기·점검·정산·호실 …)
  records: RecordsApi
  // 수리 건 밖 기록의 availableActions(호실·점검·정산·지불·고친 내용)
  actions: AvailableActionsApi
  // 호실 기록 내려받기(S-19, DL-1): 목록과 원본 사진 주소
  downloads: DownloadsApi
}
