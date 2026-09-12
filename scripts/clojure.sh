#!/usr/bin/env -S -i PATH=/usr/bin:/bin LANG=C.UTF-8 LC_ALL=C.UTF-8 /usr/bin/bash --noprofile --norc
set -euo pipefail
umask 022

readonly script_dir="$(cd -- "$(/usr/bin/dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly repository_root="$(cd -- "${script_dir}/.." && pwd -P)"
readonly lock_path="${repository_root}/toolchain.lock.json"
readonly lock_reader="${script_dir}/toolchain_lock.py"

fail() {
  /usr/bin/printf 'SENTINEL_CLJ Clojure launcher failed: %s\n' "$1" >&2
  exit 1
}

lock_value() {
  /usr/bin/python3 -I "${lock_reader}" "${lock_path}" get "$1" "$2"
}

java_directory="$(lock_value java installDirectory)" || fail "could not read Java install directory lock"
cli_directory="$(lock_value clojure-cli installDirectory)" || fail "could not read Clojure install directory lock"
readonly java_directory cli_directory
readonly java_root="${repository_root}/.toolchain/${java_directory}"
readonly cli_root="${repository_root}/.toolchain/${cli_directory}"
cli_relative_path="$(lock_value clojure-cli executable.relativePath)" || fail "could not read Clojure executable lock"
readonly cli_relative_path
readonly cli_executable="${cli_root}/${cli_relative_path}"

/usr/bin/python3 -I "${lock_reader}" "${lock_path}" verify-tree java "${java_root}" ||
  fail "Java installed tree is absent or does not match toolchain.lock.json"
/usr/bin/python3 -I "${lock_reader}" "${lock_path}" verify-tree clojure-cli "${cli_root}" ||
  fail "Clojure CLI installed tree is absent or does not match toolchain.lock.json"

/usr/bin/mkdir -p -- "${repository_root}/.toolchain/runtime-home" "${repository_root}/.toolchain/clj-config"
/usr/bin/chmod 0700 -- "${repository_root}/.toolchain/runtime-home" "${repository_root}/.toolchain/clj-config"

if [[ "${1-}" == "--offline" ]]; then
  shift
  profile="${1-}"
  shift || true
  case "${profile}" in
    -M:test)
      exec "${script_dir}/test.sh" "$@"
      ;;
    -M:test-runner-self)
      [[ "$#" -eq 0 ]] || fail "test-runner-self does not accept arguments"
      exec "${script_dir}/test.sh" --smoke
      ;;
    *)
      fail "offline mode requires exactly -M:test or -M:test-runner-self"
      ;;
  esac
fi

if [[ "${1-}" == "--cli-version" && "$#" -eq 1 ]]; then
  set -- --version
fi

exec /usr/bin/env -i \
  PATH="${java_root}/bin:/usr/bin:/bin" \
  HOME="${repository_root}/.toolchain/runtime-home" \
  CLJ_CONFIG="${repository_root}/.toolchain/clj-config" \
  LANG=C.UTF-8 \
  LC_ALL=C.UTF-8 \
  JAVA_HOME="${java_root}" \
  "${cli_executable}" "$@"
