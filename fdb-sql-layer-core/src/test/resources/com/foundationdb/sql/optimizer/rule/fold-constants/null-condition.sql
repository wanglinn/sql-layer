SELECT name, order_date
  FROM customers INNER JOIN orders ON customers.cid = orders.cid
 WHERE name = SUBSTR(name, NULL, 10)
