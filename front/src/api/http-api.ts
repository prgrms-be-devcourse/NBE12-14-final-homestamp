import type { AddressRegion, HomeStampApi } from './types'
import { endpoints } from './endpoints'
import type { HttpRequest } from './http-client'
import type { Notice, Room } from '@/data/demo'

// 백엔드 연결용 구현. 응답 본문 형식은 명세에 아직 정의되지 않아, 연결 때 아래 변환 함수에서 화면 모델로 맞춥니다.
const toRooms = (body: unknown) => body as Room[]
const toNotices = (body: unknown) => body as Notice[]
// 서버 응답에서 시·도·시·군·구·읍·면·동과 보여 줄 이름만 꺼내요(그 밖의 칸은 버려요)
const toAddresses = (body: unknown): AddressRegion[] => (Array.isArray(body) ? body : []).map((x: Partial<AddressRegion>) => ({ sido: String(x.sido ?? ''), sigungu: String(x.sigungu ?? ''), dong: String(x.dong ?? ''), label: String(x.label ?? '') })).filter(x => x.sido && x.sigungu && x.dong)
export function createHttpApi(request: HttpRequest): HomeStampApi {
  return {
    mode: 'http',
    async listRooms() { return toRooms(await request<unknown>('GET', endpoints.rooms)) },
    async listNotifications() { return toNotices(await request<unknown>('GET', endpoints.notifications)) },
    async searchAddress(keyword) { return toAddresses(await request<unknown>('GET', endpoints.addresses(keyword))) },
    async readNotification(_s, id) { await request('POST', endpoints.readNotification(id), { idempotent: false }) },
    // 명세에 '모두 읽음' API가 없어 한 건씩 읽음 처리합니다.
    async readAllNotifications(s) { const list = await this.listNotifications(s); await Promise.all(list.filter(n => !n.read).map(n => this.readNotification(s, n.id))) },
  }
}
