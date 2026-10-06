#!/usr/bin/env bash
# 서버 릴리스 노트를 만든다 (server-release.yml, ADR-0032). 결과 Markdown은 표준 출력으로 낸다.
#
#   .github/scripts/server-release-notes.sh <태그> [커밋]
#
# 직전 server-v* 태그부터 이 태그까지 main에 병합된 PR 중 `module: server` 라벨이 있는 것만 모은다.
# GitHub의 자동 릴리스 노트(.github/release.yml)는 라벨로 PR을 고를 수 없고 저장소에 설정이 하나뿐이라,
# 라이브러리 릴리스(v*)용 설정은 그대로 두고 서버 노트는 여기서 만든다. 분류는 release.yml과 같다.
#
# PR 번호는 squash 병합 커밋 제목 끝의 (#123)에서 읽는다. gh(GH_TOKEN)와 jq가 필요하고, 전체 git 기록이 있어야 한다.
set -euo pipefail

tag="${1:?태그 이름이 필요합니다 (예: server-v0.1.0)}"
ref="${2:-$tag}"
repo="${GITHUB_REPOSITORY:-dozycoffee/dozy-auth}"

previous="$(git describe --tags --abbrev=0 --match 'server-v[0-9]*' "${ref}^" 2>/dev/null || true)"
if [[ -n "$previous" ]]; then
  range="${previous}..${ref}"
  compare="https://github.com/${repo}/compare/${previous}...${tag}"
else
  range="$ref"
  compare="https://github.com/${repo}/commits/${tag}"
fi

numbers="$(git log --first-parent --format=%s "$range" | sed -nE 's/.*\(#([0-9]+)\)$/\1/p' | jq -R 'tonumber' | jq -s '.')"

gh pr list --repo "$repo" --state merged --label "module: server" --limit 1000 \
  --json number,title,url,author,labels |
  jq -r --argjson numbers "$numbers" --arg compare "$compare" '
    def category:
      [.labels[].name] as $l
      | if any($l[]; . == "breaking-change") then "호환성 변경"
        elif any($l[]; . == "feature" or . == "request") then "기능"
        elif any($l[]; . == "bug") then "버그 수정"
        elif any($l[]; . == "security") then "보안"
        else "기타" end;
    [ .[]
      | select(.number as $n | $numbers | index($n))
      | select([.labels[].name] | any(. == "duplicate" or . == "wontfix" or . == "release") | not)
      | . + {category: category} ] as $prs
    | (if ($prs | length) == 0 then "이번 릴리스에는 서버 변경 PR이 없습니다.\n"
       else
         ["호환성 변경", "기능", "버그 수정", "보안", "기타"]
         | map(. as $c | [$prs[] | select(.category == $c)] | sort_by(.number)
               | select(length > 0)
               | "### \($c)\n\n" + (map("- \(.title) by @\(.author.login) in \(.url)") | join("\n")) + "\n")
         | join("\n")
       end)
    + "\n**Full Changelog**: \($compare)"
  '
