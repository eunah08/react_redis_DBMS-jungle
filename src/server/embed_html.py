#!/usr/bin/env python3
"""HTML 파일을 C 배열 파일로 바꾼다. 사용법 : embed_html.py <입력.html> <출력.c>"""
import sys


def main():
    source_path, output_path = sys.argv[1], sys.argv[2]
    with open(source_path, "rb") as source:
        data = source.read()
    lines = []
    for start in range(0, len(data), 16):
        lines.append("    " + ",".join("0x%02x" % byte for byte in data[start:start + 16]) + ",")
    with open(output_path, "w", encoding="utf-8") as output:
        output.write("/* 자동 생성 파일 : src/server/embed_html.py. 직접 고치지 않는다 */\n")
        output.write('#include "page.h"\n\n')
        output.write("const unsigned char visualization_html[] = {\n")
        output.write("\n".join(lines) + "\n};\n")
        output.write("const size_t visualization_html_len = %d;\n" % len(data))


if __name__ == "__main__":
    main()
