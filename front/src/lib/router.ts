import { useCallback, useEffect, useRef, useState } from 'react'

// 화면 주소(명세 5-1 화면 목록 기준). 호실 안 화면은 /rooms/:roomId 아래에 둡니다.
//   /                         홈(마지막으로 본 호실)
//   /notifications            알림 목록(S-04)
//   /rooms                    내 호실 목록
//   /rooms/:roomId            호실 기록 요약(S-06)
//   /rooms/:roomId/repairs    하자·수리 목록, /rooms/:roomId/repairs/:repairId 수리 건 상세(S-12)
//   /rooms/:roomId/inspection · checkout · connection · money · timeline
// 가입 전 링크 화면(?share= 등)은 기존 주소 형식을 그대로 씁니다.
export type Page = 'home' | 'rooms' | 'inspection' | 'repairs' | 'detail' | 'connection' | 'money' | 'timeline' | 'notifications' | 'checkout'
export interface Route { page: Page; roomId: string; repairId: string; notFound?: boolean }

const ROOM_PAGES: Page[] = ['inspection', 'repairs', 'connection', 'money', 'timeline', 'checkout']
const enc = encodeURIComponent

export function parsePath(path: string, prev: Route): Route {
  const seg = path.split('/').filter(Boolean).map(s => { try { return decodeURIComponent(s) } catch { return s } })
  const ok = (r: Partial<Route>): Route => ({ ...prev, ...r, notFound: false })
  if (!seg.length) return ok({ page: 'home' })
  if (seg[0] === 'notifications' && seg.length === 1) return ok({ page: 'notifications' })
  if (seg[0] === 'rooms') {
    if (seg.length === 1) return ok({ page: 'rooms' })
    const roomId = seg[1]
    if (seg.length === 2) return ok({ page: 'home', roomId })
    if (seg[2] === 'repairs' && seg.length === 4) return ok({ page: 'detail', roomId, repairId: seg[3] })
    if (seg.length === 3 && (ROOM_PAGES as string[]).includes(seg[2])) return ok({ page: seg[2] as Page, roomId })
  }
  return { ...prev, notFound: true }
}

export function toPath(r: Route): string {
  if (r.page === 'rooms') return '/rooms'
  if (r.page === 'notifications') return '/notifications'
  if (!r.roomId) return '/'
  if (r.page === 'home') return `/rooms/${enc(r.roomId)}`
  if (r.page === 'detail') return `/rooms/${enc(r.roomId)}/repairs/${enc(r.repairId)}`
  return `/rooms/${enc(r.roomId)}/${r.page}`
}

// file://로 연 단일 파일 시연본에서는 주소를 읽거나 바꾸지 않고 화면 상태로만 움직입니다.
const canUseHistory = () => typeof window !== 'undefined' && window.location.protocol !== 'file:'

export function useRouter(initial: Route) {
  const [route, setRoute] = useState<Route>(() => canUseHistory() ? parsePath(window.location.pathname, initial) : initial)
  const push = useRef(false)
  useEffect(() => {
    if (!canUseHistory()) return
    const onPop = () => setRoute(prev => parsePath(window.location.pathname, prev))
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [])
  useEffect(() => {
    const shouldPush = push.current
    push.current = false
    if (!canUseHistory() || route.notFound) return
    const path = toPath(route)
    if (path === window.location.pathname) return
    try {
      if (shouldPush) window.history.pushState(null, '', path + window.location.search)
      else window.history.replaceState(null, '', path + window.location.search)
    } catch { /* 주소만 바뀌지 않고 화면은 계속 동작 */ }
  }, [route])
  // pushHistory=false면 뒤로 가기 기록을 남기지 않고 주소만 맞춥니다.
  const navigate = useCallback((update: (r: Route) => Route, pushHistory = true) => {
    if (pushHistory) push.current = true
    setRoute(prev => ({ ...update(prev), notFound: false }))
  }, [])
  return [route, navigate] as const
}
