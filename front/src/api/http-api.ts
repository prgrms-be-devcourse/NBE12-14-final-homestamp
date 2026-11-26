import type { HomeStampApi, Region, RegionOption } from "./types"
import { endpoints } from "./endpoints"
import { createHttpRepairCases } from "./http-repair-cases"
import { createHttpRecords } from "./http-records"
import { httpAvailableActions } from "./http-available-actions"
import type { HttpRequest } from "./http-client"
import { listAllRooms, roomFromSummary } from "./http-rooms"
import type { Notice } from "@/data/demo"

// 백엔드 연결용 구현. 응답 본문 형식이 아직 정의되지 않은 곳은 연결 때 아래 변환 함수에서 화면 모델로 맞춥니다.
const toNotices = (body: unknown) => body as Notice[]
const toRegions = (body: unknown) => body as Region[]
const toRegionOptions = (body: unknown) => body as RegionOption[]
export function createHttpApi(request: HttpRequest): HomeStampApi {
  return {
    mode: "http",
    repairCases: createHttpRepairCases(request),
    records: createHttpRecords(request),
    actions: httpAvailableActions,
    async listRooms(s) {
      return (await listAllRooms(request)).map((b) =>
        roomFromSummary(b, s.accountId),
      )
    },
    async listNotifications() {
      return toNotices(await request<unknown>("GET", endpoints.notifications))
    },
    async searchRegions(q) {
      return toRegions(await request<unknown>("GET", endpoints.regionSearch(q)))
    },
    async listRegions(parent) {
      return toRegionOptions(
        await request<unknown>("GET", endpoints.regions(parent)),
      )
    },
    async readNotification(_s, id) {
      await request("POST", endpoints.readNotification(id), {
        idempotent: false,
      })
    },
    // 명세에 '모두 읽음' API가 없어 한 건씩 읽음 처리합니다.
    async readAllNotifications(s) {
      const list = await this.listNotifications(s)
      await Promise.all(
        list.filter((n) => !n.read).map((n) => this.readNotification(s, n.id)),
      )
    },
  }
}
