---
type: validation-reference
updated: 2026-09-11
status: in-progress
owner: Codex
related:
  - https://github.com/hwain-hwang/SENTINEL/blob/main/docs/exec-plans/active/2026-09-sentinel-unified-entry.md
---

# Clojure 고정 입력과 통합 연결 준비

기존 실행 환경·의존성·clj-mutate의 설치 내용이 잠금 파일과 일치함을 확인했다. 독립 설치, 컨테이너 실행과 실제 프로젝트 비교는 아직 하지 않았다.

## 확인한 범위

|대상|현재 확인|
|---|---|
|JDK, Java 실행 환경|Temurin 17.0.20.1+1의 고정 전체 트리·실행 파일 지문 일치|
|Clojure 명령 도구|1.12.5.1664의 고정 전체 트리·실행 파일 지문 일치|
|실행 의존성|잠긴 JAR 5개와 deps.edn의 내용·크기·필요 권한 일치|
|변이 도구 clj-mutate|고정 commit e27dd5df63c4efdd66438587d1c5f49e73661b69의 전체 파일·권한·내용 일치|

기존 검증기 세 개를 읽은 뒤 파일을 대조하는 명령 네 개를 실행했고 모두 종료 0이었다. JDK나 Clojure 코드를 실행한 것이 아니며 다운로드·설치·기존 파일 변경은 없다. 명령·실제 응답·검증기 지문은 /tmp/sentinel-clojure-preflight.cPsL81Mj/existing-input-verification.json에 보존한다.

## 다음 연결에서 확인할 것

1. 독립 배치: [현재 시작 스크립트](../scripts/sentinel-clj.sh)는 설치 폴더의 src·scripts·잠금·의존성을 함께 사용한다. [변이 도구 연결](../scripts/clj-mutate-bridge.sh)은 third_party/clj-mutate와 진행 기록 경로도 필요하다. 원래 저장소가 보이지 않는 설치 배치에서 이 경로를 확인해야 한다.
2. 실제 프로젝트 비교: [현재 CLI](../src/sentinel_clj/cli.clj)에 check·mutation·history가 있고, [실행 조정 코드](../src/sentinel_clj/orchestrator.clj)는 검사·결과 기록을 연결한다. 명령 구현의 존재를 실제 프로젝트 지원 완료로 세지 않는다. 원래 빌드·전체 테스트와 검사 결과를 같은 입력으로 비교해야 한다.
3. 공통 실행·설치 플러그인: 기존 SENTINEL이 컨테이너 제한·중단·정리를 맡는 방향을 그대로 적용한다. 설치 형식·프로젝트 입력·격리 검증이 끝나기 전에는 운영 허용이나 호스트 활성화 완료로 표시하지 않는다.

현재 README의 'CLI·변이 검사·이력이 아직 없다'는 문장은 위 소스와 달라 바로잡았다. 새 실행 기능을 추가한 것은 아니다. 전체 진행 상태는 [통합 실행 계획](https://github.com/hwain-hwang/SENTINEL/blob/main/docs/exec-plans/active/2026-09-sentinel-unified-entry.md)에 있다.

## 복사만으로 설치 완료가 아닌 이유

읽기 전용 독립 검토와 소스 대조에서 다음 조건을 확인했다.

- 현재 검사 명령은 Java로 Clojure를 직접 실행하므로 별도 Clojure CLI 호출은 필요하지 않다. 다만 공개 프로젝트의 원래 빌드 도구까지 불필요하다는 뜻은 아니다.
- 반대로 검증용 Python은 필요하다. 시작 스크립트뿐 아니라 [backend 검증 코드](../src/sentinel_clj/mutation/backend_lock.clj)도 /usr/bin/python3를 직접 호출한다. 현재 고정 이미지에서 이 실행 경로와 표준 라이브러리를 확인하지 않았다.
- 원래 JDK의 내부 링크와 공통 봉인 입력의 링크 금지 조건을 함께 만족해야 한다. [의존성 검증기](../scripts/dependency_lock.py)의 폴더 권한0700과 [공통 설치기](https://github.com/hwain-hwang/SENTINEL/blob/main/src/sentinel/content_root.py)의 봉인 권한0500도 그대로는 맞지 않는다. 읽기 전용 mount를 해제하자는 뜻은 아니다.
- 자체 소스·시작 스크립트 전체 목록과 복사 독립성, 설치 경로가 반영된 프로젝트 설정이 필요하다.

따라서 단일 설치 폴더와 실행 환경을 나눈 설치 폴더는 아직 비교 후보다. 기존 Java/Python 준비물을 맞는 범위에서 재사용하되, 새 링크 허용이나 검증 생략으로 연결하지 않는다. 상세 근거와 최초 추천의 정정은 /tmp/sentinel-clojure-preflight.cPsL81Mj/install-boundary-review.md에 있다. Clojure 설치·프로젝트·컨테이너 실행은 아직 하지 않았다.

## 변경이력

- 2026-09-11 | 독립 설치의 실행 의존·권한 공백 확인 | 변경: 고정 Python 호출, JDK 링크와 의존성 폴더 권한 충돌, 자체 전체 목록의 미완료를 구분 | 검증: 독립 읽기 전용 검토 두 응답과 실제 launcher·backend 검증 소스 대조. 설치·실행·다운로드·기존 검사 변경 없음.

- 2026-09-10 | Clojure 입력 재사용 확인 | 변경: 현재 명령 구현과 독립 실행 미완료를 구분하고 오래된 README 설명 정정 | 검증: 읽기 전용 고정 입력 검사 4개 종료 0. 실제 SDK·프로젝트 실행은 없음.
