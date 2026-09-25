# 0020. 계정은 물리 삭제하지 않고 비활성화하며 개인정보를 파기한다

- 상태: 채택
- 날짜: 2026-09-24

## 맥락

다른 서비스와 감사 로그가 principal id를 참조합니다. 한편 탈퇴·퇴사자의 개인정보는 남기면 안 됩니다.

## 결정

- 계정은 `DEACTIVATED`로 바꾸고 `principal` 행은 남깁니다.
- profile의 개인정보는 즉시 파기(마스킹)하고, 크리덴셜·role·세션은 삭제합니다.
- 만료·폐기된 세션은 `policy.session-retention`, verification은 `policy.verification-retention`, 감사 로그는 `policy.audit-retention` 뒤 삭제합니다.

## 검토한 대안

- 물리 삭제: 참조가 깨지고 감사 추적이 불가능합니다.
- soft delete만(개인정보 유지): 개인정보 보관 문제가 남습니다.

## 결과

- 같은 이메일로 다시 가입·초대할 수 있습니다.
- 서비스는 사용자 이름·이메일 사본을 저장하지 않아야 파기가 의미 있습니다.
- 관련 규칙: [ACC-04](../domain.md#3-계정-상태-규칙-acc), [AUD-05](../domain.md#11-감사와-알림-aud)
