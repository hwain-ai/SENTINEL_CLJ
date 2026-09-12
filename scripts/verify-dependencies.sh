#!/usr/bin/env -S -i PATH=/usr/bin:/bin LANG=C.UTF-8 LC_ALL=C.UTF-8 /usr/bin/bash --noprofile --norc
set -euo pipefail

readonly script_dir="$(cd -- "$(/usr/bin/dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly repository_root="$(cd -- "${script_dir}/.." && pwd -P)"

if [[ "${1-}" == "--populate" && "$#" -eq 1 ]]; then
  exec "${script_dir}/bootstrap-dependencies.sh"
fi

if [[ "$#" -ne 0 ]]; then
  /usr/bin/printf 'usage: ./scripts/verify-dependencies.sh [--populate]\n' >&2
  exit 2
fi

/usr/bin/python3 -I "${script_dir}/dependency_lock.py" \
  "${repository_root}/dependency-lock.json" verify-directory \
  "${repository_root}/.toolchain/dependencies"
/usr/bin/printf 'SENTINEL_CLJ dependency verification passed\n'
