import { createDemo, other, uid, type DemoState, type RecordTarget, type Role } from '@/data/demo'
import { canAccessRoom } from '@/lib/workflows'

// Mock 저장소: API가 연결되기 전까지 브라우저 메모리에 기록을 둡니다(새로고침하면 초기화).
// 화면 하나(App 마운트)마다 저장소 하나라서, 테스트끼리 상태가 섞이지 않아요.
export type Listener = () => void
export interface MockStore {
  getState: () => DemoState
  setState: (next: DemoState | ((d: DemoState) => DemoState)) => void
  subscribe: (l: Listener) => () => void
}
export function createMockStore(seed: () => DemoState = createDemo): MockStore {
  let state = seed()
  const listeners = new Set<Listener>()
  return {
    getState: () => state,
    setState: next => { state = typeof next === 'function' ? next(state) : next; listeners.forEach(l => l()) },
    subscribe: l => { listeners.add(l); return () => { listeners.delete(l) } },
  }
}

// 행동 기록 남기기(서버의 '행동만 쌓는다'에 해당): 상태 변경 + 행동 기록 + 상대 알림을 한 번에.
export interface ActionOptions { notify?: boolean; roomId: string; connecting?: boolean; privateTo?: Role; target?: RecordTarget }
export function recordAction(store: MockStore, role: Role, text: string, update: (draft: DemoState) => void, o: ActionOptions) {
  store.setState(current => {
    const room = current.rooms.find(r => r.id === o.roomId)
    if (room && !canAccessRoom(room, role) && !o.connecting) return current
    const draft = structuredClone(current)
    update(draft)
    draft.events.unshift({ id: uid(), roomId: o.roomId, text, by: role, privateTo: o.privateTo, target: o.target, at: new Date().toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }) })
    if (o.notify !== false && draft.rooms.find(r => r.id === o.roomId)?.connection !== '연결 전') draft.notices.unshift({ id: uid(), roomId: o.roomId, title: text, to: other(role), read: false })
    return draft
  })
}
