INSERT INTO usage_records (sessionId, modelConfigId, provider, timestamp, inputTokens, outputTokens, estimatedCostYuan, success, latencyMs) VALUES
('qa-regression-001', 1, 'DEEPSEEK', CAST(strftime('%s','now') AS INTEGER) * 1000, 1200, 800, 0.0076, 1, 2300),
('qa-regression-002', 1, 'DEEPSEEK', CAST(strftime('%s','now') AS INTEGER) * 1000, 500, 300, 0.0031, 1, 1800),
('qa-regression-003', 1, 'DEEPSEEK', CAST(strftime('%s','now') AS INTEGER) * 1000, 900, 1500, 0.0123, 1, 3100);
