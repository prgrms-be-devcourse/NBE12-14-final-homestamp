import { expect, test } from 'vitest'
import { respondToOffer, acceptInvitation, declineInvitation } from '../src/lib/workflows'
import { createDemo, type Role } from '../src/data/demo'

test('제안자가 자신에게 동의하거나 이견 사유 없이 응답할 수 없다', () => {
  const proposal = createDemo().repairs[0].proposal!
  expect(() => respondToOffer(proposal, '임대인', true)).toThrow()
  expect(() => respondToOffer(proposal, '임차인', false, '  ')).toThrow()
  expect(proposal.status).toBe('합의 대기')
})

test('연결 수락은 계정 인증 번호와 초대 번호 일치, 공유 동의가 모두 있어야 하고 거절 뒤에는 수락할 수 없다', () => {
  const room = { ...createDemo().rooms[0], creator: '임대인' as Role, connection: '초대됨' as const, phone: '01011112222' }
  expect(() => acceptInvitation(room, '임차인', '01012345678', true, true, '01012345678')).toThrow()
  room.phone = '01012345678'
  expect(() => acceptInvitation(room, '임차인', '01012345678', true, false, '01012345678')).toThrow()
  expect(() => declineInvitation(room, '임대인', '01012345678')).toThrow()
  declineInvitation(room, '임차인', '01012345678')
  expect(() => acceptInvitation(room, '임차인', '01012345678', true, true, '01012345678')).toThrow()
  expect(room.connection).toBe('거절됨')
})
