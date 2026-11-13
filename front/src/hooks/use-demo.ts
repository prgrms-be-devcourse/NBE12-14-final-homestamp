import { useState, useSyncExternalStore } from 'react'
import type { DemoState, RecordTarget, Role } from '@/data/demo'
import { createMockStore, recordAction, type MockStore } from '@/api/mock/store'

// 화면이 쓰는 기록 저장소 훅. 지금은 Mock 저장소(api/mock/store)를 쓰고, 백엔드 연결 때 API 조회·명령으로 바꿉니다.
export function useDemo(role: Role, roomId: string, shared?: MockStore) {
  const [own] = useState(() => shared ?? createMockStore())
  const store = shared ?? own
  const db = useSyncExternalStore(store.subscribe, store.getState)
  function act(text: string, update: (draft: DemoState) => void, notify = true, eventRoomId = roomId, connecting = false, privateTo?: Role, target?: RecordTarget) {
    recordAction(store, role, text, update, { notify, roomId: eventRoomId, connecting, privateTo, target })
  }
  const setDb = (next: DemoState | ((d: DemoState) => DemoState)) => store.setState(next)
  return { db, act, setDb, store }
}
