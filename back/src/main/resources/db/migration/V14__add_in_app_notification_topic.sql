-- 서비스 안 알림 주제(#116 U3-1). 알려 줌(INFO)·서비스 안내(SERVICE)는 주제가 무슨 알림인지 정하고, 응답 필요(RESPONSE)는 주제 없이
-- 응답 요청(response_request_id)이 정한다. 문구는 저장하지 않는다(08이 원본).
--
-- 주제는 번호 인증 해제(PHONE_VERIFICATION_RELEASED, 계정 알림)와 수리 건 사진 추가(REPAIR_PHOTO_ADDED, 호실 알림, #381)다. 주제를 더할 때마다 새 V 파일로 ck_in_app_topic을 다시 만든다.
-- 주제별 규칙(계정 주제면 호실·대상·요청 ID 없음 등)은 notification 코드와 저장 테스트가 지킨다. DB에는 종류 단위 규칙만 둔다.
--
-- 기존 행은 없다: 이 버전 전까지 운영 InAppNotifier는 메모리 가짜였다(#381 사진 추가 알림도 메모리에만 쌓였다). 이전 버전 앱은 이 테이블에 쓰지 않으므로 블루/그린 전환 중에도 새 CHECK에
-- 걸리지 않는다(04 8장 "스키마 변경: 이전 버전 앱과도 맞게").
ALTER TABLE state.in_app_notification
    ADD COLUMN topic varchar(40),
    ADD CONSTRAINT ck_in_app_topic CHECK (topic IS NULL OR topic IN ('PHONE_VERIFICATION_RELEASED', 'REPAIR_PHOTO_ADDED')),
    ADD CONSTRAINT ck_in_app_topic_kind CHECK ((kind = 'RESPONSE') = (topic IS NULL));
