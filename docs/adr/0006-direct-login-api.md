# 0006. 로그인은 직접 로그인 API로 하고 PKCE는 확장안으로 둔다

- 상태: 채택
- 날짜: 2026-09-24

## 맥락

앱은 관리 콘솔과 파트너 웹 두 개이고 모두 같은 회사가 만듭니다. SSO나 외부 앱 연동 요구는 아직 없습니다.

## 결정

- 각 앱이 자기 화면에서 Auth의 로그인 API를 호출합니다. Auth는 화면을 제공하지 않습니다.
- access token은 응답 본문, refresh token은 HttpOnly 쿠키로 줍니다.
- SSO, Auth 중심 MFA·소셜 로그인, 외부 앱 연동이 필요해지면 Authorization Code + PKCE(Spring Authorization Server)를 도입합니다. 토큰 형식과 서비스 쪽은 바뀌지 않습니다.

## 검토한 대안

- 처음부터 PKCE + Auth 로그인 화면: Auth가 UI를 가져야 하고, 두 앱 규모에서는 이득이 작습니다.

## 결과

- 앱마다 로그인해야 합니다(SSO 없음).
- 비밀번호가 앱 화면을 거치므로 앱은 같은 회사 앱만 허용합니다.
