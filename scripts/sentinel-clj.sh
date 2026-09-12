#!/usr/bin/env -S -i PATH=/usr/bin:/bin LANG=C.UTF-8 LC_ALL=C.UTF-8 /usr/bin/bash --noprofile --norc
set -euo pipefail
umask 077

readonly script_dir="$(cd -- "$(/usr/bin/dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly repository_root="$(cd -- "${script_dir}/.." && pwd -P)"
readonly dependency_root="${repository_root}/.toolchain/dependencies"
dependency_class_path="$(/usr/bin/python3 -I "${script_dir}/dependency_lock.py" \
  "${repository_root}/dependency-lock.json" class-path "${dependency_root}")" || exit 1
readonly dependency_class_path
readonly class_path="${repository_root}/src:${dependency_class_path}"

exec "${script_dir}/java.sh" "-Dsentinel.clj.install-root=${repository_root}" \
  -cp "${class_path}" clojure.main -m sentinel-clj.cli "$@"
