#!/bin/sh -e

SCRIPT_DIR="$(dirname "$(readlink -f "$0")")"

find "$1" -name '*.json' | xargs -P0 -n1 "$SCRIPT_DIR/post-process-bundle.sh"
