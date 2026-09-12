#!/usr/bin/env -S -i PATH=/usr/bin:/bin LANG=C.UTF-8 LC_ALL=C.UTF-8 /usr/bin/bash --noprofile --norc
set -euo pipefail
umask 077

readonly script_dir="$(cd -- "$(/usr/bin/dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly repository_root="$(cd -- "${script_dir}/.." && pwd -P)"
readonly dependency_root="${repository_root}/.toolchain/dependencies"
dependency_class_path="$(/usr/bin/python3 -I "${script_dir}/dependency_lock.py" \
  "${repository_root}/dependency-lock.json" class-path "${dependency_root}")" || exit 1
readonly dependency_class_path
readonly project_root="$(pwd -P)"
readonly class_path="${project_root}/src:${project_root}/test:${project_root}/test-resources:${project_root}/spec:${repository_root}/src:${dependency_class_path}"

exec "${script_dir}/java.sh" -cp "${class_path}" clojure.main \
  -m sentinel-clj.runner.clojure-test-events "$@"
