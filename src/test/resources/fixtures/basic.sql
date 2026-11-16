-- 세 구현 공통 비교용 기본 SQL (협약 7.2, 10.2)
INSERT INTO products (name, price) VALUES ('키보드', 35000);
SELECT * FROM products;
SELECT name, price FROM products WHERE price = 35000;
SELECT * FROM products WHERE id = 1;
SELECT * FROM products WHERE name = '키보드';
SELECT * FROM products WHERE id = 2;
