# 구현 노트

## 1. 구조 (협약 6장)

`cli`, `server` → `engine` → `sql` / `storage` / `index`. cli와 server는 `DbEngine.execute`만 부릅니다.

| 패키지 | 역할 |
|---|---|
| `sql` | 글자 쪼개기(`SqlTokenizer`) → 문장 만들기(`SqlParser`). 문법 오류는 `SqlSyntaxException` |
| `storage` | 스키마 읽기, 행 인코딩, 파일 읽기·쓰기 (`TableFile`) |
| `index` | 메모리 B+ 트리 (`BPlusTree`) |
| `engine` | 문장 검증, 락, 인덱스·파일 조합 (`DbEngine`) |
| `json` | 응답 만들기·요청 읽기 (협약 5장에 없던 새 패키지) |
| `cli` | SQL 파일 나누기, 표 출력, 실행 |
| `server` | HTTP 서버, 워커 풀, 옵션 |
| `bench` | 벤치마크 |

## 2. SQL 처리

- 키워드는 대소문자 구분 없음, 컬럼·테이블 이름은 소문자만 (대문자는 UNKNOWN_*).
- 작은따옴표 문자열은 `''`으로 따옴표를 씁니다. `--` 줄 주석은 문자열 밖에서만 주석입니다.
- 정수는 `Long`, 범위를 넘으면 `BigInteger`로 담고 엔진에서 `INVALID_VALUE`로 거릅니다.
- 한 번에 한 문장만, 끝에 `;` 필수. 예약어는 이름으로 쓸 수 없습니다.
- 오류 메시지는 `[함수이름] 설명 (N번째 글자)` 형식입니다.

## 3. 값 검사 (엔진)

- INSERT 정수는 0 ~ 2147483647. 문자열은 UTF-8 **바이트** 기준 최대 길이.
- INSERT에 `id`를 쓰면, 컬럼이 빠지거나 겹치거나 개수가 다르면 `INVALID_VALUE`.
- WHERE 음수 정수는 오류가 아니라 빈 결과, int 최대를 넘으면 `INVALID_VALUE`.

## 4. B+ 트리

- 순서(order) 64 (키 최대 64개, 최소 3 허용). 리프 분할은 가운데 키를 부모에 **복사**, 내부 노드 분할은 가운데 키를 부모로 **올림**.
- 리프끼리 `next`로 연결. 같은 키를 두 번 넣으면 `IllegalArgumentException`.
- 삭제·병합·재분배 없음 (DELETE 미지원). 스레드 안전하지 않음 → 엔진이 테이블 락으로 감쌉니다.
- 인덱스는 메모리에만 있고 파일에 저장하지 않습니다. 시작할 때 `.tbl`을 훑어 다시 만듭니다. (100만 건 약 0.2초)

## 5. 엔진과 락

- 테이블마다 `TableState` (스키마, 트리, 다음 id, `ReentrantReadWriteLock`).
- INSERT는 쓰기 락 안에서 검증 → id 확보 → 파일 추가 → 트리 삽입 → id 증가. 트리 삽입이 실패하면 `rollbackAppend`로 파일을 되돌리고, 되돌리기도 실패하면 그 테이블을 "깨짐"으로 표시해 재시작 전까지 막습니다.
- SELECT는 읽기 락. `WHERE id = 정수`면 트리 → `readRowAt`(`AccessPath.INDEX`), 그 외는 전체 스캔(`FULL_SCAN`).
- 서로 다른 테이블은 서로 기다리지 않습니다. 락 순서 문제는 한 문장이 한 테이블만 만지므로 생기지 않습니다.

## 6. 서버

- JDK `HttpServer` + 직접 만든 `DbWorkerPool` (`ArrayBlockingQueue`, 고정 워커, 작업에서 예외가 나도 워커 유지).
- 큐 초과 처리 방식 : 풀이 거절 처리기를 부르면 수신 스레드가 그 요청을 **그 자리에서** 처리하되, `ThreadLocal` 표시로 엔진은 건드리지 않고 `503 SERVER_BUSY`만 씁니다. (수신 스레드가 짧게 응답을 쓰는 동안 잠깐 멈춘다는 한계가 있음)
- 종료 순서 : 새 요청 중단(`stop(1)`) → 풀 `shutdown` → 최대 10초 `awaitTermination` → 엔진 닫기.

## 7. 협약과 달라진 점 (AI 자기 보고, 협약 15장)

1. 5장에 없던 `json` 패키지를 만들었습니다. (`JsonWriter`, `JsonReader`, `JsonFormatException`) 외부 라이브러리를 쓰지 않으려고 직접 썼습니다.
2. HTTP 응답에 `404 NOT_FOUND`, `405 METHOD_NOT_ALLOWED`, `413 PAYLOAD_TOO_LARGE`를 추가했습니다. (협약은 400/500/503만 정의)
3. cli는 `;`만 있는 빈 문장을 건너뜁니다. 오류는 stderr, 성공은 stdout.
4. `Content-Type`을 검사하지 않습니다.
5. 협약의 이름에 더한 공개 이름 : `DbEngine.executeFullScan`, `DbEngine.executeStatement` (벤치마크용 강제 전체 스캔 / 파싱 없이 실행), `TableFile.rollbackAppend`, `DbWorkerPool` 3인자 생성자(거절 처리기).
6. 테스트 전용으로 `DbEngine(Path, Runnable)` 패키지 전용 생성자와 `BPlusTree.collectKeysByLeafChain`, `hasEqualLeafDepth`를 두었습니다.
7. 테스트는 JUnit 5의 기본 기능만 사용합니다. (`@Test`, `@TempDir`, `Assertions`)

## 8. 한계

- `fsync` 없음 (전원 차단 시 마지막 행 유실 가능, 덜 쓰인 행은 열 때 잘라냄).
- 인덱스를 파일에 저장하지 않음. 깨진 테이블은 재시작 전까지 오류.
- 이 개발 환경에서는 Maven을 받을 수 없어 `javac`와 JUnit 호환 도구로 테스트 101개를 통과시켰습니다. 실제 `mvnw test` 결과는 본인 PC에서 확인해야 합니다.
