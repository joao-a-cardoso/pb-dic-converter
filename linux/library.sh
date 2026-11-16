# linux/library.sh — shared functions for the chain-* scripts in linux/.
# Sourced, not executed:  source "$(dirname "$(realpath "$0")")/library.sh"
#
# Before using the functions, a chain script sets:
#   TOOL   name used in messages (default: the script's file name)
#   STEPS  number of steps, for the [n/N] messages
#
# The chain functions set these globals:
#   CONFIG      absolute path of the config file
#   CONFIG_ARG  the config path as given on the command line (for messages)
#   DEFS        array of "--def <name>=<value>" arguments, passed to every step
#   TSV         temporary TSV file, removed when the script exits

set -eo pipefail

TOOL="${TOOL:-$(basename "$0")}"
_STEP=0

# die <message> — error to stderr, exit 1
die() {
  echo "$TOOL: Error: $*" >&2
  exit 1
}

# usage — the usage line of the chain scripts
usage() {
  echo "Usage: $TOOL <config-file> [--def <name>=<value> ...]"
}

# parse_chain_args "$@" — help, config file and --def arguments
#   no arguments  -> usage to stderr, exit 1
#   -h / --help   -> usage to stdout, exit 0
parse_chain_args() {
  if [ $# -eq 0 ]; then
    usage >&2
    exit 1
  fi
  if [ "$1" = "-h" ] || [ "$1" = "--help" ]; then
    usage
    exit 0
  fi

  [ -f "$1" ] || die "config file not found: $1"
  CONFIG=$(realpath "$1")
  CONFIG_ARG="$1"
  shift

  DEFS=()
  while [ $# -gt 0 ]; do
    if [ "$1" = "--def" ] && [ $# -ge 2 ]; then
      DEFS+=(--def "$2")
      shift 2
    else
      echo "$TOOL: Error: unexpected argument '$1'" >&2
      usage >&2
      exit 1
    fi
  done
}

# goto_project_root — run from the folder above linux/, where the configs'
# relative paths start
goto_project_root() {
  cd "$(dirname "$(dirname "$(realpath "$0")")")"
}

# require_section <namespace> <keys hint> — the config must have <namespace>.* keys
require_section() {
  grep -qE "^[[:space:]]*$1\." "$CONFIG" \
    || die "$CONFIG_ARG has no $1.* keys ($2)"
}

# make_temp_tsv — temporary TSV for the first step, removed on exit. Writing it
# first means an existing .xdxf is never overwritten if the first step fails.
make_temp_tsv() {
  TSV=$(mktemp --suffix=.tsv)
  trap 'rm -f "$TSV"' EXIT
}

# step <description> — "[n/N] description" to stderr
step() {
  _STEP=$((_STEP + 1))
  echo "$TOOL: [$_STEP/$STEPS] $*" >&2
}

# done_msg — final message
done_msg() {
  echo "$TOOL: done" >&2
}
