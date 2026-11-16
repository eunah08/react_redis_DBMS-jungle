# 미니 DBMS

SQL 처리기 → B+ 트리 인덱스 → 스레드 풀 API 서버 순서로 만든 작은 DBMS (Java 21, 외부 실행 의존성 없음).
규칙은 모두 [DBMS_협업.md](DBMS_협업.md)에 있다. 문서가 코드보다 우선한다.
이 브랜치(`feat/dbms_sk`)는 문상균의 개인 구현이다. 자세한 설계는 [docs/implementation_notes.md](docs/implementation_notes.md).

| 단계 | 결과물 | 상태 |
|---|---|---|
| Phase 1 | CLI로 `INSERT` / `SELECT`가 동작하는 파일 기반 SQL 처리기 | 구현·테스트 완료 |
| Phase 2 | 자동 id + 메모리 B+ 트리 인덱스, 100만 건 성능 비교 | 구현·측정 완료 |
| Phase 3 | 스레드 풀 기반 HTTP API 서버, 읽기·쓰기 잠금 | 구현·테스트 완료 |

## 준비물

- JDK 21 (`java -version`이 21 이상)
- 인터넷 연결 (첫 빌드 때 Maven Wrapper가 Maven과 JUnit을 내려받는다)

## 빌드·테스트·실행

```
./mvnw test                  # 단위·기능·동시성 테스트 (Windows는 mvnw.cmd)
./mvnw package               # target/mini-dbms.jar 생성

java -jar target/mini-dbms.jar cli [--data-dir 경로] [--json] SQL파일
java -jar target/mini-dbms.jar server --host 127.0.0.1 --port 8080 --workers 4 --queue 128 --data-dir data
java -jar target/mini-dbms.jar bench --records 1000000
```

처음 써 볼 때 (`data/products.schema`는 저장소에 들어 있다) :

```
java -jar target/mini-dbms.jar cli src/test/resources/fixtures/basic.sql
java -jar target/mini-dbms.jar cli --json src/test/resources/fixtures/basic.sql
```

`data/*.tbl`은 실행하면 생기는 파일이며 Git 추적에서 제외된다. 지우면 데이터가 초기화된다.

## 지원하는 SQL

```sql
INSERT INTO products (name, price) VALUES ('키보드', 35000);
SELECT * FROM products;
SELECT name, price FROM products WHERE price = 35000;
SELECT * FROM products WHERE id = 1;            -- B+ 트리 인덱스 사용
SELECT * FROM products WHERE name = '키보드';    -- 선형 탐색
```

- 문장은 세미콜론(`;`)으로 끝나야 한다. 키워드 대소문자는 구분하지 않고 테이블·컬럼 이름은 소문자다.
- `id`는 테이블마다 1부터 자동으로 부여된다(INSERT에 적으면 `INVALID_VALUE`). 재시작해도 이어진다.
- `products`는 `name`(문자열, 최대 32바이트), `price`(0 이상 정수)다. 스키마는 `data/<테이블>.schema`에 적는다
  (`name string 32` / `price int`). `CREATE TABLE`은 지원하지 않는다.
- 문자열은 작은따옴표, 안의 작은따옴표는 두 번(`'it''s'`). `--` 줄 주석은 무시한다.
- 오류 코드 : `SQL_SYNTAX`, `UNKNOWN_TABLE`, `UNKNOWN_COLUMN`, `INVALID_VALUE`, `INTERNAL_ERROR`
  (+ 서버 : `BAD_REQUEST`, `SERVER_BUSY`).

## CLI 정책 (협약 9.1, 10.1, 13장)

- **여러 문장 중 하나가 실패하면** 그 문장에서 멈춘다. 앞에서 성공한 INSERT의 변경은 파일에 남는다. 첫 오류는 stderr에 쓰고 종료 코드 1이다.
- 기본 출력은 표(마지막 줄에 행 수), INSERT는 `inserted id=N`. `--json`이면 문장마다 JSON 한 줄이고 오류도 `{"error":"SQL_SYNTAX"}` 한 줄(stderr)이다.
- **빈 SQL 파일**, 공백·주석·세미콜론뿐인 파일은 아무것도 출력하지 않고 종료 코드 0이다.
- **없는 파일**, 읽을 수 없는 파일, UTF-8이 아닌 파일은 메시지를 stderr에 쓰고 종료 코드 1이다. UTF-8 BOM은 무시한다.
- 잘못된 인자는 사용법을 보여주고 종료 코드 1이다.

## API 서버 (Phase 3)

```
curl -s -H "Content-Type: application/json" -d '{"sql":"SELECT * FROM products WHERE id = 1;"}' http://127.0.0.1:8080/sql
curl -s http://127.0.0.1:8080/health
```

- `POST /sql` : `Content-Type: application/json`, 본문 `{"sql":"..."}` (문자열 필드 하나, 한 문장). 성공은 200과 공통 JSON.
- `GET /health` : 200 `{"status":"ok"}`.
- **없는 경로** 404 `NOT_FOUND`, **허용하지 않는 메서드** 405 `METHOD_NOT_ALLOWED`(+`Allow` 헤더), **본문 1MiB 초과** 413 `PAYLOAD_TOO_LARGE`,
  **큐 포화** 503 `SERVER_BUSY`, 잘못된 요청 400 `BAD_REQUEST`. 오류 응답은 `{"error":"코드"}`만 담는다.
- 워커 수 기본값은 CPU 수, 큐 크기 기본값은 128 (`--workers`, `--queue`). Ctrl+C를 누르면 새 요청을 멈추고
  남은 작업을 마친 뒤 워커와 엔진을 닫는다.
- 다른 기기에서 접속하려면 `--host 0.0.0.0`을 주고 그 기기에서 서버 주소로 요청한다.
- Windows 터미널에서 `curl -d`에 한글을 직접 쓰면 터미널 인코딩(UTF-8이 아님) 때문에 `BAD_REQUEST`가 날 수 있다.
  본문을 UTF-8 파일에 쓰고 `--data-binary @파일`을 쓴다.

## 삭제·병합·재분배 (협약 9.2)

B+ 트리는 **삽입·검색·노드 분할만** 구현했다. DELETE가 없으므로 삭제·병합·재분배는 구현하지 않았다.

## 구조

| 경로 | 책임 | 협약 |
|---|---|---|
| `src/main/java/minidbms/Main.java` | 실행 명령 분기 | 10장 |
| `cli/CliMain.java`, `cli/TableFormatter.java` | SQL 파일 읽기, 문장별 엔진 호출, 표·JSON 출력 | 9.1, 10.1 |
| `engine/DbEngine.java` 외 | 공통 진입점 `execute`, 검증, 인덱스/선형 선택, 테이블별 읽기·쓰기 락 | 8.1, 9.2, 9.3 |
| `sql/` | 문장 나누기, 토큰화, 파싱 (`parseSql`) | 7장 |
| `storage/` | 스키마·테이블 파일 (`readSchema`, `appendRow`, `readRows`, `readRowAt`) | 8장 |
| `index/BPlusTree.java` | `insertKey`, `findValue`, `size`, `height` | 9.2 |
| `server/` | HTTP 변환, `DbWorkerPool`(작업 큐 + 워커) | 9.3, 10.3 |
| `json/` | 요청 JSON 읽기, 응답 JSON 쓰기 (라이브러리 없이 직접 구현) | 10.2, 10.3 |
| `bench/` | 100만 건 생성·적재·측정, SQL 파일 생성, 워커 수별 처리량 | 12장 |
| `src/test/resources/fixtures/` | 세 구현 공통 SQL과 기대 결과 | 11.3 |
| `ai/` | AI 진입 파일 (`CLAUDE.md`, `GEMINI.md`, `AGENTS.md`) | 15장 |

의존 방향은 `cli·server → engine → sql·storage·index`이다. `cli`와 `server`는 `DbEngine.execute`만 부른다.

## 공통 fixture로 비교하기

```
mkdir 빈폴더 && cp data/products.schema 빈폴더/
java -jar target/mini-dbms.jar cli --data-dir 빈폴더 --json src/test/resources/fixtures/basic.sql > out.jsonl
diff out.jsonl src/test/resources/fixtures/basic.expected.jsonl
```

`./mvnw test`의 `CliMainTest`가 같은 비교를 자동으로 한다.

## 벤치마크 (협약 12장)

```
java -jar target/mini-dbms.jar bench --records 1000000                       # 적재(SQL 처리기) + 1만·10만·100만 단계 측정
java -jar target/mini-dbms.jar bench --records 1000000 --load direct         # 파싱을 건너뛰고 엔진에 바로 적재
java -jar target/mini-dbms.jar bench --records 1000000 --generate-sql big.sql # SQL 파일만 만든다 (저장소에 올리지 않는다)
java -jar target/mini-dbms.jar bench --mode throughput --records 100000      # 워커 수 1·2·4·8 처리량
```

예열 3회 뒤 인덱스·선형 경로를 번갈아 10회 측정하고 중앙값·평균·p95를 낸다. 매번 접근 경로(INDEX / FULL_SCAN)와
결과 일치를 확인한다. 옵션은 `bench/BenchmarkOptions.java`에 있다.

측정 결과 (Windows 11, 8코어, JDK 21, 시드 20261116, 예열 3회 뒤 번갈아 10회)

| 레코드 수 | 조회 방식 | 평균(ms) | 중앙값(ms) | p95(ms) | 배수 |
|---|---|---|---|---|---|
| 1,000,000 | `WHERE id = 500000` (인덱스) | 0.1091 | 0.1123 | 0.1541 | 1x |
| 1,000,000 | `WHERE name = 'product_0500000'` (선형) | 623.6502 | 597.9549 | 874.9323 | 5,325x |
| 100,000 | `WHERE id = 50000` (인덱스) | 0.1114 | 0.1076 | 0.1439 | 1x |
| 100,000 | `WHERE name = ...` (선형) | 67.2207 | 66.0602 | 81.6240 | 614x |
| 10,000 | `WHERE id = 5000` (인덱스) | 0.2309 | 0.1947 | 0.4336 | 1x |
| 10,000 | `WHERE name = ...` (선형) | 7.7374 | 7.3664 | 10.3834 | 38x |

재시작 뒤 인덱스 재구성은 100만 건에서 1.41초, 100만 건 적재(SQL 처리기)는 약 22초다. 전체 표와 워커 수별 처리량은 [docs/implementation_notes.md](docs/implementation_notes.md) 3·4장에 있다.

## 알려진 제한

[docs/implementation_notes.md](docs/implementation_notes.md)의 6장을 본다. (스키마 변경 불가, `fsync` 없음, 인덱스는 id 한 가지 등)
