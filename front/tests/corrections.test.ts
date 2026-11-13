import { expect, test } from 'vitest'
import { createDemo, type Repair } from '../src/data/demo'
import { answerCorrection, confirmDefect, disputeDefect, requestCorrection, requestRepairEdits } from '../src/lib/repair-rules'
import { answer, confirmItem, requestItemEdits } from '../src/lib/inspection-rules'
import { VERSION_CONFLICT_MESSAGE } from '../src/lib/versions'
import { pendingRequest } from '../src/lib/corrections'

const repair = (ack: Repair['ack']): Repair => ({ ...structuredClone(createDemo().repairs[0]), ack, ackDispute: undefined, author: '임차인', corrections: [], version: 1 })

test('등록 뒤에는 상대가 확인하기 전이어도 고치기가 승인 대기로 남고 원본은 그대로다', () => {
  const r = repair('확인 대기')
  const before = r.title
  requestRepairEdits(r, '임차인', { title: '새 제목', description: r.description, zone: r.zone }, '다시 봤어요')
  expect(r.title).toBe(before)
  expect(r.corrections!.map(c => c.status)).toEqual(['확인 대기'])
  expect(() => requestRepairEdits(r, '임차인', { title: '또 제목', description: r.description, zone: r.zone }, '')).toThrow()
})

test('지불 금액 고치기도 확인 여부와 관계없이 상대 승인이 있어야 바뀐다', () => {
  const r = repair('확인 대기')
  r.payments = [{ id: 'p2', by: '임차인', payer: '임차인', payee: '업체', amount: 10000, confirmed: false }]
  requestCorrection(r, undefined, '임차인', '금액', 10000, 12000, '오타', r.payments[0])
  expect(r.payments[0].amount).toBe(10000)
})

test('이견이 있는 기록의 고치기: 승인 전에는 원본·이견 유지, 승인 뒤 새 내용은 다시 확인 대기이고 이전 이견은 이력에 남는다', () => {
  const r = repair('확인 대기')
  disputeDefect(r, '임대인', '설명과 다름', '천장이 아니라 벽이에요')
  requestRepairEdits(r, '임차인', { title: r.title, description: r.description, zone: '거실 · 벽' }, '부위 정정')
  expect(r.ack).toBe('이견')
  expect(r.ackDispute?.reason).toBe('천장이 아니라 벽이에요')
  answerCorrection(r, '임대인', r.corrections![0].id, true)
  expect(r.zone).toBe('거실 · 벽')
  expect(r.ack).toBe('확인 대기')
  expect(r.corrections![0].priorDispute).toMatch('천장이 아니라 벽이에요')
  expect(r.version).toBe(2)
})

test('확인할 때 본 모습 번호가 지금과 다르거나, 보던 고친 내용이 다시 고쳐졌으면 다시 보고 확인해야 한다', () => {
  const r = repair('확인 대기')
  expect(() => confirmDefect(r, '임대인', 0)).toThrow(VERSION_CONFLICT_MESSAGE)
  requestRepairEdits(r, '임차인', { title: '1번 제목', description: r.description, zone: r.zone }, '정정')
  const seen = r.corrections![0].id
  requestRepairEdits(r, '임차인', { title: '2번 제목', description: r.description, zone: r.zone }, '다시 정정')
  expect(() => answerCorrection(r, '임대인', seen, true)).toThrow(VERSION_CONFLICT_MESSAGE)
  confirmDefect(r, '임대인', 1)
  expect(r.ack).toBe('확인됨')
})

test('점검 항목: 작성 중에는 바로 고쳐지고, 확인 요청 뒤에는 승인 대기·모습 번호 검사', () => {
  const d = createDemo()
  const i = d.inspections.find(x => x.author === '임차인')!
  i.ack = '확인 대기'; i.corrections = []; i.version = 1
  i.publication = 'draft'
  requestItemEdits(d, '임차인', i.id, { title: '초안 제목', description: i.description, zone: i.zone }, '')
  expect(i.title).toBe('초안 제목')
  i.publication = 'requested'
  requestItemEdits(d, '임차인', i.id, { title: '등록 뒤 제목', description: i.description, zone: i.zone }, '정정')
  expect(i.title).toBe('초안 제목')
  expect(() => confirmItem(d, '임대인', i.id, 1)).toThrow(VERSION_CONFLICT_MESSAGE)
  answer(d, '임대인', i.id, i.corrections!.at(-1)!.id, true)
  expect(i.title).toBe('등록 뒤 제목')
  confirmItem(d, '임대인', i.id, i.version)
  expect(i.ack).toBe('확인됨')
})

test('저장 규칙 3·REP-7h: 칸마다 기다리는 고친 내용은 하나, 기록의 확인 요청은 하나로 묶인다', () => {
  const r = repair('확인 대기')
  requestRepairEdits(r, '임차인', { title: '제목 1', description: r.description, zone: r.zone }, '첫 고치기')
  requestRepairEdits(r, '임차인', { title: '제목 1', description: '설명 2', zone: r.zone }, '두 번째')
  requestRepairEdits(r, '임차인', { title: '제목 3', description: '설명 2', zone: r.zone }, '세 번째')
  const waiting = r.corrections!.filter(c => c.status === '확인 대기')
  expect(waiting.map(c => `${c.field}:${c.to}`).sort()).toEqual(['설명:설명 2', '제목:제목 3'])
  expect(r.corrections!.find(c => c.to === '제목 1')!.status).toBe('대체됨')
  expect(pendingRequest(r.corrections!)!.items).toHaveLength(2)
})