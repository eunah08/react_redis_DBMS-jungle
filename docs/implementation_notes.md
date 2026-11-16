# 구현 안내 — JK

## 기준과 범위

제공받은 `DBMS_협업.md` 전체를 읽고 7~13장에 맞춰 Phase 1부터 다시 확인했다.
규약 원문은 내용 변경 없이 저장소 루트에 복사했다. 이전에 구현·검증한 B+ 트리와
워커 풀은 4장 명명 규칙에 맞춰 재사용했다. 기존 공개 메서드와 Statement/Condition 형식,
공통 SQL·기대 결과는 유지했다. 브랜치 변경·커밋·푸시·병합은 하지 않았다.

사용자의 마지막 지시 **“100만 건 측정은 하지 말고 최소화”**가 이번 측정 범위에 우선한다.
진행 중이던 100만 건 측정은 중단했고 Java 프로세스 종료를 확인했다. 최종 결과는
**100건·고정 조건 1회·무작위 3개**뿐이다. 대량 적재 옵션은 구현되어 있으나 협약 12장의
완전한 대량 측정·규모별 그래프 검증을 완료했다고 주장하지 않는다.

## 1. 파싱과 CLI

`SqlLexer`는 SQL을 단어/정수/문자열/기호/끝 토큰으로 나눈다. 키워드는 대소문자를
구분하지 않고 식별자는 원문을 유지한다. `SqlParser.parseSql`은 INSERT/SELECT와
단일 WHERE 동등 조건을 Statement로 만든다. 값은 문자열과 Integer로 구분해 유지한다.
스키마를 모르므로 컬럼 수 불일치 등은 engine이 INVALID_VALUE로 판정한다.
정수 범위 오류는 SqlParseException의 구분 값으로 INVALID_VALUE에 매핑한다.
지원하지 않는 문법·마지막 세미콜론 누락·여러 문장은 SQL_SYNTAX다.

`SqlStatements.split`은 작은따옴표와 `--` 주석의 상태를 추적해 문자열 밖의 세미콜론만
경계로 삼는다. `''`는 작은따옴표 하나다. 미완성 마지막 문장을 버리거나 미리 실행하지 않는다.
따라서 앞서 성공한 INSERT 뒤의 문법 오류가 앞의 변경을 되돌리지 않는다.

`CliMain.run`은 파일을 UTF-8로 읽고 문장마다 **DbEngine.execute만** 호출한다.
처음 실패한 문장에서 중단한다. 성공 JSON은 문장당 stdout 한 줄, 오류는 stderr 한 줄,
종료 코드는 0/1이다. 기본 표는 요청한 컬럼 순서와 행 수를 출력한다.
빈/공백/주석 전용 파일은 출력 없이 0, `;`만 있으면 SQL_SYNTAX,
없는 파일은 INTERNAL_ERROR다. CLI 옵션 오류는 SQL_SYNTAX로 처리한다.

공통 fixture는 `fixtures/products.schema`, `basic.sql`, `basic.expected.jsonl`이다.
CLI 자식 프로세스로 결과를 diff하고 다른 자식 프로세스에서 같은 파일을 재조회했다.

## 2. 스키마·파일·위치

스키마 문법은 한 줄당 `이름 INT` 또는 `이름 STRING 최대UTF8바이트`다.
빈 줄·`#` 주석을 허용하며 id INT를 맨 앞에 자동 추가한다. 명시된 id INT도 중복 없이
하나만 인정한다. products는 id/name STRING32/price INT의 공통 형태를 검증한다.
모든 저장 INT는 0~2147483647, id는 1 이상이다. 테이블/컬럼 이름은 소문자·숫자·밑줄이고
입력을 파일 경로에 그대로 연결하지 않는다. 심볼릭 링크 파일을 테이블로 열지 않는다.
한 행의 최대 크기는 1MiB다. 스키마 등록은 엔진 시작 시 확정된다.

테이블당 `<name>.table` 하나다. 고정 길이 바이너리 행을 골라 B+ 트리의 위치를 바로
파일 바이트 오프셋으로 사용할 수 있게 했다. 기존 행을 다시 쓰지 않고 끝에만 추가한다.

| 영역 | 구성 |
|---|---|
| 44바이트 헤더 | 8바이트 `MINIDB01` 버전 표식 + 4바이트 행 크기 + 32바이트 스키마 SHA-256 지문 |
| INT | big-endian 4바이트 |
| STRING | 실제 바이트 길이 4바이트 + UTF-8 bytes + 선언 길이까지 0 패딩 |
| products 행 | id 4 + name 길이 4 + name 32 + price 4 = 44바이트 |

지문은 정규화된 Schema record 표현을 UTF-8로 해시한 값이다. 행 위치는 헤더 이후의
**long 바이트 위치**이며 행 경계인지 검증한다. 문자열 길이와 패딩을 분리해 빈 문자열,
공백·쉼표·인용 부호·줄바꿈·NUL·한글·이모지를 그대로 복원한다. 잘못된 UTF-8은 오류다.
헤더/지문 불일치, 잘린 마지막 행, 정렬 경계를 벗어난 위치는 StorageException이다.
손상된 행을 조용히 무시하거나 임의로 복구하지 않는다.

`TableFile`이 테이블별 `RowFile`/FileChannel을 소유하고 close한다. 순차 반복자는 채널을
새로 열지 않고 약 64KiB의 독립 버퍼와 위치를 가진다. 직접 조회도 요청별 ByteBuffer와
`FileChannel.read(buffer, offset)`를 써서 공유 channel.position을 변경하지 않는다.
고정 길이 문자열은 패딩 비용이 있지만 products처럼 짧은 정해진 스키마에 적합하다.

## 3. 공통 엔진·자동 ID·실패 복구

`DbEngine`은 시작 시 등록된 각 스키마와 저장 파일을 읽는다. 테이블별 TableState에
Schema, 다음 ID(long), B+ 트리, ReentrantReadWriteLock, 장애 차단 플래그를 둔다.
행 ID의 연속성을 확인하고 최대 ID 다음 값부터 발급한다. 손상된 테이블은 차단하고
다른 테이블은 계속 사용할 수 있다. JSON·HTTP·표 출력은 엔진이 하지 않는다.

`execute` → `parseSql` → `executeParsed` 순서로 진행한다. `SchemaValidator`가
컬럼 존재 여부, 필수 컬럼, 중복, 자료형, 범위와 UTF-8 바이트 길이를 먼저 검증한다.
INSERT에 id를 적으면 INVALID_VALUE다. WHERE의 음수는 빈 결과지만 타입 불일치는 오류다.
결과 DbResult는 id/불변 rows/접근 경로 또는 오류 코드·안전한 메시지를 보관한다.

INSERT는 쓰기 락 안에서 다음 순서로 수행한다.

1. 다음 ID와 입력 검증, 스키마 순서의 행 구성.
2. `appendSafely` → `TableFile.appendRow` → `RowFile.appendRow`로 파일 끝에 행 추가.
3. `BPlusTree.insertKey(id, position)`으로 등록한 뒤 다음 ID 증가, 성공 반환.

파일 부분 쓰기가 IOException으로 실패하면 RowFile이 원래 끝으로 truncate한다.
인덱스 등록 실패는 `rollbackAppend`로 파일을 되돌리고 기존 파일에서 인덱스를 새로 구성한다.
복구도 실패하면 해당 TableState/RowFile을 차단해 부재 ID 조회까지 INTERNAL_ERROR다.
쓰기 중 Error가 발생해도 테이블을 차단한 뒤 상위 작업 경계로 전달한다.

롤백이 실패한 요청은 성공 여부가 불확실할 수 있다. 완전한 행이 파일에 남았다면 재시작
재구성에서 포함될 수 있고, 불완전한 행이면 계속 오류다. WAL/트랜잭션 구현 없이 실패를
성공으로 숨기지 않는다. 실제 파일 채널을 장식한 테스트로 부분 쓰기·인덱스 등록·롤백
실패를 주입했다. 테스트 계측은 `src/test/`에만 있고 프로덕션에 테스트 훅을 넣지 않았다.

## 4. B+ 트리와 접근 경로

`BPlusTree`는 배열 기반 노드다. 기본 키 최대 개수는 64, overflow 슬롯을 하나 더 둔다.
내부 노드는 분기 키/자식만 보유하고 리프는 키/long 위치/다음 리프를 가진다.
행 전체를 복제하지 않는다. 중복 키는 오류다. 삭제·병합·재분배는 구현하지 않았다.

`insertKey` → `insert` → `insertLeaf` 또는 하위 자식 삽입이다. 리프 overflow는 절반을
새 오른쪽 리프로 옮기고 오른쪽 최소 키를 부모에 **복사**한다. 내부 노드 overflow는
중앙 분기 키를 부모로 **올리고** 양쪽 자식을 나눈다. 루트가 나뉘면 새 루트를 만든다.
리프 연결은 중간 삽입에서도 유지한다. findValue는 분기 키와 같으면 오른쪽으로 간다.
키 정렬·리프 연결·모든 리프의 동일 깊이를 차수 3/4/64와 오름차순·역순·무작위에서 검사했다.

- `WHERE id = 정수`: findValue → 위치의 행 하나 readRowAt. 결과 accessPath=INDEX.
- 다른 컬럼/조건 없음: readRows → 조건 검사 → 컬럼 투영. accessPath=FULL_SCAN.
- id 미존재도 INDEX로 판정되며 파일의 모든 행을 읽지 않는다.
- `DbEngine(Path, FULL_SCAN)`은 Phase 1 및 동일 id SQL의 강제 순차 탐색 비교용이다.
- 재시작은 `readRows`의 id/위치로 인덱스를 다시 만든다. 파일 형식을 바꾸지 않는다.

트리 연산은 차수가 상수일 때 O(log n), 선형 탐색은 O(n), 전체 인덱스 구성은 O(n log n)이다.
행 위치 직접 읽기는 행 크기만큼의 I/O다. SELECT 결과 행은 요청의 메모리에 완성한다.

## 5. HTTP·워커·잠금·종료

JDK HttpServer의 `setExecutor`에 직접 구현한 DbWorkerPool을 연결했다.
고정 Thread들이 유한 ArrayBlockingQueue에서 Runnable을 꺼낸다. 요청마다 새 스레드를
만들지 않는다. 정상 SQL 워커는 기본 CPU 수, 큐는 128이며 실행 옵션으로 바뀐다.
execute의 승인/종료 판정과 offer를 같은 lifecycle 모니터에서 처리해 종료와 제출 사이에
승인된 작업이 남지 않게 한다. shutdown은 승인된 작업을 마치고 모든 워커를 join한다.
요청 작업에서 발생한 RuntimeException/AssertionError 등은 작업 경계에서 기록하고 워커를
재사용한다. VM 자원 고갈에서는 가용성을 보장하지 않으며 쓰기 테이블은 차단한다.

### 과부하

JDK가 Executor에 전달하는 작업은 HttpExchange 생성 전의 헤더 파싱도 포함한다.
거절 작업을 dispatcher에서 직접 실행하면 느린 헤더 하나가 모든 새 연결을 막을 수 있다.
이 경로는 별도 **고정 워커 2개/큐 16개**의 거절 서비스에 격리했다. ThreadLocal 상태로
정상 SQL과 구분해 본문·엔진을 실행하기 전에 `503 {"error":"SERVER_BUSY"}`만 전송한다.
거절 서비스 자체도 포화/종료하면 RejectedExecutionException으로 JDK의 연결 종료 경로에
넘기며 dispatcher에서 다시 실행하지 않는다. 따라서 정상 SQL 큐 포화는 503이고,
불완전 헤더가 거절 서비스의 부하 상한까지 채우는 극단적 연결 부하는 연결 종료가 된다.
18개의 불완전 헤더가 있어도 dispatcher가 멈추지 않고 정상 워커 회복 후 health에 응답했다.

### 테이블별 동시성

- TableState마다 공정한 ReentrantReadWriteLock을 사용한다.
- INSERT: ID 할당 → 파일 추가 → 인덱스 등록 → 실패 복구까지 **쓰기 락 하나**.
- SELECT: 검증된 접근 경로로 읽고 불변 결과를 완성할 때까지 **읽기 락**.
- JSON 변환·HTTP 전송은 execute가 반환한 뒤라 테이블 락 밖이다.
- 다음 ID/인덱스는 쓰기 락, 상태 확인은 테이블 락, 장애 차단 표시는 volatile로 보호한다.
- SQL 하나는 테이블 하나만 잠근다. 종료 시 테이블 사전순으로 진행하고 동시에 두 락을 잡지 않는다.
- 다른 테이블의 잠금을 기다리지 않는다. 작업 큐의 lifecycle 모니터를 쥔 채 SQL/네트워크를 하지 않는다.

실제 HTTP 워커 2개가 같은 테이블의 파일 읽기 지점에 시간 제한 barrier로 함께 진입했다.
동시 INSERT의 ID가 고유·연속이고 재조회한 값은 온전했다. 큐 포화에서 503 뒤 정상 복귀,
복구 실패 테이블 차단, 다른 테이블 독립성, 종료 시 두 풀 join도 검사했다.

### API와 종료

`POST /sql`은 sql 문자열 하나만 있는 JSON을 받는다. 중복은 JSON 이스케이프를 해석한 후
판정한다. 빈/추가 필드/잘못된 UTF-8/JSON은 BAD_REQUEST다. 여러 SQL은 SQL_SYNTAX다.
`GET /health`는 `{"status":"ok"}`다. 성공은 협약의 id/rows, 오류는 error 코드만 응답한다.
정의되지 않은 경로는 404 BAD_REQUEST, 메서드는 405 BAD_REQUEST, 본문 1MiB 초과는
413 BAD_REQUEST다. 내부 경로나 스택은 응답에 넣지 않는다. HEAD는 HTTP 규칙상 본문을 생략한다.

JDK 21의 maxReqTime=10초/maxRspTime=30초/drainAmount=0 기본 설정을 사용한다.
외부 JVM 속성이 있으면 우선하며, JDK HttpServer가 최초 초기화될 때 적용된다.
응답 후 연결을 닫는다. 종료는 HTTP 정리(최대 10초) → 정상 풀 drain/join → 거절 풀 drain/join
→ engine.close다. SIGINT/SIGTERM의 JVM 종료 훅을 둔다. 실제 JAR에 SIGTERM을 보낸 뒤
새 CLI 프로세스에서 같은 행을 확인했다. SIGTERM의 프로세스 종료 상태 143은 정상적인 신호 종료다.

## 6. 최소 측정 결과와 재현

최신 사용자 지시로 기본값과 재현 스크립트를 100건·1회·무작위 3개로 바꿨다.
100만 건 진행은 중단했고 완료 결과로 제출하지 않는다. 대량 실행·규모별 그래프·무작위 1000개
검증은 이번 범위에서 제외됐다. 기존 큰 측정의 숫자를 최소 측정의 결과로 섞지 않았다.

동일한 SQL 엔진으로 빈 폴더에 적재하고 재시작 인덱스 구성 후 비교했다. 고정 조건은 첫/중간/
마지막/없는 id, 무작위는 seed 42다. 각 고정 조건 예열 2회, 반환 결과와 실제 AccessPath를
매 조회 확인한다. INDEX_ID와 SCAN_ID는 **같은 id SQL**이고 SCAN_NAME은 같은 행의 이름 조건이다.
SCAN_ID는 유일한 id를 찾으면 종료하며 이름 조건은 전체를 검사한다. 조회는 System.nanoTime으로
측정하고 적재·재구성·JSON 변환·출력은 분리한다. 직접 적재 옵션도 같은 검증·파일·인덱스를 쓴다.

| 100건, 무작위 3개 | 평균 ms | 중앙값 ms | p95 ms |
|---|---:|---:|---:|
| id 인덱스 | 0.090644 | 0.075897 | 0.125554 |
| 같은 id 강제 스캔 | 0.197853 | 0.204595 | 0.216350 |
| 같은 행 이름 스캔 | 0.297347 | 0.278781 | 0.356308 |

SQL 적재 22.873ms, 재구성 5.302ms, 파일 4,444 bytes. JDK Microsoft 21.0.11,
Intel i5-8259U, 논리 CPU 8, macOS 15.7.7, JVM -Xms128m/-Xmx512m 조건이다.
표본이 작고 같은 OS 캐시를 사용한 동작 확인 값이므로 성능 우위를 일반화하지 않는다.
상세 조건·개별 표본은 [benchmark.md](benchmark.md), [benchmark_results.json](benchmark_results.json)에 있다.

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw test
./mvnw package
sh src/main/java/minidbms/bench/run_benchmarks.sh
```

## 7. 검증·별도 검토·알려진 제한

테스트는 스텁에서 실패하는 것을 먼저 확인한 뒤 파일 단위로 구현했다. 파서/저장/엔진/CLI/
인덱스/워커/HTTP/생성기를 포함한다. 공통 fixture와 별도 프로세스 재실행을 검증했다.
별도 검토에서 찾은 dispatcher 정지와 Error 뒤 워커 소멸은 회귀 테스트 RED→GREEN으로 수정했다.
쓰기 Error의 테이블 차단, 실제 HTTP 동시 읽기, 승인된 INSERT를 마친 종료도 보강했다.
Java 21 빌드의 컴파일 경고는 없으며, 규약의 함수 30줄/인자 3개는 Java AST로 검사했다.

알려진 제한:

- 저장 폴더 하나는 DBMS 프로세스 하나만 쓴다는 전제다. 다중 프로세스 파일 잠금은 없다.
- 스키마 변경과 동적 테이블 추가는 지원하지 않는다. 등록/변경 후에는 재시작이 필요하다.
- 전체 SELECT 결과는 메모리에 완성한다. 페이징과 결과 크기 상한은 선택 기능으로 남겼다.
- 삭제·병합·재분배·UPDATE/JOIN/복합 WHERE/WAL/트랜잭션은 구현하지 않았다.
- 매 INSERT fsync는 하지 않아 전원/OS 장애의 내구성이나 완전한 원자적 복구를 보장하지 않는다.
- 손상된 헤더/행의 자동 수선은 하지 않는다. 실패 복구 불가 테이블은 INTERNAL_ERROR다.
- JSON은 깊이 32·숫자 128글자를 제한한다. API의 정상 필드는 SQL 문자열 하나뿐이다.
- 거절 서비스 연결 부하 상한과 JDK HttpServer의 제한시간/종료 유예가 존재한다.
- 사용자 지시로 100만 건 벤치마크는 중단했다. 다른 팀원 구현 교차 실행·사람의 설명/리뷰는 남는다.

새 주요 내부 이름: SqlLexer, SqlStatements, SqlParseException, SchemaReader,
StorageException, RowCodec, RowFile, SchemaValidator, DbFailure, TableState,
BulkLoader, JsonCodec, ServerOptions, BenchmarkOptions/Data/Runner/Stats.
추가 내부 공개 경로: TableFile.tableNames/rollbackAppend, DbEngine(Path, AccessPath),
BulkLoader.execute, DbHttpServer.start/getPort/PoolOptions. 기존 execute/parseSql/행 I/O/트리/풀 이름은 유지했다.
주요 파일 위치와 실행 명령은 README에 기록했다.

## 8. 설명할 실제 요청 흐름

`POST /sql {"sql":"SELECT * FROM products WHERE id = 1;"}`
→ HttpServer 작업 큐 → DbWorkerPool.work → DbHttpServer.handle/readSql/executeSql
→ DbEngine.execute → SqlLexer/SqlParser.parseSql → executeParsed의 테이블 읽기 락
→ SchemaValidator.planSelect → selectRows → BPlusTree.findValue의 위치
→ TableFile.readRowAt → RowFile.readRowAt의 독립 버퍼 → RowCodec.decode
→ DbResult 완성 → 락 해제 → JsonCodec.toJson → HTTP 200이다.

INSERT는 같은 executeParsed에서 쓰기 락을 선택하고 검증 후 appendSafely → insertKey →
nextId 증가까지 마친다. JSON/HTTP는 락 밖이며 중간 실패는 파일 truncate와 인덱스 재구성,
복구 실패는 테이블 차단으로 끝난다. 핵심 참고 파일은 DbEngine/RowFile/BPlusTree/DbWorkerPool/DbHttpServer다.

직접 설명할 질문:

1. 리프 분할의 분기 키 복사와 내부 노드 분할의 키 승격은 무엇이 다르며, 검색의 동일 키는 왜 오른쪽인가?
2. INSERT의 ID·파일·인덱스가 일관되게 보이는 잠금 경계와, 복구 실패 시 테이블을 차단하는 이유는 무엇인가?
3. 작업 큐 포화에서 JDK dispatcher가 직접 요청을 파싱하면 왜 멈출 수 있고, 고정 거절 서비스가 무엇을 분리하는가?

## 최종 실행 확인

- Java 21.0.11의 `./mvnw -o -B package`: 72 tests, failures 0, errors 0, BUILD SUCCESS.
  최종 컴파일 로그에 경고가 없고 Java AST 검사에서 함수 30줄/인자 3개 제한 위반이 없었다.
- 실제 실행 JAR에 curl로 health/INSERT/SELECT/UNKNOWN_COLUMN 400을 확인했다.
  검증 서버에 SIGTERM을 보낸 뒤 새 CLI 프로세스가 동일한 행을 읽었다.
- 최소 측정은 100건/1회/무작위 3개이며 보고서의 소스 SHA-256이 최종 구현과 일치한다.
- 협약 원문 복사본은 제공 파일과 동일하고 `git diff --check`를 통과했다.
- 검증용 서버는 종료했으며 변경은 feat/dbms_JK의 미커밋 상태로 남겼다.
