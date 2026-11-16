# 미니 DBMS

SQL 처리기 → B+ 트리 인덱스 → 스레드 풀 API 서버 순서로 만드는 미니 DBMS.
규칙은 모두 [DBMS_협업.md](DBMS_협업.md)에 있다. 문서가 코드보다 우선한다.

이 상태는 팀 공용 기본 틀이다. 협약이 정한 구조와 공개 함수 이름만 있고 본문은 비어 있다.
각자 `feat/dbms_<이니셜>` 브랜치를 만들어 `TODO(이니셜)` 자리를 채운다.

## 준비물

- JDK 21
- 인터넷 연결 (첫 빌드 때 Maven Wrapper가 Maven과 의존성을 내려받는다)

## 빌드·테스트·실행

```
./mvnw test                  # 단위·기능 테스트 (Windows는 mvnw.cmd)
./mvnw package               # target/mini-dbms.jar 생성

java -jar target/mini-dbms.jar cli [--data-dir 경로] [--json] SQL파일
java -jar target/mini-dbms.jar server --host 127.0.0.1 --port 8080 --workers 4 --queue 128 --data-dir data
java -jar target/mini-dbms.jar bench --records 1000000
```

## 구조

| 경로 | 책임 | 협약 |
|---|---|---|
| `src/main/java/minidbms/Main.java` | 실행 명령 분기 (구현됨) | 10장 |
| `cli/CliMain.java` | SQL 파일 읽기, 문장별 엔진 호출, 표·JSON 출력 | 9.1, 10.1 |
| `engine/DbEngine.java` | 공통 진입점 `execute`, 검증, 실행 경로 선택 | 8.1 |
| `sql/SqlParser.java` | `parseSql`, 파싱 결과는 `Statement` | 7장 |
| `storage/TableFile.java` | `readSchema`, `appendRow`, `readRows`, `readRowAt` | 8장 |
| `index/BPlusTree.java` | `insertKey`, `findValue`, `size`, `height` | 9.2 |
| `server/DbHttpServer.java`, `server/DbWorkerPool.java` | HTTP 변환, 작업 큐와 워커 | 9.3, 10.3 |
| `bench/BenchmarkMain.java` | 100만 건 생성과 성능 비교 | 12장 |
| `src/test/resources/fixtures/` | 세 구현 공통 SQL과 기대 결과 | 11.3 |
| `ai/` | AI 진입 파일 (`CLAUDE.md`, `GEMINI.md`, `AGENTS.md`) | 15장 |

## 각자 정해서 채울 것

- 스키마 파일(`data/<테이블>.schema`) 형식, 테이블 파일 형식, 행 위치의 의미 (7.1, 8.2)
- `DbResult`의 필드, 오류를 던질지 결과로 돌려줄지 (8.1, 10.4)
- 내부 함수와 클래스 (토큰 나누기, 문장 나누기, 노드 분할 등)
- B+ 트리 기본 차수, 큐 포화 거절 방법, bench 옵션 이름

정한 내용은 [docs/implementation_notes.md](docs/implementation_notes.md)에 쓴다.

## 공통 fixture로 비교하기

`data/products.schema`를 자기 형식으로 만든 뒤 (products : name 문자열 32바이트, price 정수)

```
java -jar target/mini-dbms.jar cli --data-dir <빈 폴더> --json src/test/resources/fixtures/basic.sql > out.jsonl
diff out.jsonl src/test/resources/fixtures/basic.expected.jsonl
```

## 여기에 각자 써야 하는 것 (협약 요구)

- 여러 문장 중 하나가 실패했을 때의 정책 (9.1)
- 빈 SQL 파일, 없는 파일 경로의 동작 (13장)
- 없는 경로, 허용하지 않는 메서드, 너무 큰 본문의 응답 방식 (10.3)
- 삭제·병합·재분배를 구현하지 않았다는 것 (9.2)
- 파일 형식과 고른 이유 (8.2)
- 사용한 외부 라이브러리와 이유 (2장)
- 벤치마크 실행 환경과 결과 표 (12장)
