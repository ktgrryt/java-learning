SELECT c.name, SUM(o.total) AS paid_total
FROM customers c
JOIN orders o
  ON o.customer_id = c.id
WHERE o.status = 'PAID'
GROUP BY c.id, c.name
HAVING SUM(o.total) >= 2000
ORDER BY paid_total DESC, c.name;
