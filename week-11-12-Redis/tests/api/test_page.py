"""시각화 화면 테스트. 사용법 : python3 tests/api/test_page.py 127.0.0.1:6380"""
import http.client
import sys


def send(address, method, path):
    connection = http.client.HTTPConnection(address, timeout=5)
    connection.request(method, path)
    response = connection.getresponse()
    body = response.read()
    headers = {name.lower(): value for name, value in response.getheaders()}
    connection.close()
    return response.status, headers, body


def main():
    address = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1:6380"
    failures = []

    def check(name, ok):
        print(("통과 : " if ok else "실패 : ") + name)
        if not ok:
            failures.append(name)

    status, headers, body = send(address, "GET", "/")
    check("GET / 는 200", status == 200)
    check("Content-Type이 text/html", headers.get("content-type", "").startswith("text/html"))
    check("본문 길이와 Content-Length가 같다", len(body) == int(headers.get("content-length", -1)))
    check("본문이 HTML 문서", body.startswith(b"<!DOCTYPE html>"))

    status, _, _ = send(address, "GET", "/?from=test")
    check("쿼리가 붙어도 200", status == 200)

    status, headers, _ = send(address, "POST", "/")
    check("POST / 는 405", status == 405)
    check("405는 JSON 응답", headers.get("content-type", "").startswith("application/json"))

    status, headers, _ = send(address, "GET", "/nothing")
    check("정의되지 않은 경로는 그대로 404 JSON", status == 404 and
          headers.get("content-type", "").startswith("application/json"))

    status, headers, _ = send(address, "GET", "/health")
    check("기존 JSON 응답은 그대로", status == 200 and
          headers.get("content-type", "").startswith("application/json"))

    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
