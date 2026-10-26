#!/usr/bin/env bash
# Decides the next release from the commits since the latest vX.Y.Z tag and writes its notes.
#
# Usage: scripts/next-release.sh <notes-output-file>
#
# stdout: key=value lines for $GITHUB_OUTPUT (release, version, tag, previous, bump).
# stderr: human-readable log. Never creates tags; safe to run locally as a dry run.
#
# Bump (Conventional Commits, over every commit including the ones inside merged PRs):
#   "type!:" or a "BREAKING CHANGE:" footer -> major, feat -> minor, fix/perf -> patch,
#   anything else (docs, ci, test, chore, ...) -> no release.
set -euo pipefail

notes_file="${1:?usage: scripts/next-release.sh <notes-output-file>}"

log() { echo "$*" >&2; }

repo="${GITHUB_REPOSITORY:-}"
if [[ -z "$repo" ]]; then
  repo="$(git remote get-url origin | sed -E 's#^(https://github\.com/|git@github\.com:)##; s#\.git$##')"
fi

use_gh=false
if command -v gh >/dev/null && gh auth status >/dev/null 2>&1; then
  use_gh=true
else
  log "warning: gh missing or not authenticated; PR titles and authors come from git only"
fi

previous="$(git tag --merged HEAD --list 'v[0-9]*.[0-9]*.[0-9]*' --sort=-v:refname | head -n1)"
if [[ -n "$previous" ]]; then
  range="$previous..HEAD"
  IFS=. read -r major minor patch <<<"${previous#v}"
else
  range="HEAD"
  major=0 minor=0 patch=0
fi
log "base: ${previous:-<no tag>} ($range)"

# 3 = breaking, 2 = feat, 1 = fix/perf, 0 = no release. Also the section an entry goes to.
level_of() { # <sha>
  local subject body
  subject="$(git log -1 --format=%s "$1")"
  body="$(git log -1 --format=%b "$1")"
  if [[ "$subject" =~ ^[a-z]+(\([^\)]*\))?!: ]] || grep -qE '^BREAKING[ -]CHANGE:' <<<"$body"; then
    echo 3
  elif [[ "$subject" =~ ^feat(\([^\)]*\))?: ]]; then
    echo 2
  elif [[ "$subject" =~ ^(fix|perf)(\([^\)]*\))?: ]]; then
    echo 1
  else
    echo 0
  fi
}

# Section key for the notes: breaking, feat, fix, perf, other (refactor/build/deps), skip.
section_of() { # <subject> <level>
  if [[ "$2" == 3 ]]; then echo breaking; return; fi
  case "$1" in
    feat*) echo feat ;;
    fix*) echo fix ;;
    perf*) echo perf ;;
    refactor* | build* | deps*) echo other ;;
    *) echo skip ;;
  esac
}

# "feat(terminal): open links" -> "**terminal**: open links"; non-conventional text is kept as is.
pretty() { # <subject>
  if [[ "$1" =~ ^[a-z]+(\(([^\)]*)\))?!?:\ (.*)$ ]]; then
    local scope="${BASH_REMATCH[2]}" text="${BASH_REMATCH[3]}"
    text="${text%% (#[0-9]*)}"
    if [[ -n "$scope" ]]; then echo "**$scope**: $text"; else echo "$text"; fi
  else
    echo "$1"
  fi
}

declare -A sections=([breaking]="" [feat]="" [fix]="" [perf]="" [other]="")
max_level=0

while read -r sha; do
  [[ -z "$sha" ]] && continue
  subject="$(git log -1 --format=%s "$sha")"
  pr="" title="$subject" author="$(git log -1 --format=%an "$sha")"
  commits=("$sha")

  if [[ "$subject" =~ ^Merge\ pull\ request\ \#([0-9]+) ]]; then
    pr="${BASH_REMATCH[1]}"
    mapfile -t commits < <(git log --no-merges --reverse --format=%H "$sha^1..$sha^2")
    # GitHub puts the PR title on the first body line of its merge commit.
    title="$(git log -1 --format=%b "$sha" | sed -n '/./{p;q}')"
    author="$(git log -1 --format=%an "$sha^2")"
    if $use_gh; then
      if info="$(gh pr view "$pr" --repo "$repo" --json title,author -q '.title + "\t" + .author.login')"; then
        title="${info%%$'\t'*}"
        author="@${info#*$'\t'}"
      else
        log "warning: could not read PR #$pr with gh; using the merge commit"
      fi
    fi
    [[ -z "$title" ]] && title="$subject"
  elif [[ "$subject" =~ \(#([0-9]+)\)$ ]]; then
    pr="${BASH_REMATCH[1]}"
  fi

  entry_level=0
  footers=""
  for c in "${commits[@]}"; do
    l="$(level_of "$c")"
    (( l > entry_level )) && entry_level=$l
    if [[ "$l" == 3 ]]; then
      footer="$(git log -1 --format=%b "$c" | sed -n '/^BREAKING[ -]CHANGE:/,$p' | sed -E 's/^BREAKING[ -]CHANGE: *//')"
      [[ -n "$footer" ]] && footers+="  - ⚠️ ${footer//$'\n'/ }"$'\n'
    fi
  done
  (( entry_level > max_level )) && max_level=$entry_level

  # A PR goes to the section of its most important commit, even if its title says otherwise.
  section="$(section_of "$title" "$entry_level")"
  if [[ "$section" == skip && "$entry_level" -gt 0 ]]; then
    case "$entry_level" in 2) section=feat ;; 1) section=fix ;; esac
  fi
  if [[ "$section" == skip ]]; then
    log "skip: $subject"
    continue
  fi
  log "$section: $title"

  line="- $(pretty "$title")"
  [[ -n "$pr" ]] && line+=" (#$pr)"
  line+=" — $author"$'\n'"$footers"
  if (( ${#commits[@]} > 1 )); then
    for c in "${commits[@]}"; do
      line+="  - $(pretty "$(git log -1 --format=%s "$c")")"$'\n'
    done
  fi
  sections[$section]+="$line"
done < <(git log --first-parent --reverse --format=%H "$range")

case "$max_level" in
  3) bump=major; major=$((major + 1)); minor=0; patch=0 ;;
  2) bump=minor; minor=$((minor + 1)); patch=0 ;;
  1) bump=patch; patch=$((patch + 1)) ;;
  *) bump=none ;;
esac
version="$major.$minor.$patch"
tag="v$version"

if [[ "$bump" == none ]]; then
  log "no feat/fix/perf/breaking commits since ${previous:-the start}: no release"
  : >"$notes_file"
  printf 'release=false\nversion=\ntag=\nprevious=%s\nbump=none\n' "$previous"
  exit 0
fi
log "bump: $bump -> $tag"

{
  if [[ -n "${sections[breaking]}" ]]; then
    printf '## ⚠️ Cambios incompatibles\n\n%s\n' "${sections[breaking]}"
  fi
  [[ -n "${sections[feat]}" ]] && printf '## Novedades\n\n%s\n' "${sections[feat]}"
  [[ -n "${sections[fix]}" ]] && printf '## Correcciones\n\n%s\n' "${sections[fix]}"
  [[ -n "${sections[perf]}" ]] && printf '## Rendimiento\n\n%s\n' "${sections[perf]}"
  [[ -n "${sections[other]}" ]] && printf '## Otros cambios\n\n%s\n' "${sections[other]}"
  printf '## Instalación\n\n'
  printf 'Descarga `Argos-%s.apk` en el teléfono y ábrelo (permite "instalar apps desconocidas" para tu navegador). ' "$version"
  printf 'Las actualizaciones se instalan encima. Requiere Android 8.0+.\n\n'
  if [[ -n "$previous" ]]; then
    printf '**Changelog completo**: https://github.com/%s/compare/%s...%s\n' "$repo" "$previous" "$tag"
  fi
} >"$notes_file"

printf 'release=true\nversion=%s\ntag=%s\nprevious=%s\nbump=%s\n' "$version" "$tag" "$previous" "$bump"
