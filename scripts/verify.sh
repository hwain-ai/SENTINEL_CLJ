#!/usr/bin/env -S -i PATH=/usr/bin:/bin LANG=C.UTF-8 LC_ALL=C.UTF-8 /usr/bin/bash --noprofile --norc
set -euo pipefail

readonly script_dir="$(cd -- "$(/usr/bin/dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly repository_root="$(cd -- "${script_dir}/.." && pwd -P)"

cd -- "${repository_root}"
./scripts/verify_repository.sh
./scripts/verify-dependencies.sh
/usr/bin/python3 -I -m unittest discover -s tests/bootstrap -p 'test_*.py'
./scripts/clojure.sh --offline -M:test --include-id crap
