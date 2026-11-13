import { useState, type FormEvent, type ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog'
import { MoneyInput } from '@/components/money-input'
import { Panel, Status, Choice } from '@/components/prototype'
import { CountChip } from '@/components/fold'
import { MoreMenu } from '@/components/more-menu'
import { cn } from 'cn'
import { parseMoney } from '@/lib/money'
import { newAmountOffer, respondToOffer, roomTransfers, transferTotal } from '@/lib/workflows'
import { hasResidentId, RESIDENT_ID_MESSAGE, settlementTotal } from '@/lib/service-rules'
import { uid, won, other, type Room, type Role, type Settlement, type DemoState, type AmountOffer } from '@/data/demo'
import { sideText } from '@/lib/words'

interface Props { room: Room; role: Role; items: Settlement[]; onAction: (text: string, update: (draft: DemoState) => void) => void; onAdd: () => void; candidates?: ReactNode }
type Mode = 'deposit' | 'deposit-dispute' | 'item' | 'item-dispute' | 'item-counter' | 'exclude' | 'exclude-reject' | 'transfer' | null
function offerHistory(offers: AmountOffer[]) {
  return offers.map(o => <div className="note" key={o.id}><strong>이전 제안 {o.round}회 · {o.by} · {won(o.amount)} · {o.status}</strong><p>{o.reason}</p>{o.disputeReason && <p>{o.disputeBy} 이견: {o.disputeReason}</p>}</div>)
}
export function RoomFinancials({ room, role, items: allItems, onAction, onAdd, candidates }: Props) {
  // 빼기 제안에 상대가 동의한 항목은 정산 계산과 세 칸에서 빠지고, 아래 '뺀 항목'에 보존됩니다.
  const items = allItems.filter(s => !s.excluded && !s.withdrawn)
  const excludedItems = allItems.filter(s => s.excluded)
  const withdrawnItems = allItems.filter(s => s.withdrawn)
  const [mode, setMode] = useState<Mode>(null)
  const [itemId, setItemId] = useState('')
  const [direction, setDirection] = useState('차감')
  const [transferDirection, setTransferDirection] = useState('반환')
  const [error, setError] = useState('')
  const readOnly = room.status === '정산 완료'
  const offer = room.depositOffer
  const item = allItems.find(s => s.id === itemId)
  const total = settlementTotal(room.deposit ?? 0, items)
  const paid = transferTotal(room)
  const transfers = roomTransfers(room)
  const myConfirmed = room.depositConfirmed || offer?.by === role || offer?.status === '합의됨'
  const otherConfirmed = room.depositConfirmed || offer?.by === other(role) || offer?.status === '합의됨'
  function open(next: Mode, s?: Settlement) { setMode(next); setError(''); setItemId(s?.id || ''); setDirection(s?.direction || '차감'); setTransferDirection(total < 0 ? '추가 송금' : '반환') }
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const f = new FormData(event.currentTarget)
    const reason = String(f.get('reason') || '').trim()
    if (hasResidentId(reason)) { setError(RESIDENT_ID_MESSAGE); return }
    const amount = parseMoney(String(f.get('amount') || ''))
    if (['deposit', 'item', 'item-counter', 'transfer'].includes(mode || '') && !Number.isFinite(amount)) { setError('0 이상의 원화 정수를 입력해 주세요.'); return }
    if (mode !== 'transfer' && !reason) { setError('제안 또는 이견 사유를 입력해 주세요.'); return }
    if (mode === 'deposit') onAction(`보증금 금액 제안 · ${won(amount)} · ${reason}`, d => {
      const r = d.rooms.find(r => r.id === room.id)!
      if (!r.depositOffer && r.depositConfirmed) r.depositOffer = { ...newAmountOffer(role, r.deposit ?? 0, '기존 양측 확인 기준'), status: '합의됨' }
      if (r.depositOffer) r.depositHistory = [...r.depositHistory || [], structuredClone(r.depositOffer)]
      r.depositOffer = newAmountOffer(role, amount, reason, r.depositOffer)
      r.depositConfirmed = false
      if (!r.depositHistory?.some(o => o.status === '합의됨')) r.deposit = amount
    })
    else if (mode === 'deposit-dispute') {
      if (!offer || offer.by === role || offer.status !== '합의 대기') return
      onAction(`보증금 금액 이견 · ${reason}`, d => { const r = d.rooms.find(r => r.id === room.id)!; respondToOffer(r.depositOffer!, role, false, reason); r.depositConfirmed = false })
    } else if (mode === 'item') onAction(`${item!.title} · 정산 다른 안 제안 ${won(amount)} · ${reason}`, d => {
      const s = d.settlements.find(s => s.id === itemId)!
      const old = structuredClone(s); delete old.history
      s.history = [...s.history || [], old]
      s.amount = amount; s.direction = direction as Settlement['direction']; s.by = role; s.status = '합의 대기'; s.proposalReason = reason; s.round = (s.round || 1) + 1; s.disputeReason = undefined; s.disputeBy = undefined; s.autoChange = undefined
    })
    // 이견 + 역제안(1-2 제안 기계): 이전 안은 '이견'으로 이력에 남고, 역제안이 새 현재 제안이 돼요.
    else if (mode === 'item-counter') {
      if (!item || item.by === role || item.status !== '합의 대기') return
      onAction(`${item.title} · 정산 이견 + 다른 안 제안 ${won(amount)} · ${reason}`, d => {
        const s = d.settlements.find(s => s.id === itemId)!
        const old = structuredClone(s); delete old.history; old.status = '이견'; old.disputeReason = reason; old.disputeBy = role
        s.history = [...s.history || [], old]
        s.amount = amount; s.direction = direction as Settlement['direction']; s.by = role; s.status = '합의 대기'; s.proposalReason = reason; s.round = (s.round || 1) + 1; s.disputeReason = undefined; s.disputeBy = undefined; s.autoChange = undefined
      })
    }
    // 빼기 제안(기획서 305): 넣는 것도 빼는 것도 제안이라 상대 확인이 필요해요.
    else if (mode === 'exclude') {
      if (!item || item.excludeProposal) return
      onAction(`${item.title} · 정산에서 빼기 제안 · ${reason}`, d => { d.settlements.find(s => s.id === itemId)!.excludeProposal = { by: role, reason, at: new Date().toLocaleString('ko-KR') } })
    }
    else if (mode === 'exclude-reject') {
      if (!item?.excludeProposal || item.excludeProposal.by === role) return
      onAction(`${item.title} · 빼기 제안 거절 · ${reason}`, d => { const s = d.settlements.find(s => s.id === itemId)!; s.excludeHistory = [...s.excludeHistory || [], { ...s.excludeProposal!, result: `거절 · ${role}: ${reason}` }]; s.excludeProposal = undefined })
    }
    else if (mode === 'item-dispute') {
      if (!item || item.by === role || item.status !== '합의 대기') return
      onAction(`${item.title} · 정산 이견 · ${reason}`, d => respondToOffer(d.settlements.find(s => s.id === itemId)!, role, false, reason))
    } else if (mode === 'transfer') onAction(`${transferDirection} 사실 기록 · ${won(amount)} · 한쪽 기록`, d => {
      const r = d.rooms.find(r => r.id === room.id)!
      r.transfers = [...roomTransfers(r), { id: uid(), by: role, amount, direction: transferDirection as '반환' | '추가 송금', confirmed: false, at: new Date().toLocaleString('ko-KR') }]
    })
    setMode(null)
  }
  // 정해지지 않은 금액(문서 10장): 합의되지 않은 항목의 금액을 따로 보여 줘요. 계산(total)에는 넣지 않아요.
  const unsettled = items.filter(s => s.status !== '합의됨')
  const signed = (s: Settlement) => s.autoChange?.pending ? '금액 미정' : `${s.direction === '가산' ? '+' : '−'}${won(s.amount)}`
  // 정산 항목 카드의 행동(노출 조건·순서 그대로). 크게 보이는 버튼은 앞의 두 개, 나머지는 카드 ⋯ 메뉴(화면 원칙 1)
  const itemActs = (s: Settlement) => {
    const acts: { key: string; label: string; variant: 'default' | 'outline' | 'ghost'; onClick: () => void }[] = []
    if (s.by !== role && s.status === '합의 대기') {
      if (!s.autoChange?.pending) acts.push({ key: 'agree', label: '동의', variant: 'default', onClick: () => onAction(`${s.title} · 정산 금액 동의`, d => respondToOffer(d.settlements.find(v => v.id === s.id)!, role, true)) })
      acts.push({ key: 'dispute', label: '이견', variant: 'outline', onClick: () => open('item-dispute', s) })
      acts.push({ key: 'counter', label: '다른 안 제안', variant: 'outline', onClick: () => open('item-counter', s) })
    }
    if (s.by === role && s.status === '합의 대기' && !s.autoChange) acts.push({ key: 'withdraw', label: '제안 철회', variant: 'ghost', onClick: () => onAction(`${s.title} · 정산 제안 철회`, d => {
      // 제안 철회(1-2): 제안한 쪽, 상대 응답 전 → 직전 상태. 철회한 제안은 이력에 남깁니다.
      const idx = d.settlements.findIndex(v => v.id === s.id); const cur = d.settlements[idx]
      const snap = structuredClone(cur); delete snap.history; snap.withdrawn = true
      const prev = cur.history?.at(-1)
      if (!prev) { cur.withdrawn = true; cur.history = [...cur.history || [], snap]; return }
      const restored: Settlement = { ...structuredClone(prev), id: cur.id, history: [...cur.history!.slice(0, -1), snap] }
      if (restored.status === '이견' && restored.disputeBy === role) { restored.status = '합의 대기'; restored.disputeReason = undefined; restored.disputeBy = undefined }
      d.settlements[idx] = restored
    }) })
    if (!s.excludeProposal) acts.push({ key: 'exclude', label: '빼기 제안', variant: 'ghost', onClick: () => open('exclude', s) })
    if (s.excludeProposal && s.excludeProposal.by !== role) {
      acts.push({ key: 'exclude-agree', label: '빼기 동의', variant: 'outline', onClick: () => onAction(`${s.title} · 빼기 제안 동의 · 정산에서 뺌`, d => { const v = d.settlements.find(x => x.id === s.id)!; v.excludeHistory = [...v.excludeHistory || [], { ...v.excludeProposal!, result: `동의 · ${role}` }]; v.excludeProposal = undefined; v.excluded = true }) })
      acts.push({ key: 'exclude-reject', label: '빼기 거절', variant: 'outline', onClick: () => open('exclude-reject', s) })
    }
    if (s.excludeProposal && s.excludeProposal.by === role) acts.push({ key: 'exclude-withdraw', label: '빼기 제안 철회', variant: 'ghost', onClick: () => onAction(`${s.title} · 빼기 제안 철회`, d => { const v = d.settlements.find(x => x.id === s.id)!; v.excludeHistory = [...v.excludeHistory || [], { ...v.excludeProposal!, result: '철회' }]; v.excludeProposal = undefined }) })
    if (s.status !== '합의 대기') acts.push({ key: 'new', label: '새 금액 제안', variant: 'outline', onClick: () => open('item', s) })
    return acts
  }
  const amount = 'text-[30px] leading-[1.4] font-extrabold tracking-[-1px] [overflow-wrap:anywhere] max-md:text-[26px]'
  const subHead = 'mt-[22px] mb-2.5 text-lg font-bold'
  const COLS = ['합의됨', '확인 대기', '이견'] as const
  const colOf = (s: Settlement) => s.status === '합의 대기' ? '확인 대기' : s.status
  return <>
    {/* 정산 · 보증금(둘 다 확인 상태) — 문서 10장 순서: 보증금 → 세 칸 → 계산 금액 → 정해지지 않은 금액 → 송금 → 정산 끝내기 */}
    <div className="my-[22px]"><Panel title="보증금 기준 금액">
      <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
        <div className="min-w-0"><div className={cn(amount, 'text-ink')}>{room.deposit != null ? won(room.deposit) : '미입력'}</div>{room.deposit == null && <p className="muted">호실을 만들 때 보증금을 입력하지 않았어요. 보증금 확인·제안으로 금액을 남길 수 있어요.</p>}</div>
        <div className="flex flex-wrap items-center gap-1.5"><Status>내 확인 · {myConfirmed ? '확인' : '미확인'}</Status><Status>상대방 확인 · {otherConfirmed ? '확인' : '미확인'}</Status><Status>{room.depositConfirmed || offer?.status === '합의됨' ? '양측 확인' : offer?.status === '이견' ? '이견' : myConfirmed || otherConfirmed ? '상대 확인 대기' : '보증금 확인 전'}</Status></div>
      </div>
      {offer && <div className="proposal"><small>현재 제안 {offer.round}회 · {sideText(offer.by)}</small><strong>{won(offer.amount)}</strong><p>{offer.reason}</p>{offer.disputeReason && <div className="dispute-callout">{offer.disputeBy} 이견: {offer.disputeReason}</div>}</div>}
      {offerHistory(room.depositHistory || [])}
      {!readOnly && <div className="actions mt-4">
        {offer?.status === '합의 대기' && offer.by !== role && <><Button onClick={() => onAction('보증금 같은 금액 동의 · 양측 확인', d => { const r = d.rooms.find(r => r.id === room.id)!; respondToOffer(r.depositOffer!, role, true); r.deposit = r.depositOffer!.amount; r.depositConfirmed = true })}>보증금 금액 동의</Button><Button variant="outline" onClick={() => open('deposit-dispute')}>보증금 이견 남기기</Button></>}
        <Button variant="outline" onClick={() => open('deposit')}>{offer ? '보증금 새 금액 제안' : '보증금 확인·제안'}</Button>
      </div>}
    </Panel></div>
    {candidates}
    <Panel title="정산 항목" action={!readOnly && <Button variant="outline" onClick={onAdd}>항목 추가</Button>}>
      {/* 세 칸: 합의됨 | 확인 대기 | 이견. 좁은 화면은 위아래로, 빈 칸은 한 줄로 */}
      <div className="grid grid-cols-3 items-start gap-3 max-[1000px]:grid-cols-1">{COLS.map(status => { const list = items.filter(s => colOf(s) === status); return <div className="min-w-0 rounded-[14px] border border-[#edf1f7] bg-[#f7f9fc] p-3" key={status}>
        <h3 className="mb-2 flex items-center justify-between gap-2"><Status>{status}</Status><CountChip>{list.length}</CountChip></h3>
        {!list.length && <p className="muted px-1 py-2">해당 항목이 없어요.</p>}
        <div className="flex flex-col gap-2.5">{list.map(s => { const acts = readOnly ? [] : itemActs(s); const big = acts.slice(0, 2); const rest = acts.slice(2); return <div className="relative flex min-w-0 flex-col gap-2 rounded-[12px] border border-line bg-white p-4" key={s.id}>
          <strong className={cn('text-base leading-[1.5] text-ink', rest.length && 'pr-10')}>{s.title}</strong>
          <b className="text-[19px] leading-[1.4] font-extrabold tracking-[-.3px] text-ink tabular-nums">{signed(s)}</b>
          <small className="text-sm leading-[1.7] text-[#91a3bc]">현재 제안 {s.round || 1}회 · {sideText(s.by)} · {s.reason}</small>{s.proposalReason && <p className="text-sm">{s.proposalReason}</p>}{s.disputeReason && <div className="dispute-callout">{s.disputeBy} 이견: {s.disputeReason}</div>}{s.repairId && <small className="block muted">출처: 수리 건</small>}{s.autoChange && <div className="note">{s.autoChange.pending ? <>(자동) 수리 건 변경으로 다시 확인 대기 · 현재 {s.autoChange.moneyStatus} · {s.autoChange.fromStatus === '합의됨' ? '이전 합의' : '이전 제안'} {s.direction === '가산' ? '+' : '−'}{won(s.autoChange.from)} · {s.autoChange.at}<p className="muted">수리 건 부담이 정해지면 새 금액으로 바뀌어요. 그때까지 금액이 정해지지 않아 동의할 수 없고 정산 계산에도 넣지 않아요.</p></> : <>(자동) 수리 건 변경으로 다시 확인 대기 · {s.autoChange.fromStatus === '합의됨' ? '이전 합의 ' : ''}{won(s.autoChange.from)} → {won(s.autoChange.to ?? s.amount)} · {s.autoChange.at}</>}<p className="muted">수리 건을 바꾼 쪽: {s.autoChange.changedBy} · 정산 제안자는 그대로 {sideText(s.by)}이에요.</p></div>}{s.zone && <small className="block muted">원상복구 부위 · {s.zone}</small>}{s.excludeProposal && <div className="dispute-callout">{sideText(s.excludeProposal.by)} 빼기 제안 · {s.excludeProposal.reason}</div>}{s.excludeHistory?.map((h, n) => <p className="muted" key={'x' + n}>빼기 제안({h.by}) · {h.reason} · {h.result}</p>)}{s.history?.map((h, n) => <div className="note" key={n}>이전 제안 {h.round || 1}회 · {h.by} · {won(h.amount)} · {h.withdrawn ? '철회' : h.status}<p>{h.proposalReason || h.reason}</p>{h.disputeReason && <p>이견: {h.disputeReason}</p>}</div>)}
          {!readOnly && (big.length > 0 || (s.by === role && s.status === '합의 대기')) && <div className="actions">
            {big.map(a => <Button key={a.key} variant={a.variant} onClick={a.onClick}>{a.label}</Button>)}
            {s.by === role && s.status === '합의 대기' && <small className="muted">상대가 같은 안에 동의해야 합의돼요.</small>}
          </div>}
          <MoreMenu className="absolute top-2 right-2" items={rest.map(a => ({ key: a.key, label: a.label, onSelect: a.onClick }))} />
        </div> })}</div>
      </div> })}</div>
      {withdrawnItems.length > 0 && <div className="mt-4"><h3 className={subHead}>철회된 제안</h3>{withdrawnItems.map(s => <p className="muted" key={s.id}>{s.title} · {s.direction === '가산' ? '+' : '−'}{won(s.amount)} · {sideText(s.by)} 제안 철회</p>)}</div>}
      {excludedItems.length > 0 && <div className="mt-4"><h3 className={subHead}>양측 동의로 정산에서 뺀 항목</h3>{excludedItems.map(s => <p className="muted" key={s.id}>{s.title} · {s.direction === '가산' ? '+' : '−'}{won(s.amount)} · {s.excludeHistory?.at(-1)?.reason}</p>)}</div>}
    </Panel>
    <div className="mt-5"><Panel title="합의된 항목으로 계산한 금액">
      <div className={cn(amount, 'text-primary')}>{won(total)}</div><p className="muted">보증금 − 합의된 차감 + 합의된 가산</p><p className="muted">확인 대기·이견 {unsettled.length}건 제외{!room.depositConfirmed && offer?.status !== '합의됨' ? ' · 보증금 확인 전 기준 계산' : ''}</p>
      {/* 정해지지 않은 금액: 항목마다 금액을 따로(계산에는 넣지 않아요) */}
      {unsettled.length > 0 && <div className="mt-3 rounded-[12px] bg-soft px-4 py-3"><p className="text-sm font-semibold text-sub">정해지지 않은 금액</p><ul className="mt-1.5 flex flex-col gap-1">{unsettled.map(s => <li key={s.id} className="flex items-baseline justify-between gap-3 text-sm"><span className="min-w-0 text-ink [overflow-wrap:anywhere]">{s.title} <span className="text-[13px] text-sub">· <Status>{colOf(s)}</Status></span></span><span className="flex-none font-bold text-ink tabular-nums">{signed(s)}</span></li>)}</ul></div>}
    </Panel></div>
    <Panel title="보증금 반환 · 수령 기록" className="mt-5"><p className="muted">실제 송금이 아닌 과거 사실 기록입니다. 정산 금액이 바뀌어도 기록 금액은 바뀌지 않아요.</p>
      {/* 영수증처럼: 순액 · 차액 */}
      <dl className="my-3 divide-y divide-line rounded-[12px] border border-line">
        <div className="flex items-baseline justify-between gap-3 px-4 py-3"><dt className="text-[15px] text-sub">기존 반환·송금 순액</dt><dd className="text-xl font-extrabold text-ink tabular-nums">{won(paid)}</dd></div>
        <div className="flex items-baseline justify-between gap-3 px-4 py-3" aria-live="polite"><dt className="text-[15px] font-semibold text-ink">차액</dt><dd className="text-2xl font-extrabold text-primary tabular-nums">{won(total - paid)}</dd></div>
      </dl>
      <div className="flex flex-col gap-2">{transfers.map(t => <div className="flex flex-wrap items-center gap-3 rounded-[12px] bg-[#f6f9fd] p-4" key={t.id}><div className="min-w-0 flex-1"><strong className="text-lg font-[750] text-ink">{t.direction} {won(t.amount)}</strong><p className="text-sm leading-[1.7] text-[#8fa3be]">{t.at} · 기록자 {t.by}</p><Status>{t.confirmed ? '양측 확인' : '상대 확인 대기 · 한쪽 기록'}</Status></div>{!readOnly && t.by !== role && !t.confirmed && <Button onClick={() => onAction(`${t.direction} ${won(t.amount)} · 상대 확인`, d => { const r = d.rooms.find(r => r.id === room.id)!; r.transfers = roomTransfers(r); r.transfers.find(v => v.id === t.id)!.confirmed = true })}>{role === '임차인' && t.direction === '반환' ? '받았어요' : '송금 사실 확인'}</Button>}</div>)}</div>
      {!readOnly && <Button variant="outline" className="mt-4" onClick={() => open('transfer')}>반환·수령 기록 추가</Button>}
      {room.status === '거주 종료' && <div className="mt-5 flex flex-wrap items-center justify-between gap-3 border-t pt-5 max-md:flex-col max-md:items-stretch"><p className="text-sm text-[#8c9fb9]">이견과 미확인 항목은 완료 후에도 그대로 보존됩니다.</p><Button disabled={room.finishBy === role} onClick={() => onAction(room.finishBy ? '정산 끝내기 · 양측 확인' : '정산 끝내기 제안', d => { const r = d.rooms.find(r => r.id === room.id)!; if (r.finishBy && r.finishBy !== role || r.connection === '연결 전') r.status = '정산 완료'; else r.finishBy = role })}>{room.finishBy === role ? '상대 확인 대기' : room.finishBy ? '정산 끝내기 확인' : '정산 끝내기 제안'}</Button></div>}
    </Panel>
    <Dialog open={mode !== null} onOpenChange={v => { if (!v) setMode(null) }}><DialogContent className="app-dialog"><DialogHeader><DialogTitle>{mode === 'transfer' ? '반환·수령 사실 기록' : mode === 'item-counter' ? '이견 + 다른 안 제안' : mode?.includes('dispute') ? '금액 이견 남기기' : mode === 'exclude' ? '정산에서 빼기 제안' : mode === 'exclude-reject' ? '빼기 제안 거절' : mode === 'deposit' ? '보증금 금액 제안' : '새 금액 제안'}</DialogTitle><DialogDescription>한쪽의 제안은 상대가 같은 안에 동의해야 합의됩니다.</DialogDescription></DialogHeader>
      <form className="modal-form" onSubmit={submit}>
        {['deposit', 'item', 'item-counter', 'transfer'].includes(mode || '') && <div className="field"><label htmlFor="financial-amount">{mode === 'item-counter' ? '다른 안 금액 (원)' : '금액 (원)'}</label><MoneyInput id="financial-amount" name="amount" defaultValue={mode === 'deposit' ? offer?.amount ?? room.deposit : mode === 'item' || mode === 'item-counter' ? item?.amount : Math.abs(total - paid)} required /></div>}
        {(mode === 'item' || mode === 'item-counter') && <Choice label="계산 방향" value={direction} values={['차감', '가산']} onChange={setDirection} />}
        {mode === 'transfer' ? <><Choice label="송금 방향" value={transferDirection} values={['반환', '추가 송금']} onChange={setTransferDirection} /><p className="muted">{transferDirection === '반환' ? '임대인 → 임차인' : '임차인 → 임대인'} · {sideText(role)}의 한쪽 기록으로 남겨 상대에게 확인을 요청해요.</p></> : <div className="field"><label htmlFor="financial-reason">{mode === 'item-counter' ? '이견 사유 · 필수' : '사유 · 필수'}</label><Textarea id="financial-reason" name="reason" required /></div>}
        {error && <p role="alert" className="form-error">{error}</p>}<div className="modal-footer"><Button type="button" variant="outline" onClick={() => setMode(null)}>취소</Button><Button type="submit">{mode === 'transfer' ? '사실 기록 남기기' : '제안·응답 남기기'}</Button></div>
      </form>
    </DialogContent></Dialog>
  </>
}
