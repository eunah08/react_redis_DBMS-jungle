#!/bin/sh
set -eu
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../../../../.." && pwd)
cd "$repo_dir"
if [ -n "${JAVA_HOME:-}" ]; then
    java_command="$JAVA_HOME/bin/java"
else
    java_command=java
fi
output_dir=target/benchmarks-minimal
mkdir -p "$output_dir"
if [ "$#" -eq 0 ]; then
    set -- 100
fi
for record_count in "$@"; do
    echo "Measuring $record_count records (SQL load, 1 repeat, 3 random queries)"
    "$java_command" -Xms128m -Xmx512m -jar target/mini-dbms.jar bench \
        --records "$record_count" --repeats 1 --random-queries 3 --json \
        > "$output_dir/$record_count.json" 2> "$output_dir/$record_count.log"
    python3 - "$output_dir/$record_count.json" <<'PY'
import json, sys
report = json.load(open(sys.argv[1]))
print(f"records={report['records']} load={report['loadMs']:.2f}ms rebuild={report['rebuildMs']:.2f}ms")
for row in report['measurements']:
    if row['scenario'] == 'random':
        print(f"  {row['path']}: mean={row['meanMs']:.6f}ms median={row['medianMs']:.6f}ms p95={row['p95Ms']:.6f}ms")
PY
done
python3 src/main/java/minidbms/bench/summarize_benchmarks.py "$output_dir"
