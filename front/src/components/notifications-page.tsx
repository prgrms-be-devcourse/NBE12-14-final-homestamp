import { useState } from 'react'
import { Bell, CheckCheck, ChevronRight } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Panel } from '@/components/prototype'
import { EmptyState, ErrorState, LoadingState } from '@/components/states'
import { useApi, useApiQuery } from '@/api'
import { errorMessage } from '@/api/errors'
import type { Notice, Room } from '@/data/demo'

// 알림 목록(S-04, GET /api/me/notifications). 읽음은 응답·동의가 아니에요.
export function NotificationsPage({ accountId, rooms, version, onOpen }: { accountId: string; rooms: Room[]; version: unknown; onOpen: (n: Notice) => void }) {
  const api = useApi()
  const q = useApiQuery(a => a.listNotifications({ accountId }), [accountId, version])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function readAll() {
    setBusy(true); setError('')
    try { await api.readAllNotifications({ accountId }) } catch (e) { setError(errorMessage(e)) } finally { setBusy(false) }
  }
  async function openNotice(n: Notice) {
    try { await api.readNotification({ accountId }, n.id) } catch { /* 읽음 표시가 실패해도 기록은 열어요 */ }
    onOpen(n)
  }
  const list = q.data || []
  return <Panel title="내 알림" action={<Button variant="outline" disabled={busy || !list.some(n => !n.read)} onClick={readAll}><CheckCheck />모두 읽음</Button>}>
    <p className="muted mb-5">읽음 처리는 응답이나 동의가 아닙니다.</p>
    {q.loading && !q.data ? <LoadingState label="알림을 불러오는 중이에요" />
      : q.error ? <ErrorState message={q.error} onRetry={q.reload} />
      : !list.length ? <EmptyState title="새로운 알림이 없어요." description="상대가 확인하거나 답해야 할 일이 생기면 여기에 모여요." />
      : list.map(n => <div className={`notice-row ${n.read ? 'read' : ''}`} key={n.id}><Bell size={20} aria-hidden /><div><strong>{n.title}</strong><small>{rooms.find(r => r.id === n.roomId)?.name} · {n.read ? '읽음' : '새 알림'}</small></div><Button variant="outline" onClick={() => openNotice(n)}>기록 보기<ChevronRight /></Button></div>)}
    {error && <p role="alert" className="form-error">{error}</p>}
  </Panel>
}
