# SENTINEL_CLJ

한마디로, 현재 SENTINEL_CLJ는 Clojure 소스를 실행하지 않고 함수별 복잡도와 coverage를 결합해 정확한 CRAP 값을 만드는 native core입니다.

CRAP은 함수가 복잡하면서 테스트 coverage도 낮은지를 함께 나타내는 점수입니다. 이 저장소는 반올림 전 정확한 분수가 8 이하일 때만 통과시킵니다.

## 현재 구현된 범위

|기능|현재 상태|
|---|---|
|Clojure callable 분석|`defn`, `defn-`, multi-arity, type·record·reify·extend method, `fn`, `#()` 지원|
|안전한 source 읽기|reader eval, project data reader, tagged literal, reader conditional, macro expansion과 namespace load 차단|
|복잡도 계산|분기, 조건, 논리 연산, loop, catch, cond·case 계열을 가장 안쪽 callable에만 계산|
|Coverage 결합|범위가 있는 strict form report와 exact-path LCOV line report 지원|
|CRAP 계산|arbitrary-precision integer 기약분수, 소수 12자리 half-even 표시, 정확한 8 경계|
|재현 가능한 정렬|unknown 우선, 정확한 분수 내림차순, UTF-8 byte 기반 tie-break|
|고정 실행 환경|Temurin 17.0.20.1+1, Clojure CLI 1.12.5.1664, Clojure 1.12.0, tools.reader 1.4.2|

현재 [명령 코드](src/sentinel_clj/cli.clj)에는 check·mutation·doctor·history가 있고, [실행 조정 코드](src/sentinel_clj/orchestrator.clj)는 검사 도구 실행과 결과·이력 기록을 연결합니다. 다만 원래 저장소가 보이지 않는 독립 설치, 네트워크 차단 컨테이너의 실제 프로젝트 비교, 통합 SENTINEL·설치 플러그인 연결은 아직 검증 중입니다. 명령이 있다는 사실과 실제 프로젝트 지원 완료를 구분합니다.

## 검증 방법

아래 파일은 셸에 인자로 넘기지 말고 실행 파일 자체를 직접 실행해야 합니다. 그래야 `BASH_ENV`처럼 Bash 시작 전에 실행되는 외부 설정도 차단됩니다.

|순서|직접 실행할 명령|하는 일|
|---:|---|---|
|1|`./scripts/bootstrap-clojure.sh`|고정 JDK와 Clojure CLI archive를 검증하고 저장소 내부에 설치|
|2|`./scripts/verify-dependencies.sh --populate`|고정 Clojure JAR을 내려받아 크기와 SHA-256 검증|
|3|`./scripts/clojure.sh --offline -M:test --include-id crap`|resolver와 network 없이 CRAP test 실행|
|4|`./scripts/verify.sh`|저장소, toolchain, hostile environment, SPEC와 전체 CRAP test 통합 검증|

`/usr/bin/bash scripts/...` 호출은 지원하지 않습니다. 셸 본문이 시작되기 전에 외부 `BASH_ENV`가 실행될 수 있기 때문입니다.

자세한 처리 순서와 coverage 입력 계약은 [아키텍처](docs/architecture.md)에 있습니다.
