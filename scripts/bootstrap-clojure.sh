#!/usr/bin/env -S -i PATH=/usr/bin:/bin LANG=C.UTF-8 LC_ALL=C.UTF-8 /usr/bin/bash --noprofile --norc
set -euo pipefail
umask 022

readonly script_dir="$(cd -- "$(/usr/bin/dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly repository_root="$(cd -- "${script_dir}/.." && pwd -P)"
readonly lock_path="${repository_root}/toolchain.lock.json"
readonly lock_reader="${script_dir}/toolchain_lock.py"
readonly toolchain_root="${repository_root}/.toolchain"
readonly download_root="${toolchain_root}/downloads"

stage_root=""

fail() {
  /usr/bin/printf 'SENTINEL_CLJ toolchain bootstrap failed: %s\n' "$1" >&2
  exit 1
}

cleanup() {
  if [[ -n "${stage_root}" && -d "${stage_root}" ]]; then
    /usr/bin/rm -rf -- "${stage_root}"
  fi
}

lock_value() {
  /usr/bin/python3 -I "${lock_reader}" "${lock_path}" get "$1" "$2"
}

download_archive() {
  local tool="$1"
  local archive_path="$2"
  local temporary_archive
  local url

  if [[ -e "${archive_path}" ]]; then
    /usr/bin/python3 -I "${lock_reader}" "${lock_path}" verify-archive "${tool}" "${archive_path}" ||
      fail "cached ${tool} archive does not match toolchain.lock.json"
    return
  fi
  url="$(lock_value "${tool}" archive.url)"
  temporary_archive="${archive_path}.partial.$$"
  [[ ! -e "${temporary_archive}" ]] || fail "temporary archive already exists: ${temporary_archive}"
  /usr/bin/curl --fail --location --proto '=https' --tlsv1.2 --silent --show-error \
    --output "${temporary_archive}" "${url}" || fail "could not download ${tool} archive"
  /usr/bin/python3 -I "${lock_reader}" "${lock_path}" verify-archive "${tool}" "${temporary_archive}" ||
    fail "downloaded ${tool} archive does not match toolchain.lock.json"
  /usr/bin/mv -T --no-clobber -- "${temporary_archive}" "${archive_path}" ||
    fail "could not publish ${tool} archive"
}

extract_archive() {
  local tool="$1"
  local archive_path="$2"
  local extraction_root="$3"
  local expected_root

  expected_root="$(lock_value "${tool}" archive.rootDirectory)"
  /usr/bin/mkdir -m 0755 -- "${extraction_root}"
  /usr/bin/tar --extract --gzip --file "${archive_path}" --directory "${extraction_root}" ||
    fail "could not extract ${tool} archive"
  [[ -d "${extraction_root}/${expected_root}" && ! -L "${extraction_root}/${expected_root}" ]] ||
    fail "${tool} archive has an unexpected root"
  [[ "$(/usr/bin/find "${extraction_root}" -mindepth 1 -maxdepth 1 -printf '%f\n')" == "${expected_root}" ]] ||
    fail "${tool} archive contains more than its locked root"
}

prepare_java() {
  local source_root="$1"
  local prepared_root="$2"
  /usr/bin/mv -T -- "${source_root}" "${prepared_root}"
}

prepare_clojure_cli() {
  local source_root="$1"
  local prepared_root="$2"

  /usr/bin/mkdir -m 0755 -p -- "${prepared_root}/bin" "${prepared_root}/libexec" "${prepared_root}/share/man/man1"
  /usr/bin/install -m 0644 -- "${source_root}/deps.edn" "${source_root}/example-deps.edn" \
    "${source_root}/tools.edn" "${prepared_root}/"
  /usr/bin/install -m 0644 -- "${source_root}/exec.jar" \
    "${source_root}/clojure-tools-1.12.5.1664.jar" "${prepared_root}/libexec/"
  /usr/bin/install -m 0644 -- "${source_root}/clojure.1" "${source_root}/clj.1" \
    "${prepared_root}/share/man/man1/"
  /usr/bin/install -m 0755 -- "${source_root}/clojure" "${prepared_root}/bin/clojure"
  /usr/bin/install -m 0755 -- "${source_root}/clj" "${prepared_root}/bin/clj"
  /usr/bin/sed -i \
    's|^install_dir=PREFIX$|script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" \&\& pwd -P)"\ninstall_dir="$(cd -- "${script_dir}/.." \&\& pwd -P)"|' \
    "${prepared_root}/bin/clojure"
  /usr/bin/sed -i \
    's|^bin_dir=BINDIR$|bin_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" \&\& pwd -P)"|' \
    "${prepared_root}/bin/clj"
}

install_tool() {
  local tool="$1"
  local archive_name="$2"
  local install_directory
  local install_root
  local archive_path
  local extraction_root
  local source_root
  local prepared_root

  install_directory="$(lock_value "${tool}" installDirectory)"
  install_root="${toolchain_root}/${install_directory}"
  if [[ -e "${install_root}" ]]; then
    /usr/bin/python3 -I "${lock_reader}" "${lock_path}" verify-tree "${tool}" "${install_root}" ||
      fail "pre-existing ${tool} installed tree is partial or modified"
    return
  fi
  archive_path="${download_root}/${archive_name}"
  download_archive "${tool}" "${archive_path}"
  extraction_root="${stage_root}/${tool}-archive"
  extract_archive "${tool}" "${archive_path}" "${extraction_root}"
  source_root="${extraction_root}/$(lock_value "${tool}" archive.rootDirectory)"
  prepared_root="${stage_root}/${install_directory}"
  if [[ "${tool}" == "java" ]]; then
    prepare_java "${source_root}" "${prepared_root}"
  else
    prepare_clojure_cli "${source_root}" "${prepared_root}"
  fi
  /usr/bin/python3 -I "${lock_reader}" "${lock_path}" verify-tree "${tool}" "${prepared_root}" ||
    fail "prepared ${tool} installed tree does not match toolchain.lock.json"
  /usr/bin/mv -T --no-clobber -- "${prepared_root}" "${install_root}" ||
    fail "could not publish ${tool} installed tree"
  [[ ! -e "${prepared_root}" ]] || fail "another process published ${tool} first"
}

trap cleanup EXIT
/usr/bin/python3 -I "${lock_reader}" "${lock_path}" get java version >/dev/null ||
  fail "toolchain.lock.json is invalid"
if [[ -e "${toolchain_root}" && ( ! -d "${toolchain_root}" || -L "${toolchain_root}" ) ]]; then
  fail ".toolchain must be a real directory"
fi
if [[ -e "${download_root}" && ( ! -d "${download_root}" || -L "${download_root}" ) ]]; then
  fail ".toolchain/downloads must be a real directory"
fi
/usr/bin/mkdir -m 0700 -p -- "${toolchain_root}" "${download_root}" \
  "${toolchain_root}/runtime-home" "${toolchain_root}/clj-config"
/usr/bin/chmod 0700 -- "${toolchain_root}" "${download_root}" \
  "${toolchain_root}/runtime-home" "${toolchain_root}/clj-config"
stage_root="$(/usr/bin/mktemp -d "${toolchain_root}/.bootstrap.XXXXXXXX")"
[[ "${stage_root}" == "${toolchain_root}/.bootstrap."* ]] || fail "unsafe staging directory"

install_tool java temurin-17.0.20.1+1.tar.gz
install_tool clojure-cli clojure-tools-1.12.5.1664.tar.gz

/usr/bin/printf 'SENTINEL_CLJ toolchain ready: Temurin %s, Clojure CLI %s\n' \
  "$(lock_value java version)" "$(lock_value clojure-cli version)"
