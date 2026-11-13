import type { ReactNode } from 'react'
import { AlertCircle, Inbox, Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'

// 화면 공통 상태: 불러오는 중 / 오류 / 빈 화면 / 없는 주소
export function LoadingState({ label = '불러오는 중이에요' }: { label?: string }) {
  return <div className="state-box" role="status" aria-live="polite"><Loader2 className="animate-spin" aria-hidden /><p>{label}</p></div>
}
export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return <div className="state-box" role="alert"><AlertCircle aria-hidden /><p>{message}</p>{onRetry && <Button variant="outline" onClick={onRetry}>다시 불러오기</Button>}</div>
}
export function EmptyState({ title, description, action }: { title: string; description?: string; action?: ReactNode }) {
  return <div className="state-box"><Inbox aria-hidden /><p className="font-semibold">{title}</p>{description && <p className="muted">{description}</p>}{action}</div>
}
export function NotFoundState({ onHome }: { onHome: () => void }) {
  return <EmptyState title="찾을 수 없는 기록이에요" description="주소가 바뀌었거나 볼 수 있는 기록이 아니에요." action={<Button onClick={onHome}>내 호실로 가기</Button>} />
}
