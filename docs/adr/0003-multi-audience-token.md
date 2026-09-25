# 0003. 토큰 하나에 여러 audience를 싣고 role은 audience로 네임스페이스한다

- 상태: 채택
- 날짜: 2026-09-24

## 맥락

직원 한 명이 WMS와 Catalog를 함께 쓰는 경우가 많습니다. 서비스마다 토큰을 따로 받으면 앱이 복잡해집니다.

## 결정

- access token 하나에 `aud`를 여러 개 넣습니다. `aud`는 보유 role의 audience 목록입니다.
- role은 `{audience}:{code}` 형식입니다. 서비스는 자기 prefix의 role만 씁니다.
- 파트너 토큰의 `aud`는 `["store"]` 고정입니다.

## 검토한 대안

- 서비스별 토큰 교환: 호출할 때마다 교환 요청이 필요합니다.
- 평문 role: `wms`의 `admin`과 `catalog`의 `admin`을 구분할 수 없습니다.

## 결과

- 토큰 하나로 여러 서비스를 호출할 수 있습니다. 대신 토큰이 유출되면 여러 서비스에 영향이 있습니다.
- 관련 문서: [token.md §4](../token.md#4-aud-결정-규칙)
