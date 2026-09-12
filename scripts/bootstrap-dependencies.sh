#!/usr/bin/env -S -i PATH=/usr/bin:/bin LANG=C.UTF-8 LC_ALL=C.UTF-8 /usr/bin/bash --noprofile --norc
set -euo pipefail
umask 022

readonly script_dir="$(cd -- "$(/usr/bin/dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly repository_root="$(cd -- "${script_dir}/.." && pwd -P)"
readonly lock_path="${repository_root}/dependency-lock.json"
readonly lock_reader="${script_dir}/dependency_lock.py"
readonly toolchain_root="${repository_root}/.toolchain"
readonly dependency_root="${repository_root}/.toolchain/dependencies"

fail() {
  /usr/bin/printf 'SENTINEL_CLJ dependency bootstrap failed: %s\n' "$1" >&2
  exit 1
}

if [[ -e "${toolchain_root}" && ( ! -d "${toolchain_root}" || -L "${toolchain_root}" ) ]]; then
  fail ".toolchain must be a real directory"
fi
if [[ -e "${dependency_root}" && ( ! -d "${dependency_root}" || -L "${dependency_root}" ) ]]; then
  fail ".toolchain/dependencies must be a real directory"
fi
/usr/bin/mkdir -m 0700 -p -- "${toolchain_root}" "${dependency_root}"
/usr/bin/chmod 0700 -- "${toolchain_root}" "${dependency_root}"
while IFS=$'\t' read -r file_name url size_bytes digest; do
  artifact_path="${dependency_root}/${file_name}"
  if [[ -e "${artifact_path}" ]]; then
    /usr/bin/python3 -I "${lock_reader}" "${lock_path}" verify-file "${file_name}" "${artifact_path}" ||
      fail "pre-existing ${file_name} is partial or modified"
    continue
  fi
  temporary_path="${artifact_path}.partial.$$"
  [[ ! -e "${temporary_path}" ]] || fail "temporary dependency already exists: ${temporary_path}"
  /usr/bin/curl --fail --location --proto '=https' --tlsv1.2 --silent --show-error \
    --output "${temporary_path}" "${url}" || fail "could not download ${file_name}"
  /usr/bin/python3 -I "${lock_reader}" "${lock_path}" verify-file "${file_name}" "${temporary_path}" ||
    fail "downloaded ${file_name} does not match dependency-lock.json"
  /usr/bin/mv -T --no-clobber -- "${temporary_path}" "${artifact_path}" ||
    fail "could not publish ${file_name}"
done < <(/usr/bin/python3 -I "${lock_reader}" "${lock_path}" manifest)

/usr/bin/python3 -I "${lock_reader}" "${lock_path}" verify-directory "${dependency_root}" ||
  fail "installed dependency directory does not match dependency-lock.json"
/usr/bin/printf 'SENTINEL_CLJ dependencies ready\n'
