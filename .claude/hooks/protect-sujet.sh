#!/usr/bin/env bash
# PreToolUse (Edit/Write) — sujet/ est le référentiel de correction de la phase
# en cours (sujet + grille d'audit) : il ne doit jamais être modifié.
set -euo pipefail

input="$(cat)"
file_path="$(printf '%s' "$input" | grep -o '"file_path"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | sed -E 's/.*:[[:space:]]*"([^"]*)"/\1/')"

case "$file_path" in
  */sujet/*|sujet/*)
    echo "Refus : '$file_path' fait partie de sujet/ (sujet + grille d'audit de référence) et ne doit jamais être modifié." >&2
    exit 2
    ;;
esac

exit 0
