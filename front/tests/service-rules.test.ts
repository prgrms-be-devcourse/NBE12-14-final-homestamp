import { strict as assert } from "node:assert"
import { test } from "vitest"
import { createDemo } from "../src/data/demo.ts"
import { plainText } from "../src/lib/plain-words.ts"
import {
  settlementTotal,
  hasUnsettledMoney,
  safeSharedText,
  netExpenditure,
  hasResidentId,
} from "../src/lib/service-rules.ts"

test("이견·대기 금액은 합의된 정산액에 들어가지 않는다", () => {
  const db = createDemo()
  assert.equal(settlementTotal(10000000, db.settlements), 9995000)
  assert.equal(
    settlementTotal(10000000, [
      ...db.settlements,
      {
        ...db.settlements[0],
        id: "pending",
        amount: 1000000,
        status: "합의 대기",
      },
    ]),
    9995000,
  )
})
test("수리가 완료돼도 돈이 미정이면 남은 돈에 포함한다", () => {
  const r = createDemo().repairs.find((r) => r.id === "window")!
  assert.equal(r.status, "처리 완료")
  assert.equal(hasUnsettledMoney(r), true)
})
test("합의됐어도 실제 지불과 부담이 다르면 돈이 남는다", () => {
  const r = createDemo().repairs[0]
  r.status = "처리 완료"
  r.proposal!.status = "합의됨"
  assert.equal(hasUnsettledMoney(r), true)
  r.payments = [{ id: "p", by: "임대인", amount: 65000, confirmed: true }]
  assert.equal(hasUnsettledMoney(r), false)
})
test("비용 없음은 상대 동의 후 완료 상태로 본다", () => {
  const r = createDemo().repairs[0]
  r.proposal!.status = "비용 없음"
  assert.equal(hasUnsettledMoney(r), false)
})
test("공유 설명의 전화번호를 가린다", () => {
  assert.equal(safeSharedText("연락처 010-1234-5678"), "연락처 [연락처 가림]")
})
test("임차인이 업체에 낸 뒤 임대인이 전액 상환하면 돈이 남지 않는다", () => {
  const r = createDemo().repairs[0]
  r.proposal!.status = "합의됨"
  r.payments = [
    {
      id: "vendor",
      by: "임차인",
      payer: "임차인",
      payee: "업체",
      amount: 65000,
      confirmed: true,
    },
    {
      id: "refund",
      by: "임대인",
      payer: "임대인",
      payee: "임차인",
      amount: 65000,
      confirmed: true,
    },
  ]
  assert.equal(hasUnsettledMoney(r), false)
})
test("일부 상환·초과 상환은 미정산이고, 수령 측이 기록해도 실제 방향으로 계산한다", () => {
  const r = createDemo().repairs[0]
  r.proposal!.status = "합의됨"
  r.payments = [
    {
      id: "vendor",
      by: "임차인",
      payer: "임차인",
      payee: "업체",
      amount: 65000,
      confirmed: true,
    },
    {
      id: "refund",
      by: "임차인",
      payer: "임대인",
      payee: "임차인",
      amount: 30000,
      confirmed: false,
    },
  ]
  assert.equal(netExpenditure(r.payments, "임차인"), 35000)
  assert.equal(netExpenditure(r.payments, "임대인"), 30000)
  assert.equal(hasUnsettledMoney(r), true)
  r.payments[1].amount = 70000
  assert.equal(hasUnsettledMoney(r), true)
  r.payments[1].amount = 65000
  assert.equal(hasUnsettledMoney(r), false)
  assert.equal(r.payments[1].confirmed, false)
})
test("나눠서 부담과 역방향 상환에도 같은 순지출 계산을 적용한다", () => {
  const r = createDemo().repairs[0]
  r.proposal = {
    by: "임대인",
    amount: 65000,
    payer: "나눠서",
    tenantAmount: 32500,
    status: "합의됨",
  }
  r.payments = [
    {
      id: "vendor",
      by: "임대인",
      payer: "임대인",
      payee: "업체",
      amount: 65000,
      confirmed: true,
    },
    {
      id: "refund",
      by: "임차인",
      payer: "임차인",
      payee: "임대인",
      amount: 32500,
      confirmed: true,
    },
  ]
  assert.equal(hasUnsettledMoney(r), false)
  r.proposal.status = "합의 대기"
  assert.equal(hasUnsettledMoney(r), true)
})

test("주민등록번호 형태(000000-0000000)만 막고, 긴 숫자·숫자 반복·일반 문장은 통과시킨다", () => {
  for (const blocked of [
    "900101-1234567",
    "세입자 번호 850315 - 2345678 입니다",
    "000229-3123456",
  ])
    assert.equal(hasResidentId(blocked), true, blocked)
  for (const allowed of [
    "1234567890123",
    "9001011234567",
    "111111-1111111",
    "991332-1234567",
    "900100-1234567",
    "900101-9234567",
    "1900101-1234567",
    "900101-12345678",
    "계좌 110-123-456789로 보냈어요",
    "2026-11-12 오후 3시에 수리했어요",
    "0".repeat(40),
  ])
    assert.equal(hasResidentId(allowed), false, allowed)
})
test("경과 문구는 화면에서만 쉬운 말로 바뀌고 원문은 그대로다(08 13장 · 부록 A)", () => {
  const original =
    "창틀 · 부담 역제안 · 합의 대기 · 링크 열람 · 상대 미확인 · 하자 사실 확인 · 본 모습 2번"
  const shown = plainText(original)
  assert.equal(
    shown,
    "창틀 · 다른 안 제안 · 답을 기다려요 · 링크로 봤어요 · 확인 없음 · 하자 사실 확인 · 2번째 내용을 보고 확인",
  )
  assert.equal(
    original,
    "창틀 · 부담 역제안 · 합의 대기 · 링크 열람 · 상대 미확인 · 하자 사실 확인 · 본 모습 2번",
  )
})
