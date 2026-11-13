import { expect, test } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { createDemo, type Repair } from '../src/data/demo'
import { maskOf, maskPhoto, objectToMask } from '../src/lib/photo-privacy'
import { PhotoManageList } from '../src/components/photo-masks'
import { PhotoGallery } from '../src/components/photo-gallery'

const repair = (): Repair => ({ ...structuredClone(createDemo().repairs[0]), photos: ['data:image/jpeg;base64,AA', 'data:image/jpeg;base64,BB'], privacyMasks: undefined })

test('개인정보 가리기: 올린 사람만, 한 번만, 되돌리기 없이 가림 기록이 남는다', () => {
  const r = repair()
  const them = r.author === '임차인' ? '임대인' : '임차인'
  expect(() => maskPhoto(r, 'b:0', them, r.author)).toThrow(/올린 사람만/)
  maskPhoto(r, 'b:0', r.author, r.author)
  expect(maskOf(r, 'b:0')?.by).toBe(r.author)
  expect(r.photos).toHaveLength(2) // 사진은 지우지 않아요
  expect(() => maskPhoto(r, 'b:0', r.author, r.author)).toThrow(/이미/)
})

test('가린 사진에는 상대만 사유와 함께 이견을 남길 수 있다', () => {
  const r = repair()
  const them = r.author === '임차인' ? '임대인' : '임차인'
  maskPhoto(r, 'b:1', r.author, r.author)
  expect(() => objectToMask(r, 'b:1', r.author, '왜요')).toThrow(/상대만/)
  expect(() => objectToMask(r, 'b:1', them, '  ')).toThrow(/사유/)
  objectToMask(r, 'b:1', them, '하자 부위가 찍혀 있었어요')
  expect(maskOf(r, 'b:1')?.objections?.[0]).toMatchObject({ by: them, reason: '하자 부위가 찍혀 있었어요' })
})

test('가린 사진은 흐리게 "개인정보 이유로 가림"으로 보인다', () => {
  render(<PhotoGallery title="벽" photos={['data:image/jpeg;base64,AA']} masks={[{ key: '0', by: '임차인', at: '11/12' }]} />)
  expect(screen.getAllByText('개인정보 이유로 가림').length).toBeGreaterThan(0)
  expect(screen.queryByAltText('벽 · 사진 1')).toBeNull()
})

test('가린 사진은 뒤로 가고 열려 있는 사진이 첫 장이 된다', () => {
  render(<PhotoGallery title="벽" layout="compact" photos={['data:,masked', 'data:,open']} masks={[{ key: '0', by: '임차인', at: '11/12' }, undefined]} />)
  expect(screen.getByAltText('벽 · 사진 1').getAttribute('src')).toBe('data:,open')
})

test('사진 관리: 체크박스로 여러 장을 골라 잘못 올린 사진 표시를 한 번에 한다', () => {
  let got: [string[], boolean] | null = null
  render(<PhotoManageList items={[{ key: '0', src: 'data:,a', label: '사진 1', canWrong: true }, { key: '1', src: 'data:,b', label: '사진 2', canWrong: true }, { key: '2', src: 'data:,c', label: '사진 3', canWrong: true, masked: true }]}
    onWrong={(keys, mark) => { got = [keys, mark] }} onMask={() => {}} />)
  fireEvent.click(screen.getByRole('checkbox', { name: '사진 1 선택' }))
  fireEvent.click(screen.getByRole('checkbox', { name: '사진 2 선택' }))
  fireEvent.click(screen.getByRole('button', { name: '잘못 올린 사진 표시' }))
  expect(got).toEqual([['0', '1'], true])
})
