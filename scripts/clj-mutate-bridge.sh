#!/usr/bin/env -S -i PATH=/usr/bin:/bin LANG=C.UTF-8 LC_ALL=C.UTF-8 S=${SCLJ_PROGRESS} /usr/bin/bash --noprofile --norc
set -euo pipefail
umask 077

readonly script_dir="$(cd -- "$(/usr/bin/dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly repository_root="$(cd -- "${script_dir}/.." && pwd -P)"
readonly dependency_root="${repository_root}/.toolchain/dependencies"
if ! /usr/bin/python3 -I "${script_dir}/backend_lock.py" \
  "${repository_root}/backend.lock.json" "${repository_root}/third_party/clj-mutate" >/dev/null; then
  exit 5
fi
dependency_class_path="$(/usr/bin/python3 -I "${script_dir}/dependency_lock.py" \
  "${repository_root}/dependency-lock.json" class-path "${dependency_root}")" || exit 1
readonly dependency_class_path
readonly class_path="${repository_root}/src:${repository_root}/third_party/clj-mutate/src:${dependency_class_path}"

[[ -n "${S-}" ]] || exit 6

exec "${script_dir}/java.sh" "-Dsentinel.clj.progress=${S}" -cp "${class_path}" clojure.main \
  -m sentinel-clj.mutation.companion "$@"
