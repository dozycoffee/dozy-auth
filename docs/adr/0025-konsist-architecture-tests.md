# 0025. 아키텍처 규칙은 Konsist로 검사하고 detekt는 나중에 도입한다

- 상태: 채택
- 날짜: 2026-09-24

## 맥락

헥사고날 구조의 의존 규칙과 이름 규칙은 리뷰만으로는 지키기 어렵습니다.

## 결정

- 계층 의존, 도메인 간 import 금지, 이름 규칙, `@Transactional` 위치를 Konsist 테스트로 검사합니다.
- 포맷은 ktlint로 합니다.
- detekt는 코드가 쌓인 뒤 도입합니다.

## 검토한 대안

- ArchUnit: Kotlin 선언(파일, 확장 함수) 표현이 약합니다.

## 결과

- 관련 문서: [architecture.md §6.3](../architecture.md#63-아키텍처-테스트)
