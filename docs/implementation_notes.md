# 구현 안내

작성자(이니셜) : sk (브랜치 `feat/dbms_sk`)
협약 14.3의 항목이다. 협약 문서(`DBMS_협업.md`)가 이 문서보다 우선한다.

## 1. 파싱 방식

SQL 문자열은 `토큰화 → 구문 검증 → Statement 생성 → (engine) 스키마 검증 → 실행` 순서로 처리한다.
문자열을 한 번 나누어 바로 실행하지 않는다. (협약 7.3)

- **문장 나누기** (`sql/SqlSplitter`) : SQL 파일 내용을 한 글자씩 훑으며 문자열 안(`'...'`, `''`는 따옴표 하나)과
  `--` 줄 주석 안의 `;`는 경계로 보지 않는다. 세미콜론만 있는 빈 문장과 주석뿐인 부분은 건너뛴다.
  세미콜론 없이 끝난 마지막 문장은 그대로 넘겨 파서가 `SQL_SYNTAX`로 알린다. `cli`만 쓴다.
- **토큰 나누기** (`sql/SqlTokenizer`) : 키워드(INSERT·INTO·VALUES·SELECT·FROM·WHERE, 대소문자 무시),
  이름(`[A-Za-z_][A-Za-z0-9_]*`), 정수(`-?[0-9]+`), 문자열, 기호(`( ) , * = ;`)로 나눈다.
  모르는 글자, 숫자에 붙은 글자(`12abc`), 닫히지 않은 따옴표는 위치(몇 번째 줄 몇 번째 칸)와 함께 오류다.
- **문장별 파싱** (`sql/SqlParser`) : 재귀 하강 방식. `parseInsert`, `parseSelect`, `parseCondition`이 토큰을 하나씩
  소비한다. 마지막은 반드시 `;`이고 그 뒤에 토큰이 더 있으면 "한 번에 한 문장만" 오류다.
  값은 SQL에 적힌 모양 그대로 담는다. 정수는 `Long`(long에 안 들어가면 `BigInteger`), 문자열은 `String`이다.
- **스키마 검증** (`engine/StatementValidator`) : 테이블·컬럼 존재, 타입, 범위, 길이를 파일을 건드리기 전에 검사한다.
  결과는 `SelectPlan`(컬럼 위치와 비교 값을 미리 계산)이다.
- 오류 메시지는 `[함수이름] 한글 설명` 형식이다. `SqlSyntaxException`(sql) → `SQL_SYNTAX`,
  `EngineException`(engine 내부) → 해당 코드, `StorageException`(storage) → `INTERNAL_ERROR`로 `DbEngine.execute`가 바꾼다.

## 2. 저장

- **스키마 파일 형식** : `data/<테이블>.schema`, 한 줄에 컬럼 하나, `#` 줄과 빈 줄은 무시.
  `이름 int` 또는 `이름 string 최대UTF-8바이트수`. `id`는 적지 않으며 맨 앞에 자동으로 붙는다. products는
  `name string 32` / `price int`다.
- **테이블 파일 형식과 고른 이유** : `data/<테이블>.tbl` 바이너리 한 파일.

  ```
  머리 12바이트 : 매직 "MDB1"(4) | 컬럼 수 int32 | 스키마 CRC32 int32
  행 기록       : 본문 길이 int32 | 본문 | 본문 CRC32 int32      (모두 big-endian)
  본문          : 스키마 순서대로  정수 = int32,  문자열 = [UTF-8 바이트 수 int32][UTF-8 바이트]
  ```

  - **길이 접두 방식**을 골랐다. CSV는 쉼표·따옴표·줄바꿈이 든 문자열에 이스케이프 규칙이 필요하지만,
    길이 접두는 어떤 글자든 그대로 저장되고 다른 처리가 없다. 한글·이모지·빈 문자열·NUL도 같다.
  - **행마다 CRC32**를 붙여 손상된 행을 읽을 때 알아챈다. 행당 평균 35바이트로 CRC 비용은 4바이트다.
  - **머리의 스키마 CRC**로 다른 스키마로 만든 파일을 잘못 여는 일을 막는다.
  - 한계 : 한 행씩 `pwrite`하므로 100만 건 적재가 약 20초다. 문자열 길이 변경(스키마 변경)은 지원하지 않는다.
- **행 경계, 문자열 처리** : 행 경계는 길이 접두로 정해진다. 줄바꿈은 경계가 아니므로 문자열에 들어가도 된다.
  문자열은 UTF-8이고, 짝 없는 서로게이트처럼 UTF-8로 못 쓰는 글자는 `INVALID_VALUE`다.
- **위치 표현** : 행 기록 시작의 **바이트 위치(`long`)**. 행 번호 방식은 가변 길이 행에서 위치를 찾으려면 따로
  색인이 필요해 바이트 위치를 골랐다. `readRowAt`은 `FileChannel.read(buffer, position)`으로 요청마다 버퍼를
  새로 만들어 읽으므로 여러 스레드가 함께 불러도 된다.
- **쓰기 안전** : INSERT는 행 기록 전체를 한 버퍼로 만들어 파일 끝 위치에 쓴다. 쓰다 실패하면 쓰던 부분을
  잘라낸다. 프로세스가 쓰는 도중 죽어 마지막 행이 덜 쓰이면 **다음에 열 때 온전한 마지막 행까지만 남기고
  잘라낸다**(경고를 stderr에 출력). 머리가 손상됐거나 스키마와 다르면 그 테이블은 열지 않는다(`INTERNAL_ERROR`).
- 같은 구현의 Phase 2·3은 Phase 1이 만든 파일을 그대로 연다. 파일 형식이 바뀌지 않았다.

## 3. B+ 트리 (Phase 2)

- **구조와 차수** : `index/BPlusTree`. 키는 id(`int`), 값은 행의 바이트 위치(`long`). 노드 하나의 키 최대 개수가
  차수이고 기본값은 64, 생성자로 바꾼다(테스트는 3·4). 리프는 `int[] keys`·`long[] values`와 다음 리프 참조,
  내부 노드는 `int[] keys`와 `Node[] children`이다. `children[i]`에는 `keys[i-1]` 이상 `keys[i]` 미만이 들어 있다.
  100만 건에서 높이는 4다.
- **분할 방식** : 키가 차수+1개가 되면 나눈다.
  - 리프 : 앞쪽 `(n+1)/2`개를 남기고 나머지를 새 리프로 옮기며, 새 리프의 첫 키를 부모에 **복사**해 올린다. 리프 연결을 이어 붙인다.
  - 내부 노드 : 가운데 키를 부모로 **올리고**(새 노드에 남기지 않음) 오른쪽 절반을 새 노드로 옮긴다.
  - 루트가 나뉘면 새 루트를 만들어 높이가 1 늘어난다. 같은 키를 다시 넣으면 `IllegalArgumentException`이고
    트리는 바뀌지 않는다(삽입은 되돌아오는 길에서만 노드를 바꾼다).
  - 삭제·병합·재분배는 구현하지 않았다. (DELETE가 없다. 협약 9.2)
  - `checkInvariants()`가 키 정렬, 부모 구분 키 범위, 모든 리프 깊이 같음, 리프 연결 오름차순을 검사한다(테스트용).
- **인덱스와 파일의 연결** : 테이블마다 `TableState`가 `BPlusTree`, 다음 id, 읽기·쓰기 락을 가진다.
  - 시작할 때 `DbEngine`이 `*.schema`가 있는 모든 테이블의 `readRows`로 행을 읽어 `insertKey(id, 위치)`로 넣고
    다음 id를 `최대 id + 1`로 정한다. (재시작 뒤에도 id가 이어진다)
  - INSERT : `id 부여 → appendRow → insertKey → 다음 id 증가`. 인덱스 등록이 실패하면 `rollbackAppend`로
    파일에서 그 행을 잘라낸다. 되돌리기도 실패하면 그 테이블을 고장 상태로 표시해 `INTERNAL_ERROR`를 돌려준다.
  - `SELECT ... WHERE id = 정수` : `findValue` → `readRowAt` 한 번(읽은 행의 id가 맞는지 확인).
    그 밖(다른 컬럼 조건, WHERE 없음)은 `readRows` 선형 탐색이다. 결과 객체의 `AccessPath`(INDEX / FULL_SCAN)로 확인한다.
  - 인덱스는 테이블 락 안에서만 접근한다. 엔진이 열린 뒤에 생긴 스키마 파일도 처음 쓸 때 읽어 인덱스를 만든다.

### 성능 비교 결과

| 레코드 수 | 조회 방식 | 평균(ms) | 중앙값(ms) | p95(ms) | 배수 |
|---|---|---|---|---|---|
| 1,000,000 | `WHERE id = 500000` (인덱스) | 0.1091 | 0.1123 | 0.1541 | 1x |
| 1,000,000 | `WHERE name = 'product_0500000'` (선형) | 623.6502 | 597.9549 | 874.9323 | 5,325x |
| 100,000 | `WHERE id = 50000` (인덱스) | 0.1114 | 0.1076 | 0.1439 | 1x |
| 100,000 | `WHERE name = ...` (선형) | 67.2207 | 66.0602 | 81.6240 | 614x |
| 10,000 | `WHERE id = 5000` (인덱스) | 0.2309 | 0.1947 | 0.4336 | 1x |
| 10,000 | `WHERE name = ...` (선형) | 7.7374 | 7.3664 | 10.3834 | 38x |

선형 탐색은 건수에 비례해 늘고(10만→100만에서 약 9배) 인덱스는 거의 그대로(트리 높이 4)다. 인덱스는 O(log n) 탐색 뒤 행 하나만 읽고, 선형은 O(n)으로 모든 행을 읽고 해석한다. 이 수치는 이 환경·조건에서의 관찰값이다. 전체 결과는 아래와 같다.

- 실행 환경: JDK 21.0.13.0.2+1-LTS, Windows 11 amd64, CPU 코어 8개, 최대 힙 8164MB
- 데이터: 1,000,000건, 적재 방법 SQL, 레코드 products(id int, name ≤32바이트, price int), 시드 20261116
- 측정: 예열 3회 뒤 번갈아 10회, 무작위 id 인덱스 1000개·선형 20개
- 배수는 같은 대상의 인덱스 중앙값 대비 중앙값 비율이다

### 10,000건 (적재 1.59초, 트리 높이 3)

| 대상 | 조회 방식 | 평균(ms) | 중앙값(ms) | p95(ms) | 배수 |
|---|---|---|---|---|---|
| id=1 | `WHERE id = ?` (인덱스) | 0.6036 | 0.3294 | 2.6919 | 1.0x |
| id=1 | `WHERE id = ?` (강제 선형) | 13.8651 | 10.9273 | 24.6969 | 33.2x |
| id=1 | `WHERE name = ?` (선형) | 13.7638 | 13.3751 | 23.9452 | 40.6x |
| id=5,000 | `WHERE id = ?` (인덱스) | 0.2309 | 0.1947 | 0.4336 | 1.0x |
| id=5,000 | `WHERE id = ?` (강제 선형) | 8.3861 | 8.2475 | 13.3005 | 42.4x |
| id=5,000 | `WHERE name = ?` (선형) | 7.7374 | 7.3664 | 10.3834 | 37.8x |
| id=10,000 | `WHERE id = ?` (인덱스) | 0.1752 | 0.1749 | 0.2130 | 1.0x |
| id=10,000 | `WHERE id = ?` (강제 선형) | 7.1977 | 6.9981 | 9.4528 | 40.0x |
| id=10,000 | `WHERE name = ?` (선형) | 6.9701 | 6.6988 | 9.5147 | 38.3x |
| id=10,001 | `WHERE id = ?` (인덱스) | 0.2005 | 0.0856 | 0.7419 | 1.0x |
| id=10,001 | `WHERE id = ?` (강제 선형) | 8.9741 | 8.9786 | 10.9707 | 105.0x |
| id=10,001 | `WHERE name = ?` (선형) | 8.9827 | 8.8725 | 13.4072 | 103.7x |
| 무작위 id 1000개 | 인덱스 | 0.1284 | 0.0795 | 0.2608 | 1.0x |
| 무작위 id 20개 | `WHERE name = ?` (선형) | 9.3345 | 9.1254 | 12.1573 | 114.9x |

### 100,000건 (적재 3.41초, 트리 높이 4)

| 대상 | 조회 방식 | 평균(ms) | 중앙값(ms) | p95(ms) | 배수 |
|---|---|---|---|---|---|
| id=1 | `WHERE id = ?` (인덱스) | 0.1724 | 0.1128 | 0.5174 | 1.0x |
| id=1 | `WHERE id = ?` (강제 선형) | 67.7686 | 64.1184 | 94.1880 | 568.7x |
| id=1 | `WHERE name = ?` (선형) | 64.4086 | 60.0516 | 87.7749 | 532.6x |
| id=50,000 | `WHERE id = ?` (인덱스) | 0.1114 | 0.1076 | 0.1439 | 1.0x |
| id=50,000 | `WHERE id = ?` (강제 선형) | 64.1028 | 65.4435 | 78.5191 | 608.2x |
| id=50,000 | `WHERE name = ?` (선형) | 67.2207 | 66.0602 | 81.6240 | 613.9x |
| id=100,000 | `WHERE id = ?` (인덱스) | 0.0671 | 0.0624 | 0.0930 | 1.0x |
| id=100,000 | `WHERE id = ?` (강제 선형) | 46.3306 | 44.4030 | 54.9843 | 712.2x |
| id=100,000 | `WHERE name = ?` (선형) | 49.9301 | 47.1340 | 61.3171 | 756.0x |
| id=100,001 | `WHERE id = ?` (인덱스) | 0.0336 | 0.0285 | 0.0616 | 1.0x |
| id=100,001 | `WHERE id = ?` (강제 선형) | 48.6380 | 45.8125 | 67.3292 | 1610.3x |
| id=100,001 | `WHERE name = ?` (선형) | 51.0309 | 50.9171 | 67.3012 | 1789.7x |
| 무작위 id 1000개 | 인덱스 | 0.0558 | 0.0508 | 0.0983 | 1.0x |
| 무작위 id 20개 | `WHERE name = ?` (선형) | 47.4468 | 46.9753 | 54.5072 | 924.7x |

### 1,000,000건 (적재 22.09초, 트리 높이 4)

| 대상 | 조회 방식 | 평균(ms) | 중앙값(ms) | p95(ms) | 배수 |
|---|---|---|---|---|---|
| id=1 | `WHERE id = ?` (인덱스) | 0.1320 | 0.0894 | 0.3310 | 1.0x |
| id=1 | `WHERE id = ?` (강제 선형) | 519.9018 | 471.7095 | 918.7437 | 5276.4x |
| id=1 | `WHERE name = ?` (선형) | 551.5566 | 504.5337 | 917.9485 | 5643.6x |
| id=500,000 | `WHERE id = ?` (인덱스) | 0.1091 | 0.1123 | 0.1541 | 1.0x |
| id=500,000 | `WHERE id = ?` (강제 선형) | 609.6057 | 546.4119 | 909.2072 | 4865.6x |
| id=500,000 | `WHERE name = ?` (선형) | 623.6502 | 597.9549 | 874.9323 | 5324.6x |
| id=1,000,000 | `WHERE id = ?` (인덱스) | 0.0898 | 0.0873 | 0.1794 | 1.0x |
| id=1,000,000 | `WHERE id = ?` (강제 선형) | 661.7123 | 552.0919 | 1225.4070 | 6327.7x |
| id=1,000,000 | `WHERE name = ?` (선형) | 736.1668 | 527.2785 | 1786.3273 | 6043.3x |
| id=1,000,001 | `WHERE id = ?` (인덱스) | 0.0261 | 0.0247 | 0.0329 | 1.0x |
| id=1,000,001 | `WHERE id = ?` (강제 선형) | 648.0530 | 412.3778 | 1478.6650 | 16729.3x |
| id=1,000,001 | `WHERE name = ?` (선형) | 633.1599 | 446.7829 | 1337.2204 | 18125.1x |
| 무작위 id 1000개 | 인덱스 | 0.0754 | 0.0595 | 0.1512 | 1.0x |
| 무작위 id 20개 | `WHERE name = ?` (선형) | 714.4004 | 719.2761 | 939.2334 | 12098.8x |

- 재시작 뒤 인덱스 재구성: 1.41초 (1,000,000건). 재시작 전후 조회 결과 일치 확인
- 테이블 파일: 35,000,012 바이트 (행당 평균 35 바이트)


## 4. 서버 (Phase 3)

- **서버 구조** : `클라이언트 ─HTTP─▶ JDK HttpServer ─▶ DbWorkerPool(작업 큐 + 워커 N개) ─▶ SqlExecutor(DbEngine.execute) ─▶ JSON 응답`.
  `server`는 `DbEngine.execute`만 부른다(`SqlExecutor`는 `DbEngine::execute`를 끼우는 얇은 인터페이스이고,
  테스트가 느린 실행기를 끼워 큐 포화를 재현하는 데 쓴다).
- **스레드 풀 구성** : `DbWorkerPool`은 `Executor`를 구현한다. 시작할 때 `db-worker-N` 스레드를 `N`개 만들고,
  각 워커가 `ArrayBlockingQueue<Runnable>`(크기 기본 128)에서 작업을 꺼내 반복 실행한다. 요청마다 새 스레드를 만들지 않는다.
  작업이 던진 `Throwable`은 요청 단위로 잡아 워커가 죽지 않는다. 워커 수 기본값은 CPU 수다(`--workers`, `--queue`로 변경).
  워커를 interrupt하지 않는다(파일 채널이 interrupt되면 닫히기 때문). 종료 : 새 작업 거절 → 큐가 빌 때까지 실행 → 워커 종료.
- **큐 포화 처리 (거절 방법)** : 큐가 가득 차면 기다리지 않고 거절한다. 그런데 JDK `HttpServer`는 "소켓에서 요청을
  읽고 핸들러를 부르는 일" 전체를 풀에 맡기므로, `execute`가 예외만 던지면 연결이 그냥 끊겨 클라이언트가 503을
  받지 못한다. 그래서 **거절된 작업은 풀을 부른 스레드(서버의 접속 수락 스레드)에서 그 자리에서 실행**하고,
  그 동안 `pool.isRejectingCurrentTask()`가 참이 되게 한다(`ThreadLocal`). 핸들러는 이 값을 보고 DB 작업 없이
  `503 {"error":"SERVER_BUSY"}`와 `Connection: close`만 돌려준다. 짧은 일이라 수락 스레드를 오래 잡지 않는다.
  단점 : 요청 헤더를 읽는 동안은 수락 스레드가 그 클라이언트를 기다린다(느린 클라이언트에 약하다). `/health`도 포화 중에는 503이다.
- **공유 상태와 지키는 잠금**

  | 공유 상태 | 위치 | 지키는 것 |
  |---|---|---|
  | 테이블 맵 (이름 → `TableState`) | `DbEngine.tables` | `ConcurrentHashMap`. 처음 여는 일은 `loadMonitor`(synchronized)로 한 번만 |
  | B+ 트리 | `TableState.index` | 테이블의 `ReentrantReadWriteLock` (INSERT=쓰기, SELECT=읽기) |
  | 다음 id | `TableState.nextId` | 같은 쓰기 락 |
  | 파일 끝 위치, 파일 채널 | `TableHandle` | 쓰기는 쓰기 락 + `synchronized(handle)`, 읽기는 위치 지정 읽기라 락 없이 안전 |
  | 고장 표시 | `TableState.failureMessage` | `volatile` |
  | 작업 큐 | `DbWorkerPool.taskQueue` | `ArrayBlockingQueue` + 받기/종료 경합은 `acceptLock` |

  쓰기 락은 `id 할당 → 파일 추가 → 인덱스 등록 → (실패 시) 복구`까지 유지한다. SELECT는 읽기 락 안에서 결과 행을
  완성하고, JSON 변환과 응답 전송은 락을 푼 뒤 서버에서 한다. 결과 `DbResult`는 만든 뒤 바뀌지 않는다.
- **잠금 순서** : 한 문장은 한 테이블만 잠그므로 두 테이블을 동시에 잠그는 경로가 없고 교착 상태가 생기지 않는다.
  나중에 여러 테이블을 잠그는 문장(JOIN 등)을 넣으면 테이블 이름 사전순으로 잠가야 한다(협약 9.3).
  `ReentrantReadWriteLock`은 비공정 모드다. 다만 Java의 비공정 모드도 대기 줄 맨 앞이 쓰기이면 새 읽기를
  기다리게 하므로, 읽기가 계속 들어와도 쓰기가 끝없이 밀리지는 않는다. 도착 순서를 엄격히 지키지는 않는다.
- **API 형식**

  | 요청 | 응답 |
  |---|---|
  | `POST /sql` `Content-Type: application/json`, 본문 `{"sql":"..."}` (문자열 필드 하나만, 세미콜론으로 끝나는 한 문장) | 성공 200 + 공통 JSON(`{"id":1}`, `{"rows":[...]}`) |
  | `GET /health` | 200 `{"status":"ok"}` |
  | 잘못된 JSON, `sql` 누락·중복·타입 오류·빈 값, 추가 필드, JSON이 아닌 Content-Type·UTF-8이 아닌 charset | 400 `BAD_REQUEST` |
  | 구문 오류, 세미콜론 누락, 여러 문장 | 400 `SQL_SYNTAX` |
  | 없는 테이블/컬럼, 잘못된 값 | 400 `UNKNOWN_TABLE` / `UNKNOWN_COLUMN` / `INVALID_VALUE` |
  | 없는 경로 | 404 `NOT_FOUND` |
  | 허용하지 않는 메서드 | 405 `METHOD_NOT_ALLOWED` + `Allow` 헤더 |
  | 본문이 1MiB 초과 | 413 `PAYLOAD_TOO_LARGE` (8MiB까지는 읽어서 버리고, 그보다 크면 연결을 닫는다) |
  | 큐 포화 | 503 `SERVER_BUSY` |
  | 엔진 내부 오류, 예상 못한 예외 | 500 `INTERNAL_ERROR` |

  오류 응답에는 오류 코드만 담고 내부 경로·스택은 담지 않는다(자세한 원인은 서버 로그 stderr에만).
  `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `PAYLOAD_TOO_LARGE`는 협약 오류 표에 없어 서버 안에서만 쓴다(`ErrorCode`는 건드리지 않았다).
  요청 JSON의 `\"`, `\\`, `\n`, `\uXXXX`를 처리하고 응답 JSON 문자열은 항상 이스케이프한다.

### 처리량 결과 (워커 수 1, 2, 4, 8)

- 실행 환경: JDK 21.0.13.0.2+1-LTS, Windows 11 amd64, CPU 코어 8개
- 데이터 100000건, 동시 클라이언트 16개, 단계별 요청 2000건 (선형 탐색 20건), 서버와 클라이언트가 같은 프로세스

| 워커 수 | id 조회(인덱스) req/s | name 조회(선형) req/s | INSERT req/s |
|---|---|---|---|
| 1 | 313 | 9.7 | 630 |
| 2 | 1036 | 15.7 | 1238 |
| 4 | 1309 | 26.7 | 2004 |
| 8 | 1651 | 25.1 | 2747 |

해석 : 인덱스 조회와 INSERT는 워커를 늘릴수록 처리량이 늘었다(클라이언트와 서버가 같은 CPU를 나눠 쓰므로 선형 증가는 아니다). 선형 탐색은 4개 이후 늘지 않았다. INSERT는 쓰기 락으로 한 번에 하나씩 실행되지만, 락 밖의 HTTP·JSON 처리가 병렬이라 늘었다.

## 5. 외부 의존성

- 실행 의존성 **없음**. JDK 표준 라이브러리(`com.sun.net.httpserver`, `java.nio`, `java.util.concurrent`)만 쓴다.
- 테스트 : JUnit 5 (`junit-jupiter` 5.11.4, 매개변수 테스트 포함). 빌드 플러그인은 compiler 3.13.0, surefire 3.5.2, jar 3.4.2.
- JSON : 협약은 라이브러리를 허용하지만, 요청 본문에서 키 중복·추가 필드·타입 오류를 엄격히 가려야 하고
  Maven 의존성·JAR 합치기(shade)를 늘리지 않으려고 `json/JsonReader`·`JsonWriter`를 직접 썼다(약 300줄, 단위 테스트 있음).

## 6. 알려진 제한, 추가 구현 요소

알려진 제한
- 삭제·병합·재분배 없음(DELETE 없음). 스키마 변경(컬럼 추가·길이 변경) 불가 : 머리의 스키마 CRC가 달라 열리지 않는다.
- 행마다 `fsync`하지 않는다. 운영체제가 죽으면 마지막 몇 행이 사라질 수 있다(프로세스만 죽는 경우는 위의 덜 쓰인 행 복구로 안전).
- 데이터 디렉터리 하나는 프로세스 하나만 쓴다(파일 잠금을 걸지 않았다).
- 인덱스는 `WHERE id = 정수` 한 가지에만 쓴다. 인덱스는 메모리에만 있어 재시작할 때 파일 전체를 읽어 다시 만든다.
- 선형 탐색은 `SELECT`의 결과 행을 모두 메모리에 모은다. 행이 아주 많은 `SELECT *`는 힙을 많이 쓴다.
- 한 테이블의 INSERT는 쓰기 락으로 한 번에 하나씩이다. 100만 건 선형 탐색(약 0.5초)이 읽기 락을 잡은 동안 그 테이블의 INSERT는 기다린다.
- 인덱스 등록 실패 뒤 파일을 되돌리는 경로(`registerIndex` → `rollbackAppend`)는 정상 흐름에서 일어나지 않아
  엔진 수준 테스트가 없다. `TableFile.rollbackAppend` 자체만 단위 테스트했다.
- 큐 포화 때의 503은 접속 수락 스레드에서 만들어 느린 클라이언트에 약하다(4장).
- 서버 로그는 JDK 기본 인코딩으로 stdout/stderr에 쓴다. Windows 콘솔이 아닌 곳으로 리다이렉트하면 한글이 깨져 보일 수 있다.

추가 구현 요소 (협약 17장 후보와 별개로 이번에 같이 넣은 것)
- 행마다 CRC32, 파일 머리 검증, 덜 쓰인 마지막 행 자동 복구.
- `bench --mode throughput` : 워커 수 1·2·4·8 처리량 표.
- `bench --generate-sql` : SQL 파일 생성기(처리기로 따로 적재). `--load direct` : 파싱을 건너뛰고 엔진에 바로 적재.
- 협약 17장 차별화 후보(`EXPLAIN` 등)는 협약대로 기본 기능이 끝난 뒤 팀이 정하므로 넣지 않았다.

협약에 없어 정한 것 (팀 확인 필요)
- 정수 컬럼은 `0 ~ 2147483647`. INSERT의 음수·상한 초과는 `INVALID_VALUE`. WHERE의 상한 초과·음수는 **빈 결과**(오류 아님).
- WHERE의 타입 불일치(`WHERE id = 'a'`, `WHERE name = 5`)는 `INVALID_VALUE`.
- `SELECT name, name`처럼 결과 컬럼이 겹치면 `SQL_SYNTAX`(JSON 키 중복 방지).
- 테이블 이름은 소문자·숫자·밑줄 **1~64자**. 더 길면 파일을 찾지 않고 `UNKNOWN_TABLE`(운영체제 경로 길이 한도 회피).
- CLI 표 출력에서 줄바꿈·탭·제어 문자는 `\n`, `\t`, `\u0001`처럼 보이게 그린다(저장 값은 그대로, `--json`은 원래 이스케이프).
- API는 본문을 먼저 읽은 뒤 Content-Type을 검사한다. 안 읽고 400을 보내면 큰 본문에서 연결이 끊겨 응답을 못 받기 때문이다.
- CLI : 세미콜론뿐인 문장·주석뿐인 파일은 건너뛰고 종료 코드 0, API : `;` 하나는 `SQL_SYNTAX`.
- INSERT 검사 순서 : 없는 컬럼 → id 지정 → 값 개수 → 컬럼 중복 → 타입·범위·길이 → 빠진 필수 컬럼.
- 새 공개 이름 : `DbEngine.execute(String, boolean)`(인덱스 사용 여부 지정), `execute(Statement, boolean)`(파싱 건너뛰기),
  `DbEngine.getIndexSize/getIndexHeight/setSelectHook`(점검·테스트용), `TableFile.rollbackAppend/isValidTableName`,
  `RowCursor`(`Iterator<StoredRow>` + `AutoCloseable`), `SqlSplitter.splitStatements`, `BPlusTree.keysInLeafOrder/checkInvariants`,
  `DbWorkerPool.awaitTermination/isRejectingCurrentTask/getQueuedCount/getRejectedCount`, `SqlExecutor`,
  `DbHttpServer(SqlExecutor, DbWorkerPool, InetSocketAddress)`, 패키지 `minidbms.json`(`JsonReader`, `JsonWriter`, `JsonParseException`).

협약 4장 코드 규칙 점검 (2026-11-16)
- 함수 인자 3개 이하, 함수 30줄 이하, 한 글자 이름은 `i`·`j`만 : 스크립트로 `src/main` 전체를 검사해 맞췄다.
  (`catch`의 `e` → `exception`, `err` → `errorOutput`, 인자가 많던 함수는 `CliOutput`·`KeyRange`·`Phase`·`ReportRow`·`NumberRange` 레코드로 묶음)
- 예외 : 레코드(`Statement`, `SelectPlan`, `BenchmarkOptions` 등)의 구성 요소와 `DbResult`의 private 생성자(7개)는
  "여러 값을 객체 하나로 묶은 것" 자체라 그대로 두었다. `DbResult`는 정적 팩토리 3개(`forInsert`/`forSelect`/`forError`)로만 만든다.

## 7. 핵심 로직을 설명할 때 참고할 파일

| 설명할 것 | 파일 |
|---|---|
| SQL 한 건이 파싱·실행·저장되는 흐름 | `engine/DbEngine.java` (`execute`, `executeInsert`, `executeSelect`) |
| 토큰화·파싱, 문자열 안의 `;` | `sql/SqlTokenizer.java`, `sql/SqlParser.java`, `sql/SqlSplitter.java` |
| 파일 형식, 위치 읽기, 덜 쓰인 행 복구 | `storage/RowCodec.java`, `storage/TableHandle.java`, `storage/TableFile.java` |
| B+ 트리 삽입·분할·검색 | `index/BPlusTree.java` (`insertKey`, `splitLeaf`, `splitInternal`, `findValue`) |
| 인덱스 재구성, 인덱스/선형 선택 | `engine/DbEngine.java` (`loadTable`, `selectRows`) |
| 작업 큐·워커·큐 포화 | `server/DbWorkerPool.java`, `server/DbHttpServer.java` (`createReply`) |
| 읽기·쓰기 잠금과 INSERT 원자성 | `engine/TableState.java`, `engine/DbEngine.java` (`appendRowAndIndex`) |
| 성능 측정 방법 | `bench/BenchmarkRunner.java`, `bench/ThroughputBenchmark.java` |
