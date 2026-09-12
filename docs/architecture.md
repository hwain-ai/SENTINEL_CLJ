---
type: Architecture Decision
status: stable
generated: { by: "process:codex", at: "2026-09-03T00:00:00Z" }
owner: "human:hwain"
stale_after: 2026-12-03
sources:
  - resource: https://clojure.org/releases/tools
    title: Clojure CLI release history
  - resource: https://github.com/clojure/tools.reader
    title: Clojure tools.reader source and API
  - resource: https://github.com/cloverage/cloverage
    title: Cloverage source and LCOV format
  - resource: ../../SENTINEL_SPEC/golden/crap/formula-v1.json
    title: Shared exact CRAP vectors
  - resource: ../../SENTINEL_SPEC/golden/crap/stable-sort-v1.json
    title: Shared row ordering vectors
---

# Clojure CRAP core 아키텍처

한마디로, source byte를 안전한 Clojure form으로 읽고 callable과 coverage를 정확히 연결한 뒤 정수만으로 CRAP을 계산합니다.

## 실제 처리 순서

1. 입력 module path와 UTF-8 source byte를 검증합니다.
2. tools.reader 1.4.2를 `*read-eval*=false` 상태로 사용해 source를 data로만 읽습니다.
3. 각 callable의 의미 기반 ID, 원본 UTF-8 byte 범위와 Cyclomatic Complexity를 만듭니다.
4. config에서 이미 선택한 form 또는 line basis 하나만 받습니다. 실행 중 다른 basis로 바꾸지 않습니다.
5. Coverage의 module path와 source SHA-256이 현재 callable inventory와 정확히 같은지 확인합니다.
6. 실행 unit를 가장 안쪽 callable 하나에만 귀속합니다. 증거가 모호하면 coverage를 추측하지 않고 unknown으로 만듭니다.
7. 정확한 기약분수로 CRAP을 계산하고 반올림 전 값이 8 이하인지 판정합니다.
8. 공통 SPEC 순서로 결과를 정렬합니다.

## Callable과 복잡도

Callable은 독립적으로 CRAP 한 행을 만드는 실행 단위입니다.

|Clojure 형태|행을 만드는 기준|부모와의 관계|
|---|---|---|
|`defn`, `defn-`|arity마다 한 행|각 arity body를 따로 계산|
|`deftype`, `defrecord` method|method arity마다 한 행|type 또는 record가 소유|
|`reify` method|method arity마다 한 행|binding·property 같은 의미 기반 소유자가 구별|
|`extend-type`, `extend-protocol` method|protocol·target type·method·arity마다 한 행|다른 protocol의 같은 이름을 합치지 않음|
|`fn`, `fn*`, `#()`|arity마다 한 행|가장 가까운 callable과 binding·map key·call argument 역할이 소유|

ID에는 line number, source byte 위치, sibling 순번과 익명 callable 본문을 넣지 않습니다. 함수가 위아래로 이동하거나 익명 함수의 구현 내용이 바뀌어도 binding·property·callee 같은 논리적 역할이 같으면 같은 ID를 유지합니다. 같은 이름이 다시 정의됐을 때만 normalized declaration digest로 구별합니다. 의미 기반 descriptor까지 완전히 같은 두 callable은 임의 번호를 붙이지 않고 `identityAmbiguous`로 실패합니다.

CC는 1에서 시작합니다. `if`·`when` 계열, `and`, `or`, `loop`, `catch`는 각각 decision 하나를 더합니다. `cond`, `condp`, `case`, `cond->`, `some->` 계열은 실제 clause 수만큼 더합니다. 인용된 data, macro 정의, `comment`와 child callable body는 부모 CC에 넣지 않습니다.

## Source를 실행하지 않는 경계

Analyzer는 project namespace를 `require`하거나 macro를 확장하지 않습니다. `eval`, source load와 project code 호출도 하지 않습니다.

|입력|처리|
|---|---|
|`#=(...)`|reader 단계에서 `analysisReadError`|
|project custom tag|등록된 project data reader를 부르지 않고 `analysisReadError`|
|`#inst`, `#uuid`|strict allowlist 밖이므로 `analysisReadError`|
|reader conditional|platform branch를 고르지 않고 `readerConditionalUnsupported`|
|존재하지 않는 namespace를 적은 `ns` form|form 자체만 읽으므로 namespace load 0회|
|project macro 호출|일반 list data로 보며 expansion 0회|

Reader가 주는 line과 UTF-16 column은 원본 UTF-8 byte의 half-open 범위로 다시 계산합니다. BOM 3 byte, CRLF 2 byte, 한글과 astral Unicode 문자도 원본 byte 위치에 포함합니다.

## Coverage 입력과 fail-closed 규칙

Form basis는 `sentinel-cloverage-form-v1`로 정규화된 report만 받습니다. 각 unit에는 half-open start byte, end byte와 nonnegative hit count가 있어야 합니다. 같은 range가 중복되거나 callable 경계를 가로지르면 report 오류입니다. 정상 unit는 range가 완전히 들어가는 가장 작은 callable에만 귀속됩니다.

Line basis는 Cloverage 1.2.4가 만드는 LCOV의 `TN`, `SF`, `DA`, `LF`, `LH`, `end_of_record`만 받습니다. 여러 file record가 있어도 project-relative `SF`가 완전히 같은 record 하나만 고릅니다. 절대 경로 추측, suffix matching과 fallback은 없습니다. 같은 line이 독립 callable 둘 이상에 걸치면 관련 callable을 모두 `coverageAmbiguous`로 표시합니다.

두 basis 모두 fresh coverage를 만든 상위 runner가 module path와 current source digest를 provenance로 전달해야 합니다. Core는 digest가 다르면 `coverageStale`, file이 다르면 `coverageModuleMismatch`로 거부합니다. Raw LCOV 자체에는 source digest가 없으므로 fresh 실행 여부를 증명하는 책임은 후속 runner에 있습니다.

## 정확한 CRAP 계산

CC를 복잡도, C를 실행된 unit 수, T를 전체 unit 수라고 하면 계산 전 분자는 `CC² × (T-C)³ + CC × T³`, 분모는 `T³`입니다. 최대공약수로 나눈 기약분수를 저장하고, 계산 전 분자가 `8 × 분모` 이하일 때만 통과합니다.

표시 문자열은 정수 나눗셈으로 소수점 아래 최대 12자리를 만들고 정확히 절반일 때 마지막 자리가 짝수가 되도록 반올림합니다. Binary float, Java decimal과 locale formatter는 사용하지 않습니다. 따라서 17/4는 `4.25`, 1/3은 `0.333333333333`이 됩니다.

## 고정 도구체인

공개 셸 entrypoint는 clean environment shebang을 사용하고 직접 실행합니다. 호출자가 준 `PATH`, `BASH_ENV`, `ENV`, Java option, `HOME`, `CLJ_CONFIG`, proxy와 `CLASSPATH`는 실행 환경으로 전달되지 않습니다.

`toolchain.lock.json`은 archive URL·크기·SHA-256, 설치 폴더, executable SHA-256과 설치 root를 포함한 전체 tree manifest를 고정합니다. `.toolchain`은 현재 사용자만 접근 가능한 mode 0700입니다. 설치 폴더가 조금이라도 이미 존재하면서 manifest가 다르면 자동으로 덮어쓰지 않고 실패합니다.

`dependency-lock.json`은 Clojure 1.12.0, tools.reader 1.4.2, data.json 2.5.1과 두 Clojure transitive JAR의 URL·크기·SHA-256을 고정합니다. Offline test는 Clojure CLI resolver를 호출하지 않고 검증된 classpath와 고정 Java를 직접 사용합니다.

## 로그 책임

이 core는 분석 대상 project에 로그나 state를 쓰지 않습니다. 대신 안정적인 callable ID, source digest, coverage basis와 typed unknown reason을 상위 runner에 반환할 수 있게 설계했습니다. 반복 결함을 찾는 장기 run history는 project별 evidence를 가진 후속 orchestrator가 기록해야 합니다. Core가 전역 로그를 직접 쓰면 서로 다른 project의 경로와 결과가 섞이고 read-only 분석도 write 작업이 되기 때문입니다.

## 현재 경계

- 구현됨: source analyzer, form·LCOV adapter, exact CRAP, canonical decimal, stable sort와 고정 local runtime.
- 후속 작업: project scope 탐색, fresh coverage process 실행, 최종 `sentinel-clj crap` CLI, mutation backend, signed evidence와 repeated-defect history.
- 트레이드오프: line coverage는 범위가 없어서 같은 줄 callable을 일부 계산하지 못합니다. 잘못된 통과보다 명시적인 unknown을 선택합니다.
