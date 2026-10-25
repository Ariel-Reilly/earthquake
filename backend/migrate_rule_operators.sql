-- =====================================================================
-- 规则引擎 operator 格式迁移脚本
-- 作用：将已有数据库中 rule 表的 conditions JSON 中的短语形式 operator
--       转换为符号形式，使其与 RuleEvaluator 的期望格式一致。
--
-- 执行前必须备份 rule 表。
-- 执行方式：psql -U user -d database -f migrate_rule_operators.sql
-- =====================================================================

BEGIN;

-- 迁移 R001：gt → >（2 个 operator）
UPDATE rule 
SET conditions = jsonb_set(
  conditions, 
  '{conditions}', 
  (SELECT jsonb_agg(
    CASE 
      WHEN item->>'operator' = 'gt' THEN item || jsonb_build_object('operator', '>')
      ELSE item
    END
  ) FROM jsonb_array_elements(conditions->'conditions') AS item)
)
WHERE rule_code = 'R001';

-- 迁移 R002：gt → >（2 个 operator）
UPDATE rule 
SET conditions = jsonb_set(
  conditions, 
  '{conditions}', 
  (SELECT jsonb_agg(
    CASE 
      WHEN item->>'operator' = 'gt' THEN item || jsonb_build_object('operator', '>')
      ELSE item
    END
  ) FROM jsonb_array_elements(conditions->'conditions') AS item)
)
WHERE rule_code = 'R002';

-- 迁移 R003：gt → >（2 个 operator）
UPDATE rule 
SET conditions = jsonb_set(
  conditions, 
  '{conditions}', 
  (SELECT jsonb_agg(
    CASE 
      WHEN item->>'operator' = 'gt' THEN item || jsonb_build_object('operator', '>')
      ELSE item
    END
  ) FROM jsonb_array_elements(conditions->'conditions') AS item)
)
WHERE rule_code = 'R003';

-- 迁移 R004：eq → ==（1 个）+ gt → >（1 个）
UPDATE rule 
SET conditions = jsonb_set(
  conditions, 
  '{conditions}', 
  (SELECT jsonb_agg(
    CASE 
      WHEN item->>'operator' = 'eq' THEN item || jsonb_build_object('operator', '==')
      WHEN item->>'operator' = 'gt' THEN item || jsonb_build_object('operator', '>')
      ELSE item
    END
  ) FROM jsonb_array_elements(conditions->'conditions') AS item)
)
WHERE rule_code = 'R004';

-- 迁移 R005：gte → >=（1 个）+ lt → <（1 个）
UPDATE rule 
SET conditions = jsonb_set(
  conditions, 
  '{conditions}', 
  (SELECT jsonb_agg(
    CASE 
      WHEN item->>'operator' = 'gte' THEN item || jsonb_build_object('operator', '>=')
      WHEN item->>'operator' = 'lt' THEN item || jsonb_build_object('operator', '<')
      ELSE item
    END
  ) FROM jsonb_array_elements(conditions->'conditions') AS item)
)
WHERE rule_code = 'R005';

-- 迁移 R006：gt → >（2 个 operator）
UPDATE rule 
SET conditions = jsonb_set(
  conditions, 
  '{conditions}', 
  (SELECT jsonb_agg(
    CASE 
      WHEN item->>'operator' = 'gt' THEN item || jsonb_build_object('operator', '>')
      ELSE item
    END
  ) FROM jsonb_array_elements(conditions->'conditions') AS item)
)
WHERE rule_code = 'R006';

-- 验证迁移结果
SELECT 
  rule_code, 
  name, 
  conditions 
FROM rule 
WHERE rule_code IN ('R001', 'R002', 'R003', 'R004', 'R005', 'R006') 
ORDER BY rule_code;

COMMIT;
