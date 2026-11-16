#!/usr/bin/env python3
"""Java 벤치마크 원본에서 재현 조건·표·Mermaid 그래프를 만든다(표준 라이브러리만 사용)."""
import hashlib
import json
import pathlib
import platform
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[5]


def machine_description():
    try:
        cpu = subprocess.check_output(['sysctl', '-n', 'machdep.cpu.brand_string'], text=True).strip()
        memory = int(subprocess.check_output(['sysctl', '-n', 'hw.memsize'], text=True))
        return {'cpu': cpu, 'memoryBytes': memory, 'platform': platform.platform()}
    except (OSError, subprocess.CalledProcessError):
        return {'cpu': platform.processor(), 'platform': platform.platform()}


def source_digest():
    digest = hashlib.sha256()
    for path in sorted((ROOT / 'src/main/java').rglob('*.java')):
        digest.update(str(path.relative_to(ROOT)).encode())
        digest.update(path.read_bytes())
    digest.update((ROOT / 'pom.xml').read_bytes())
    return digest.hexdigest()


def main():
    directory = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ROOT / 'target/benchmarks-minimal')
    reports = sorted((json.loads(path.read_text()) for path in directory.glob('*.json')),
                     key=lambda report: report['records'])
    if not reports:
        raise SystemExit('no benchmark reports')
    machine = machine_description()
    raw = {'machine': machine, 'sourceSha256': source_digest(), 'runs': reports}
    (ROOT / 'docs/benchmark_results.json').write_text(json.dumps(raw, ensure_ascii=False, indent=2) + '\n')
    environment = reports[-1]['environment']
    lines = ['# 미니 DBMS 실측', '', f"환경: {machine['platform']}, {machine['cpu']}.",
             f"JDK {environment['javaVendor']} {environment['javaVersion']}, "
             f"CPU {environment['processors']}개, 최대 JVM heap {environment['maxHeapBytes']} bytes.", '',
             '- 사용자의 최신 지시에 따라 100만 건 측정을 중단하고 최소 규모로 실행했다.',
             '- 이번 값은 동작 확인용이며 성능 우위를 일반화할 표본 수가 아니다.',
             '- Java 21 `release 21`, `./mvnw package`, 동일 실행 JAR, `-Xms128m -Xmx512m`.',
             '- 각 크기는 새 빈 폴더에 공통 SQL 처리기로 INSERT. 이름 `product_0000001`, price=id.',
             '- 테이블 헤더 44 bytes, 행 44 bytes. B+ 트리 기본 키 최대 64개.',
             '- 파일 적재·재시작 인덱스 재구성·JSON 변환은 조회 측정에서 제외하고 따로 기록.',
             f"- 각 고정 조건은 예열 {reports[-1]['warmups']}회 뒤 {reports[-1]['repeats']}회. 실행 순서는 반복마다 교대.",
             f"- 무작위는 seed {reports[-1]['seed']}의 같은 ID {reports[-1]['randomQueries']}개를 세 경로에서 한 번씩 조회.",
             '- 매 조회의 결과와 접근 경로를 확인한다. 인덱스/강제 스캔은 동일 id SQL이다.',
             '- INDEX_ID는 인덱스 위치 직접 읽기, SCAN_ID는 같은 id 강제 스캔(찾으면 종료), '
             'SCAN_NAME은 같은 행을 반환하는 이름 조건의 전체 스캔이다.',
             '- 전부 OS 페이지 캐시가 예열된 동일 프로세스의 비교다. JVM GC와 스케줄링 지연도 포함한다.',
             '', '| 레코드 수 | SQL 적재 ms | 인덱스 재구성 ms | 파일 bytes |', '|---:|---:|---:|---:|']
    for report in reports:
        lines.append(f"| {report['records']:,} | {report['loadMs']:.3f} | {report['rebuildMs']:.3f} | {report['tableBytes']:,} |")
    lines += ['', '무작위 ID 조회 요약:', '',
              '| 레코드 수 | 경로 | 평균 ms | 중앙값 ms | p95 ms | 중앙값 비율 |',
              '|---:|---|---:|---:|---:|---:|']
    for report in reports:
        for row in report['measurements']:
            if row['scenario'] == 'random':
                lines.append(f"| {report['records']:,} | {row['path']} | {row['meanMs']:.6f} | "
                             f"{row['medianMs']:.6f} | {row['p95Ms']:.6f} | {row['ratio']:.2f}x |")
    if len(reports) >= 2:
        paths = ['INDEX_ID', 'SCAN_ID', 'SCAN_NAME']
        series = [[next(row['meanMs'] for row in report['measurements']
                        if row['scenario'] == 'random' and row['path'] == path) for report in reports]
                  for path in paths]
        maximum = max(value for values in series for value in values) * 1.1
        lines += ['', '```mermaid', 'xychart-beta', '    title "Mean query latency by record count"',
                  '    x-axis "records" [' + ', '.join(str(report['records']) for report in reports) + ']',
                  f'    y-axis "ms" 0 --> {maximum:.6f}']
        for values in series:
            lines.append('    line [' + ', '.join(f'{value:.6f}' for value in values) + ']')
        lines += ['```', '', '그래프의 선은 INDEX_ID, SCAN_ID, SCAN_NAME 순서다.']
    lines += ['', '첫/중간/마지막/없는 ID의 상세 측정:', '',
              '| 레코드 수 | 조건 | 경로 | 평균 ms | 중앙값 ms | p95 ms | 반환 행 |',
              '|---:|---|---|---:|---:|---:|---:|']
    for report in reports:
        for row in report['measurements']:
            if row['scenario'] != 'random':
                lines.append(f"| {report['records']:,} | {row['scenario']} | {row['path']} | "
                             f"{row['meanMs']:.6f} | {row['medianMs']:.6f} | {row['p95Ms']:.6f} | {row['rowCount']} |")
    lines += ['', '원본 표본·무작위 ID·측정 시각·환경은 [benchmark_results.json](benchmark_results.json)에 '
              '보존한다. JSON/표 출력의 별도 시간은 `target/benchmarks-minimal/*.log`의 `[printReport]` 줄에 남는다.', '',
              '재현:', '', '```sh', 'export JAVA_HOME=$(/usr/libexec/java_home -v 21) # macOS',
              './mvnw package', 'sh src/main/java/minidbms/bench/run_benchmarks.sh', '```', '',
              '선형 탐색은 ID 위치에 따라 읽는 양이 다르며, 이름 조건은 전체를 검사한다. '
              '수치는 이 환경과 예열 조건의 결과이며 다른 디스크·JVM·데이터 크기에 그대로 적용하지 않는다.']
    (ROOT / 'docs/benchmark.md').write_text('\n'.join(lines) + '\n')
    print('Wrote docs/benchmark.md and docs/benchmark_results.json')


if __name__ == '__main__':
    main()
