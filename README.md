# 미니 DBMS (EA · SK · JK 통합본)

SQL 한 문장을 받아 파일에 저장하고 읽는 작은 DBMS입니다. Java 21, 외부 라이브러리 없음 (테스트만 JUnit 5).
`DBMS_협업.md` 협약을 기준으로 Phase 1~3을 구현했습니다.
세 팀원 구현(EA 뼈대 + SK의 B+ 트리·SQL 분리기 + JK의 CRC·서버 방어)을 합친 버전이며, 합친 근거는 [MERGE_NOTES.md](MERGE_NOTES.md)에 있습니다.

| Phase | 내용 |
|---|---|
| 1 | SQL 처리기 (INSERT, SELECT, WHERE 컬럼 = 값) + 파일 저장 + cli |
| 2 | `id` B+ 트리 인덱스 + 100만 건 벤치마크 |
| 3 | 스레드 풀 HTTP API 서버 (`/sql`, `/health`) + 동시성 |

## 빌드와 테스트

JDK 21만 있으면 됩니다. (`java -version`이 21인지 확인)

```
mvnw.cmd test        # Windows
./mvnw test          # macOS / Linux
```

## 실행

```
# cli : SQL 파일을 위에서부터 한 문장씩 실행
java -cp target/classes minidbms.Main cli [--data-dir data] [--json] 파일.sql

# server
java -cp target/classes minidbms.Main server [--host 127.0.0.1] [--port 8080] [--workers N] [--queue 128] [--data-dir data]

# bench
java -cp target/classes minidbms.Main bench --records 10000,100000,1000000 [--load-mode parse|direct] [--repeat 10] [--random-count 1000] [--seed 42] [--data-dir 경로] [--generate-sql 파일]

# bench (서버 처리량 : 워커 1/2/4/8개별 req/s)
java -cp target/classes minidbms.Main bench --mode throughput --records 100000 [--requests 2000] [--clients 16]
```

기본 데이터 폴더는 `data/` 입니다. 테이블은 `data/<이름>.schema` 파일로 정의합니다. (`id`는 자동으로 맨 앞에 붙음)

```
# data/products.schema
name string 32
price int
```

## 동작 정책 (협약 9장)

- **cli 실패 정책** : 문장이 실패하면 그 문장에서 멈추고 종료 코드 1입니다. 앞에서 성공한 INSERT는 파일에 남습니다.
- **빈 파일 / 주석뿐인 파일 / `;`뿐인 파일** : 아무것도 하지 않고 종료 코드 0입니다.
- **없는 파일, 잘못된 인자** : 오류를 stderr에 쓰고 종료 코드 1입니다.
- **오류 출력** : stderr. `--json`이면 `{"error":"SQL_SYNTAX"}` 형태 한 줄.
- **세미콜론 없이 끝난 마지막 문장** : `SQL_SYNTAX` 오류입니다.
- **서버 오류 응답** : `400 SQL_SYNTAX / UNKNOWN_TABLE / UNKNOWN_COLUMN / INVALID_VALUE / BAD_REQUEST`, `500 INTERNAL_ERROR`, `503 SERVER_BUSY`.
  협약에 없는 `404 NOT_FOUND`(없는 경로), `405 METHOD_NOT_ALLOWED`(+`Allow` 헤더), `413 PAYLOAD_TOO_LARGE`(본문 1 MiB 초과)를 추가했습니다.
  요청의 `Content-Type`은 검사하지 않습니다.
- **큐가 가득 차면** 새 요청은 `503 SERVER_BUSY`로 즉시 답합니다. 거절 응답은 전용 풀(워커 2, 큐 16)이 쓰므로 수신 스레드가 멈추지 않습니다.
- **지원하지 않는 것** : DELETE, UPDATE, 트리 삭제·병합·재분배, 범위 조회, 트랜잭션.

## 저장 형식

- `data/<테이블>.schema` : 한 줄에 컬럼 하나 (`이름 int` / `이름 string 최대바이트`). `#` 주석, 빈 줄, BOM 허용.
- `data/<테이블>.tbl` : 12바이트 머리(`MDBT0002` + 스키마 CRC32 4바이트) 뒤에 `[4바이트 길이][행 내용][행 CRC32 4바이트]`가 이어지는 이진 파일.
  행 내용이 한 비트라도 바뀌거나 스키마가 다른 파일이면 읽을 때 오류로 막습니다. (예전 `MDBT0001` 파일과는 호환되지 않음)
  정수는 4바이트(big-endian), 문자열은 `[4바이트 길이][UTF-8 바이트]`.
  행의 위치는 파일 안 **바이트 오프셋**이며 B+ 트리가 `id → 오프셋`을 가집니다.
- 이진 길이 접두 형식을 고른 이유 : 쉼표·따옴표·줄바꿈·이모지가 있어도 이스케이프 규칙이 필요 없고, 오프셋으로 한 행만 바로 읽을 수 있습니다.
- 서버나 cli를 다시 켜면 `.tbl`을 한 번 훑어 인덱스를 다시 만들고, 다음 id는 `최대 id + 1` 입니다.
- 마지막 행이 덜 쓰인 파일은 열 때 온전한 행까지만 남기고 잘라냅니다. 머리가 손상되거나 id가 중복된 테이블은 그 테이블만 `INTERNAL_ERROR`로 막고 나머지 테이블은 정상 동작합니다.
- `fsync`는 하지 않습니다. (한계)

## 벤치마크 결과

환경 : 이 결과는 **개발 중 확인용 참고값**입니다. (2 CPU 컨테이너, JDK 21, 힙 1536 MB, `--load-mode direct`, 시드 42, 4개 id x 10회, 예열 2회 제외)
팀 제출용 수치는 본인 PC에서 다시 돌려 아래 표를 갱신하세요.

| 레코드 수 | `WHERE id = ?` 인덱스 중앙값(ms) | `WHERE name = ?` 선형 중앙값(ms) | 강제 전체 스캔 중앙값(ms) | 선형 / 인덱스 |
|---|---|---|---|---|
| 10,000 | 0.0738 | 1.6088 | 1.4402 | 약 22배 |
| 100,000 | 0.0404 | 14.6196 | 13.7925 | 약 362배 |
| 1,000,000 | 0.0397 | 116.8839 | 115.4911 | 약 2,945배 |

레코드가 늘어도 인덱스 조회 시간은 거의 그대로이고, 전체 스캔은 레코드 수에 비례해 늘어납니다.

### 서버 처리량 (`--mode throughput`, 10만 건, 클라이언트 16개)

| 워커 수 | id 조회(인덱스) req/s | name 조회(선형) req/s | INSERT req/s |
|---|---|---|---|
| 1 | 336 | 44.7 | 356 |
| 2 | 359 | 91.0 | 358 |
| 4 | 360 | 93.8 | 358 |
| 8 | 358 | 99.8 | 357 |

서버와 클라이언트가 같은 프로세스·2 CPU에서 돌렸기 때문에 인덱스 조회와 INSERT는 클라이언트 쪽이 먼저 한계에 닿아 워커를 늘려도 그대로입니다.
CPU를 많이 쓰는 선형 탐색에서만 워커 1→2개에서 약 2배 늘어납니다. 코어가 더 많은 PC에서 다시 재 보세요.

## 더 읽을 것

구현 설명과 협약과 달라진 점은 [docs/implementation_notes.md](docs/implementation_notes.md)에 있습니다.
