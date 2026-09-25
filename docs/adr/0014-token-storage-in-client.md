# 0014. access token은 앱 메모리, refresh token은 HttpOnly 쿠키에 둔다

- 상태: 채택
- 날짜: 2026-09-24

## 맥락

브라우저 앱에서 토큰을 localStorage에 두면 XSS로 탈취됩니다.

## 결정

- access token은 응답 본문으로 주고 앱은 메모리에만 둡니다.
- refresh token은 Auth가 HttpOnly 쿠키로 설정합니다. JS에서 읽을 수 없습니다.
- 쿠키 `Path`는 `/realms/{realm}`입니다. 갱신과 로그아웃 모두 쿠키가 필요하기 때문입니다.
- 쿠키를 쓰는 API는 `Origin` 검사로 CSRF를 막습니다.

## 검토한 대안

- 둘 다 localStorage: XSS에 취약합니다.
- `Path=/realms/{realm}/token`: 로그아웃에 쿠키가 전송되지 않습니다.

## 결과

- 앱과 Auth가 같은 상위 도메인에 있어야 합니다.
- 새로고침하면 access token이 사라지므로 앱은 시작할 때 갱신을 호출합니다.
- 관련 문서: [api/conventions.md §6](../api/conventions.md#6-refresh-쿠키)
