#!/usr/bin/env -S -i PATH=/usr/bin:/bin LANG=C.UTF-8 LC_ALL=C.UTF-8 /usr/bin/bash --noprofile --norc
set -euo pipefail
umask 022

readonly script_dir="$(cd -- "$(/usr/bin/dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly repository_root="$(cd -- "${script_dir}/.." && pwd -P)"
readonly dependency_root="${repository_root}/.toolchain/dependencies"
dependency_class_path="$(/usr/bin/python3 -I "${script_dir}/dependency_lock.py" \
  "${repository_root}/dependency-lock.json" class-path "${dependency_root}")" || exit 1
readonly dependency_class_path
readonly source_class_path="${repository_root}/src:${repository_root}/test:${repository_root}/test-resources"
readonly class_path="${source_class_path}:${dependency_class_path}"

if [[ "${1-}" == "--smoke" && "$#" -eq 1 ]]; then
  exec "${script_dir}/java.sh" -cp "${class_path}" clojure.main -e \
    '(require (quote clojure.tools.reader)) (assert (= "1.12.0" (clojure-version))) (println "SENTINEL_CLJ smoke passed")'
fi

[[ "$#" -eq 0 || ( "$#" -eq 2 && "$1" == "--include-id" && "$2" == "crap" ) ]] || {
  /usr/bin/printf 'usage: ./scripts/test.sh [--smoke | --include-id crap]\n' >&2
  exit 2
}
exec "${script_dir}/java.sh" -cp "${class_path}" clojure.main -m sentinel-clj.test-runner "$@"
