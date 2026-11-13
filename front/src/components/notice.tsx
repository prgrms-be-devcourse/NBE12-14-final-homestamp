import type { ReactNode } from 'react'
import { Info } from 'lucide-react'
import { cn } from 'cn'
import { Alert, AlertDescription } from '@/components/ui/alert'

// 안내 상자(shadcn Alert). 경고가 아니라 안내라서 role="note"로 둬요(화면 읽기 프로그램이 매번 끼어들지 않게).
// tone: info(파랑, 기본) · amber(확인이 필요한 안내·개발 환경 표시)
export function Notice({ tone = 'info', icon, className, children }: { tone?: 'info' | 'amber'; icon?: ReactNode; className?: string; children: ReactNode }) {
  return <Alert role="note" className={cn('notice', `notice-${tone}`, className)}>
    {icon ?? <Info aria-hidden />}
    <AlertDescription>{children}</AlertDescription>
  </Alert>
}
