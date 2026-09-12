#!/usr/bin/env -S -i PATH=/usr/bin:/bin LANG=C.UTF-8 LC_ALL=C.UTF-8 /usr/bin/bash --noprofile --norc
set -euo pipefail
umask 022

readonly script_dir="$(cd -- "$(/usr/bin/dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly repository_root="$(cd -- "${script_dir}/.." && pwd -P)"
readonly lock_path="${repository_root}/toolchain.lock.json"
readonly lock_reader="${script_dir}/toolchain_lock.py"

fail() {
  /usr/bin/printf 'SENTINEL_CLJ Java launcher failed: %s\n' "$1" >&2
  exit 1
}

lock_value() {
  /usr/bin/python3 -I "${lock_reader}" "${lock_path}" get java "$1"
}

install_directory="$(lock_value installDirectory)" || fail "could not read Java install directory lock"
executable_relative_path="$(lock_value executable.relativePath)" || fail "could not read Java executable lock"
readonly install_directory executable_relative_path
readonly install_root="${repository_root}/.toolchain/${install_directory}"
readonly java_executable="${install_root}/${executable_relative_path}"

/usr/bin/python3 -I "${lock_reader}" "${lock_path}" verify-tree java "${install_root}" ||
  fail "installed tree is absent or does not match toolchain.lock.json"

exec /usr/bin/env -i \
  PATH="${install_root}/bin:/usr/bin:/bin" \
  HOME="${repository_root}/.toolchain/runtime-home" \
  LANG=C.UTF-8 \
  LC_ALL=C.UTF-8 \
  JAVA_HOME="${install_root}" \
  "${java_executable}" "$@"
