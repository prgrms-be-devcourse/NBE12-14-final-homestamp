import type { RecordsApi } from "../records"
import { recordAction, type MockStore } from "./store"

// 목업 기록 API: 서버 대신 저장소를 바로 바꿔요(행동 기록·상대 알림 포함).
// 화면이 바로 바뀌도록 기다리지 않고, 규칙 위반은 거절(reject)로 돌려줘요.
export function createMockRecords(store: MockStore): RecordsApi {
  return {
    subscribe: store.subscribe,
    getSnapshot: store.getState,
    load: async () => {},
    loadRoom: async () => {},
    send: async (_s, c) => {
      recordAction(store, c.role, c.text, c.update, c.options)
      return {}
    },
  }
}
