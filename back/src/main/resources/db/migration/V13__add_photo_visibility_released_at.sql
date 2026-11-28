-- 사진이 상대에게 보이기 시작한 시각(#295, 03 3-1 "작성 중 점검", 06 INSP-1a).
--
-- released_at: 보통 사진은 붙인 때(created_at과 같은 값). 기준 측이 작성 중 점검에 붙인 사진은 비워 두고, 확인 요청
-- (REQUEST_INSPECTION_CONFIRM)을 저장할 때 그 행동 시각으로 채운다. 비어 있으면 올린 측에게만 보이고, 공유를 그만둔 시각보다 뒤면
-- 상대에게 보이지 않는다(불변식 18). 한 번 채우면 바꾸지 않는다.
--
-- DEFAULT now(): 블루/그린 전환 중 이 칸을 모르는 이전 버전 앱이 넣는 줄도 붙인 때부터 보이게 한다(04 8장 "스키마 변경: 이전 버전 앱과도
-- 맞게"). 새 앱은 늘 값을 직접 넣는다(작성 중 점검 사진은 NULL).
ALTER TABLE state.photo_visibility
    ADD COLUMN released_at timestamptz DEFAULT now();

-- 기존 줄: 붙인 때부터 보인 것으로 채운다
UPDATE state.photo_visibility SET released_at = created_at;

-- 기존 줄 중 기준 측이 점검에 붙인 사진: 아직 작성 중이면 비우고, 확인 요청을 보냈으면 그 시각보다 이르지 않게 한다
-- (작성 중에 붙인 사진은 확인 요청 때부터, 확인 요청 뒤에 더한 사진은 붙인 때부터)
-- 확인 요청은 점검마다 첫 시각 하나로 모은다. 지금 전이로는 DRAFT에서 한 번뿐이지만, 여러 개면 UPDATE ... FROM이 아무 줄이나 고르므로
-- 작성 중이 처음 끝난 때로 정한다. 요청 없이 DRAFT가 아닌 점검은 지금 없고, 있으면 GREATEST가 NULL을 무시해 붙인 때부터 보인다.
UPDATE state.photo_visibility v
   SET released_at = CASE
                         WHEN s.status = 'DRAFT' THEN NULL
                         ELSE GREATEST(v.created_at, r.requested_at)
                     END
  FROM record.photo p
  JOIN state.inspection s
    ON s.id = COALESCE(p.inspection_id,
                       (SELECT i.inspection_id FROM state.inspection_item i WHERE i.id = p.inspection_item_id))
  LEFT JOIN (SELECT a.inspection_id, MIN(a.created_at) AS requested_at
               FROM record.action a
              WHERE a.type = 'REQUEST_INSPECTION_CONFIRM'
              GROUP BY a.inspection_id) r
    ON r.inspection_id = s.id
 WHERE p.id = v.photo_id
   AND p.uploaded_by_side = s.author_side;
