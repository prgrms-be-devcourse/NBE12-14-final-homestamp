import { createDemo, emptyState, type DemoState } from '@/data/demo'
import { todayKey } from '@/lib/workflows'

// 임시(개발용) 시작 데이터: 확인 계정 두 개(api/auth의 DEV_ACCOUNTS)가 이미 연결된 호실 하나.
// 김냥냥(test@gmail.com)은 세입자 쪽, 박주인(test1@gmail.com)은 집주인 쪽이에요. 서버 연결 전 확인용으로만 써요.
export function createDevSeed(): DemoState {
  const today = todayKey()
  return {
    ...emptyState(),
    rooms: [{
      id: 'dev-room', name: '101호', address: '서울시 마포구 연남동', since: today, createdAt: today,
      status: '거주 중', connection: '연결됨', received: false, creator: '임차인', phone: '01012341234',
      members: { 임차인: 'dev-tenant', 임대인: 'dev-landlord' },
    }],
  }
}

// 임시(개발용) 예시 기록: 주소에 ?sample을 붙이면 예전 예시 기록(302호 거주 중 · 201호 거주 종료)을 확인용 계정 두 개에 연결해 시작해요.
// 화면 캡처·확인용이에요. 서버 연결 전까지만 써요.
export function createSampleSeed(): DemoState {
  const d = createDemo()
  d.rooms.forEach(r => { r.members = { 임차인: 'dev-tenant', 임대인: 'dev-landlord' }; r.connection = '연결됨' })
  return d
}
export const wantsSample = () => typeof window !== 'undefined' && new URLSearchParams(window.location.search).has('sample')
