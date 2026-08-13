SELECT c.name, SUM(o.total) AS paid_total
FROM customers c
JOIN orders o ON o.customer_id = c.id
WHERE o.status = 'PAID'
GROUP BY c.id, c.name
ORDER BY c.name;
