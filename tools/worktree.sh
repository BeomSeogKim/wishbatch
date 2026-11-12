#!/usr/bin/env bash
# Manage one Git worktree per task branch for parallel work.
#   add BRANCH [BASE]   create or check out BRANCH (from BASE, default main) under .worktrees/
#   list                list worktrees and their assigned application ports
#   remove BRANCH       remove the worktree and release its port; the branch is kept
set -euo pipefail

BASE_PORT=8200
SLOTS=9

common=$(git rev-parse --path-format=absolute --git-common-dir)
root=$(dirname "$common")
trees="$root/.worktrees"
ports="$trees/ports"

usage() {
  sed -n '2,5p' "$0" >&2
  exit 2
}

slug() {
  printf '%s' "$1" | tr '/' '-'
}

case "${1:-}" in
  add)
    branch=${2:-}
    base=${3:-main}
    [ -n "$branch" ] || usage
    "$root/tools/git-conventions.sh" branch "$branch"
    dir="$trees/$(slug "$branch")"
    if [ -e "$dir" ]; then
      echo "worktree: $dir already exists" >&2
      exit 1
    fi
    mkdir -p "$trees"
    touch "$ports"
    if git -C "$root" rev-parse --verify --quiet "refs/heads/$branch" >/dev/null; then
      git -C "$root" worktree add "$dir" "$branch"
    else
      if ! git -C "$root" rev-parse --verify --quiet "$base^{commit}" >/dev/null; then
        echo "worktree: base '$base' has no commit yet" >&2
        exit 1
      fi
      git -C "$root" worktree add -b "$branch" "$dir" "$base"
    fi
    port=""
    for slot in $(seq 1 "$SLOTS"); do
      candidate=$((BASE_PORT + slot))
      if ! grep -q " $candidate\$" "$ports"; then
        port=$candidate
        break
      fi
    done
    echo "worktree: $dir"
    if [ -n "$port" ]; then
      echo "$(slug "$branch") $port" >> "$ports"
      echo "worktree: run the application here with SERVER_PORT=$port"
    else
      echo "worktree: no free port; build and test here, but do not run the application"
    fi
    ;;
  list)
    git -C "$root" worktree list
    if [ -s "$ports" ]; then
      echo "ports:"
      sed 's/^/  /' "$ports"
    fi
    ;;
  remove)
    branch=${2:-}
    [ -n "$branch" ] || usage
    dir="$trees/$(slug "$branch")"
    git -C "$root" worktree remove "$dir"
    if [ -f "$ports" ]; then
      grep -v "^$(slug "$branch") " "$ports" > "$ports.tmp" || true
      mv "$ports.tmp" "$ports"
    fi
    echo "worktree: removed $dir; branch $branch is kept"
    ;;
  *)
    usage
    ;;
esac
