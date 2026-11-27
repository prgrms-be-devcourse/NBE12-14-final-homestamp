-- 지불(V6)과 같이 정산 송금의 응답 전 상태를 PENDING으로 맞춘다.
-- 기존 값과 기본값·CHECK를 한 트랜잭션에서 바꾼다.

ALTER TABLE state.settlement_transfer DROP CONSTRAINT settlement_transfer_response_check;

UPDATE state.settlement_transfer SET response = 'PENDING' WHERE response = 'NONE';

ALTER TABLE state.settlement_transfer ALTER COLUMN response SET DEFAULT 'PENDING';

ALTER TABLE state.settlement_transfer
    ADD CONSTRAINT settlement_transfer_response_check CHECK (response IN ('PENDING', 'ACK', 'NEED_MORE', 'DISPUTE'));
