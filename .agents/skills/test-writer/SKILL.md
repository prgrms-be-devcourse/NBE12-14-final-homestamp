---
name: test-writer
description: 기능을 만들기 전에 시나리오·상태 규칙만 보고 실패하는 테스트를 먼저 쓴다. 사람이 부를 때만 쓴다
disable-model-invocation: true
---

# test-writer

기능을 만들기 **전에** 문서만 보고 테스트를 먼저 쓴다. 구현에 맞춘 테스트가 되지 않도록 구현 코드는 보지 않는다.

## 입력

시나리오 번호나 행동 이름. 예) `시나리오 1`, `수리 시작`
입력이 문서에서 안 찾아지면 비슷한 후보를 보여 주고 멈춘다.

## 읽는 것

- `docs/scenarios.md` — 시나리오
- `docs/rules/` — 상태 규칙
- 다른 도메인에 공개된 인터페이스(다른 도메인이 쓰라고 연 클래스·메서드)만
- `back/src/test/` — 기존 테스트의 모양을 맞추려고

**`back/src/main`의 구현 코드는 읽지 않는다.** 문서가 없으면 "시나리오·규칙 문서가 없어 테스트를 쓸 수 없습니다"라고 알리고 멈춘다.

## 쓰는 것

`back/src/test/` 아래만 쓴다. 패키지는 `com.chilsuitjo.back` 아래 해당 도메인.

- 시나리오 통합 테스트 1개 — 시나리오의 흐름을 처음부터 끝까지
- 상태 규칙 단위 테스트 — 허용되는 전이와 **막혀야 하는 전이** 둘 다
- 테스트 이름은 한국어로 무엇을 확인하는지 드러나게 (`@DisplayName` 또는 메서드 이름)
- 기대값은 문서에서 가져온다. 문서에 없는 값은 지어내지 말고 결과에 "문서에 없음"으로 적는다

테스트 작성 세부 규칙(Testcontainers, Clock 주입 등): TODO — 첫 기능이 머지된 뒤 채운다

## 끝낼 때

1. `cd back && ./gradlew test` (Windows는 `gradlew.bat test`)를 한 번 돌린다
2. 아직 구현이 없으니 **실패하는 것**이 정상이다
3. 컴파일이 안 되면, 테스트가 필요로 하는 클래스·메서드 이름과 시그니처를 목록으로 알려 준다

## 하지 않는 것

- `back/src/main` 읽기·수정
- 테스트를 통과시키려고 기대값 바꾸기, 테스트 지우기, `@Disabled` 붙이기

## 결과 모양

```
LeaseScenarioTest.세입자가_수리를_요청하면_임대인에게_알림이_간다 — 시나리오 1 흐름 — docs/scenarios.md 시나리오 1
RepairStatusTest.완료된_수리는_다시_시작할_수_없다 — 완료 → 진행 전이 금지 — docs/rules/repair.md 상태표
실행 결과: 실패 3 / 컴파일 오류 없음
필요한 구현: RepairService.start(Long repairId, Long landlordId)
```
