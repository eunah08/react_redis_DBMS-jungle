# 미니 DBMS

Java 21로 직접 만든 SQL 처리기, 파일 저장소, 메모리 B+ 트리, 고정 스레드 풀 HTTP 서버다.
[협업 규약](DBMS_협업.md)을 기준으로 Phase 1의 엔진과 파일을 Phase 2·3에서도 재사용한다.
SQL 파싱 라이브러리·기존 DB 엔진·프레임워크 스레드 풀·외부 JSON 라이브러리를 사용하지 않는다.

## 빌드·테스트

JDK 21과 저장소의 Maven Wrapper를 사용한다. 첫 실행에는 Maven/JUnit 다운로드를 위한
인터넷 연결이 필요하다. 런타임 의존성은 Java 표준 라이브러리뿐이다.

```sh
# macOS: 설치된 JDK 21 선택
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw test
./mvnw package                  # target/mini-dbms.jar
```

Linux/Windows에서는 설치한 JDK 21의 JAVA_HOME을 지정한다. Windows Wrapper는 `mvnw.cmd`다.
테스트는 임시 폴더를 쓰며 실제 CLI 자식 프로세스, 로컬 HTTP 서버, 실패 주입,
인덱스 재구성, 동시 읽기/쓰기, 과부하·종료까지 검사한다. 소켓을 금지한 실행 환경에서는
HTTP 테스트에 로컬 소켓 권한이 필요하다. 의도적으로 발생시킨 작업 오류의 stderr는 예상 로그다.

## Phase 1 — CLI와 파일 저장

예제 스키마 `data/products.schema`는 다음 두 줄이다. id는 자동으로 맨 앞에 붙는다.

```text
name STRING 32
price INT
```

스키마 형식은 `이름 INT` 또는 `이름 STRING UTF-8최대바이트`다. `#` 주석과 빈 줄을 허용한다.
테이블·컬럼 이름은 소문자·숫자·밑줄이다. products는 공통 스키마인 id/name/price를 쓴다.
스키마가 있는 테이블의 데이터 파일은 처음 열 때 초기화하며 CREATE TABLE은 구현하지 않았다.

```sh
java -jar target/mini-dbms.jar cli --data-dir data --json src/test/resources/fixtures/basic.sql
# --json을 생략하면 표 출력
java -jar target/mini-dbms.jar cli --data-dir data src/test/resources/fixtures/basic.sql
```

공통 fixture를 정확히 비교할 때는 빈 폴더를 쓴다. 같은 폴더에 fixture를 다시 실행하면
이전 데이터가 남아 있으므로 ID도 이어진다.

```sh
demo_dir=$(mktemp -d)
cp src/test/resources/fixtures/products.schema "$demo_dir/products.schema"
java -jar target/mini-dbms.jar cli --data-dir "$demo_dir" --json \
  src/test/resources/fixtures/basic.sql > "$demo_dir/out.jsonl"
diff -u src/test/resources/fixtures/basic.expected.jsonl "$demo_dir/out.jsonl"
```

지원 SQL:

```sql
INSERT INTO products (name, price) VALUES ('오''늘;😀', 35000);
INSERT INTO products (price, name) VALUES (0, '');
SELECT * FROM products;
SELECT name, price FROM products WHERE price = 35000;
SELECT * FROM products WHERE id = 1;
```

- 키워드는 대소문자를 구분하지 않는다. 이름은 대문자를 자동 변환하지 않고 UNKNOWN_TABLE/COLUMN이다.
- 문장 끝 `;`가 필수다. 문자열의 `;`, `,`, 줄바꿈은 보존하고 작은따옴표는 `''`로 쓴다. `--` 주석을 지원한다.
- INT는 0~2147483647, id는 테이블별 1부터 자동 발급한다. 명시적 id·컬럼 누락/중복·타입/길이 오류는 거부한다.
- WHERE는 단일 `=` 조건이다. 음수/0 id는 빈 결과다. WHERE의 타입 불일치는 INVALID_VALUE다.
- 첫 오류에서 중단하며 앞서 성공한 INSERT는 남긴다. JSON 성공은 stdout, 오류는 stderr 한 줄이다.
- 빈 파일·공백/주석만 있는 파일은 출력 없이 종료 0. `;`만 있는 문장은 SQL_SYNTAX, 없는 파일은 INTERNAL_ERROR다.
- CLI 옵션 오류는 SQL_SYNTAX로 종료 1이다. 기본 데이터 폴더는 `data/`다.

각 테이블은 `<table>.table` 하나다. 헤더 뒤에 고정 길이 바이너리 행을 추가하며, 문자열은
실제 UTF-8 바이트 길이+bytes+0 패딩으로 저장한다. 쉼표·따옴표·줄바꿈·NUL·한글·이모지를
보존한다. 위치는 long 바이트 오프셋이다. 자세한 헤더·복구 규칙은 구현 노트에 있다.

## Phase 2 — B+ 트리와 최소 측정

테이블별 B+ 트리가 id → 파일 위치를 보관한다. 키 최대 64개를 넘으면 리프·내부 노드·루트를
분할한다. 리프끼리 연결되고 모든 리프의 깊이가 같다. 삭제·병합·재분배는 구현하지 않았다.
시작할 때 파일을 순회해 인덱스와 다음 id를 복원한다. id 조건은 행 하나를 직접 읽고,
다른 조건은 파일을 순차 탐색한다. 전체 행을 인덱스에 복제하지 않는다.

**사용자의 최신 지시에 따라 100만 건 측정은 중단했다.** 기본 측정은 100건·1회·무작위 3개로
최소화했다. 작은 값은 동작 확인용이며 성능 우위를 일반화하는 근거로 쓰지 않는다.

```sh
java -Xms128m -Xmx512m -jar target/mini-dbms.jar bench
# 같은 최소 조건을 명시
java -Xms128m -Xmx512m -jar target/mini-dbms.jar bench \
  --records 100 --repeats 1 --random-queries 3 --json
# 원본 JSON/로그와 docs/benchmark.md 생성
sh src/main/java/minidbms/bench/run_benchmarks.sh
```

예열은 각 고정 조건 2회다. 인덱스 ID, 같은 ID 강제 순차 탐색, 같은 행의 이름 순차 탐색을
비교하고 결과·접근 경로가 같은지 확인한다. 적재·재구성·JSON 출력 시간은 조회 시간에서 분리한다.
`--data-dir`은 비어 있어야 한다. 생략하면 새 임시 폴더를 만들고 보고한 위치에 데이터를 남긴다.

```sh
# 파싱만 생략하고 같은 엔진 검증·파일·인덱스 경로로 적재
java -jar target/mini-dbms.jar bench --records 100 --mode direct
# SQL 파일 생성. 출력 파일은 아직 없어야 한다.
java -jar target/mini-dbms.jar bench --records 100 --generate-sql target/products-demo.sql
```

100건 최소 실측의 무작위 평균은 INDEX_ID 0.090644ms, SCAN_ID 0.197853ms,
SCAN_NAME 0.297347ms다. JDK 21.0.11·Intel i5-8259U·heap 512MiB 조건이며 상세 표본과
조건은 [benchmark.md](docs/benchmark.md), [원본 JSON](docs/benchmark_results.json)에 있다.
협약의 대규모 측정·크기별 그래프 검증은 이번 사용자 지시에 따라 완료 항목에서 제외한다.

## Phase 3 — HTTP와 직접 만든 스레드 풀

```sh
java -jar target/mini-dbms.jar server \
  --host 127.0.0.1 --port 8080 --workers 4 --queue 128 --data-dir data
```

기본값은 host 127.0.0.1, port 8080, SQL 워커 CPU 수, 큐 128이다. 요청마다 스레드를 만들지 않는다.

```sh
curl -sS http://127.0.0.1:8080/health
curl -sS http://127.0.0.1:8080/sql -H 'Content-Type: application/json' \
  --data-binary "{\"sql\":\"INSERT INTO products (name, price) VALUES ('keyboard', 35000);\"}"
curl -sS http://127.0.0.1:8080/sql -H 'Content-Type: application/json' \
  --data-binary '{"sql":"SELECT * FROM products WHERE id = 1;"}'
```

성공은 `{"id":1}` 또는 `{"rows":[...]}`, 오류는 `{"error":"SQL_SYNTAX"}` 형식이다.
API는 SQL 한 문장만 허용한다. JSON sql 누락·중복·추가 필드·타입 오류·빈 값은 BAD_REQUEST 400이다.
없는 경로는 404 BAD_REQUEST, 메서드 오류는 405 BAD_REQUEST, 본문 1MiB 초과는 413 BAD_REQUEST다.
엔진 오류는 협약의 코드와 HTTP 상태를 따른다. Content-Type은 application/json; charset=utf-8이다.

테이블별 ReentrantReadWriteLock을 공유한다. SELECT는 읽기 락으로 함께 수행하고,
INSERT의 ID 발급→파일 추가→인덱스 등록→실패 복구는 같은 쓰기 락이다. JSON/네트워크는 락 밖이다.

SQL 작업 큐가 가득 차면 503 SERVER_BUSY다. 불완전 헤더가 dispatcher를 막지 않도록
오류 응답 전용 고정 워커 2개·대기 16개를 별도로 둔다. 여기서는 SQL을 실행하지 않는다.
이 별도 서비스까지 불완전 연결로 포화되면 연결을 즉시 닫는 연결 부하 상한을 적용한다.
전체 SQL 동시 실행 수는 여전히 `--workers` 값이다.

OpenJDK 21의 요청/응답 제한시간 기본값을 10초/30초로 설정한다. JVM의 같은 속성을 미리
지정하면 그 값을 우선한다. 연결은 응답 뒤 닫는다. SIGINT/SIGTERM은 HTTP 정리(최대 10초),
승인된 작업 완료, 두 워커 풀 join, 엔진 close 순서로 처리한다. 정상 작업 오류 뒤에도 워커는
재사용한다. 쓰기 도중 Error가 나면 해당 테이블을 차단한다. VM 자원 고갈 시 가용성은 보장하지 않는다.

## 구현 파일과 알려진 제한

| 위치 | 역할 |
|---|---|
| `sql/SqlLexer`, `SqlParser`, `SqlStatements` | 토큰화·파싱·문장 경계 |
| `storage/SchemaReader`, `RowCodec`, `RowFile`, `TableFile` | 스키마·바이너리 행·위치 I/O·자원 소유 |
| `engine/DbEngine`, `SchemaValidator`, `TableState`, `DbResult` | 검증·실행·잠금·결과 |
| `engine/BulkLoader` | 벤치마크용 파싱 생략 적재 |
| `index/BPlusTree` | 직접 만든 배열 기반 B+ 트리 |
| `server/DbHttpServer`, `DbWorkerPool`, `ServerOptions` | HTTP·유한 큐·고정 워커·옵션 |
| `minidbms/JsonCodec` | CLI·서버 공용 JSON 코덱 |
| `bench/Benchmark*`, `run_benchmarks.sh`, `summarize_benchmarks.py` | 최소 측정·재현·보고서 |
| `src/test/java/`, `src/test/resources/fixtures/` | 단위·통합·실제 프로세스·HTTP 검사와 공통 자료 |

위 Java 패키지 경로는 `src/main/java/minidbms/` 기준이다.
파일 형식·실제 함수 흐름·실패 복구·새 내부 이름은 [구현 노트](docs/implementation_notes.md)에 있다.

데이터 폴더는 DBMS 프로세스 하나만 사용한다. 스키마 변경은 지원하지 않으며 지문이 달라지면
해당 테이블을 차단한다. 손상된 헤더/마지막 행은 조용히 버리지 않고 INTERNAL_ERROR로 알린다.
SELECT 전체 결과는 메모리에 완성하며 페이징/트랜잭션/UPDATE/DELETE/WAL/매 INSERT fsync는
구현하지 않았다. OS/전원 장애의 내구성을 약속하지 않는다. 다른 팀원 구현과의 교차 실행 및
사람의 설명·최종 팀 통합은 별도 검토 항목이다.
