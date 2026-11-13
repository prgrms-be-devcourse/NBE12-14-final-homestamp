import { useState, type FormEvent, type ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import type { Repair } from '@/data/demo'
import { hasResidentId, RESIDENT_ID_MESSAGE } from '@/lib/service-rules'

// 상태를 바꾸는 행동은 먼저 복사본에 적용해 규칙 위반(오류)을 확인한 뒤 실제로 기록합니다.
export function tryOn(r: Repair, update: (draft: Repair) => void): string {
  try { update(structuredClone(r)); return '' } catch (e) { return e instanceof Error ? e.message : '처리할 수 없어요.' }
}

interface Props { open: boolean; title: string; description?: string; submitLabel?: string; onClose: () => void; onSubmit: (form: FormData) => string | void; children: ReactNode }
export function ActionDialog({ open, title, description, submitLabel = '기록 남기기', onClose, onSubmit, children }: Props) {
  const [error, setError] = useState('')
  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const form = new FormData(e.currentTarget)
    if (Array.from(form.values()).some(v => typeof v === 'string' && hasResidentId(v))) { setError(RESIDENT_ID_MESSAGE); return }
    const result = onSubmit(form)
    if (result) setError(result); else { setError(''); onClose() }
  }
  return <Dialog open={open} onOpenChange={v => { if (!v) { setError(''); onClose() } }}><DialogContent className="app-dialog"><DialogHeader><DialogTitle>{title}</DialogTitle><DialogDescription>{description || '사실을 기록하고, 상대의 응답을 기다려요. 원래 기록은 그대로 남습니다.'}</DialogDescription></DialogHeader>
    <form className="modal-form" onSubmit={submit}>{children}{error && <p role="alert" className="form-error">{error}</p>}<div className="modal-footer"><Button type="button" variant="outline" onClick={() => { setError(''); onClose() }}>취소</Button><Button type="submit">{submitLabel}</Button></div></form>
  </DialogContent></Dialog>
}
