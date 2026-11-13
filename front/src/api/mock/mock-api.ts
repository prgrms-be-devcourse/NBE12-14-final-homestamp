import type { HomeStampApi, Session } from '../types'
import type { MockStore } from './store'
import type { Notice } from '@/data/demo'
import { canAccessRoom, sideOf } from '@/lib/workflows'

// Mock API: 실제 서버처럼 비동기로 응답합니다. 테스트에서는 지연 없이 바로 응답해요.
const delay = () => new Promise<void>(r => setTimeout(r, import.meta.env.MODE === 'test' ? 0 : 120))
export function createMockApi(store: MockStore): HomeStampApi {
  // 내 알림: 내가 연결된 호실에서, 그 호실의 내 측으로 온 알림만
  const mine = ({ accountId }: Session) => (n: Notice) => { const r = store.getState().rooms.find(x => x.id === n.roomId); const side = sideOf(r, accountId); return Boolean(r && side && side === n.to && canAccessRoom(r, side)) }
  return {
    mode: 'mock',
    async listRooms(s) { await delay(); return store.getState().rooms.filter(r => { const side = sideOf(r, s.accountId); return side && canAccessRoom(r, side) }) },
    async listNotifications(s) { await delay(); return store.getState().notices.filter(mine(s)) },
    async readNotification(s, id) { await delay(); const ok = mine(s); store.setState(d => ({ ...d, notices: d.notices.map(n => n.id === id && ok(n) ? { ...n, read: true } : n) })) },
    // 가짜 주소 결과는 만들지 않아요. 서버(승인키) 없이 쓸 수 없다고 알려요.
    async searchAddress() { await delay(); throw new Error('주소 검색은 서버가 연결된 뒤에 쓸 수 있어요.') },
    async readAllNotifications(s) { await delay(); const ok = mine(s); store.setState(d => ({ ...d, notices: d.notices.map(n => ok(n) ? { ...n, read: true } : n) })) },
  }
}