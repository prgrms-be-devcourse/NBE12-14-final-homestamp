-- 지불의 응답 전 상태를 PENDING으로 맞춘다. PaymentResponse와 응답 공통 규칙(AnswerSupport)이 같은 값을 쓴다.
-- 기존 V1은 유지하고, 저장된 NONE과 기본값·CHECK를 한 트랜잭션에서 바꾼다.
-- 정산 송금(settlement_transfer)의 response는 이 변경 대상이 아니다.

ALTER TABLE state.payment DROP CONSTRAINT payment_response_check;

UPDATE state.payment SET response = 'PENDING' WHERE response = 'NONE';

ALTER TABLE state.payment ALTER COLUMN response SET DEFAULT 'PENDING';

ALTER TABLE state.payment
    ADD CONSTRAINT payment_response_check CHECK (response IN ('PENDING', 'ACK', 'NEED_MORE', 'DISPUTE'));
