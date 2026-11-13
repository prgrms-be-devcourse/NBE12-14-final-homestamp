import { useEffect, useRef, useState, type ComponentType } from 'react'
import { MoreHorizontal } from 'lucide-react'
import { cn } from 'cn'
import { Button } from '@/components/ui/button'

export interface MenuItem { key: string; label: string; icon?: ComponentType<{ size?: number; 'aria-hidden'?: boolean }>; onSelect: () => void }

// 작은 '더 보기'(⋯) 메뉴: 기록 카드 안의 보조 행동(고치기 · 사진 추가 · 공유 링크 복사 등)을 모아요.
// 메뉴 바깥을 누르거나(마우스·터치) Esc를 누르면 닫혀요. 항목이 없으면 아무것도 그리지 않아요.
export function MoreMenu({ items, label = '더 보기', className }: { items: MenuItem[]; label?: string; className?: string }) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const away = (e: PointerEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false) }
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('pointerdown', away)
    document.addEventListener('keydown', esc)
    return () => { document.removeEventListener('pointerdown', away); document.removeEventListener('keydown', esc) }
  }, [open])
  if (!items.length) return null
  return <div ref={ref} className={cn('relative', className)}>
    <Button variant="ghost" size="icon" className="size-10 rounded-[10px] text-sub" aria-label={label} aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(v => !v)}><MoreHorizontal /></Button>
    {open && <div role="menu" className="absolute top-full right-0 z-20 mt-1 flex w-52 flex-col overflow-hidden rounded-[12px] border border-line bg-white shadow-[0_8px_24px_#17203314]">
      {items.map(it => <button key={it.key} type="button" role="menuitem" className="flex min-h-11 w-full items-center gap-2.5 border-t border-line px-4 text-left text-[15px] text-ink first:border-t-0 hover:bg-hover focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-brand" onClick={() => { setOpen(false); it.onSelect() }}>{it.icon && <it.icon size={16} aria-hidden />}{it.label}</button>)}
    </div>}
  </div>
}
