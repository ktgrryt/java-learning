SELECT c.name, COALESCE(SUM(o.total), 0) AS paid_total
FROM customers c
LEFT JOIN orders o
  ON o.customer_id = c.id
 AND o.status = 'PAID'
GROUP BY c.id, c.name
ORDER BY c.name;
