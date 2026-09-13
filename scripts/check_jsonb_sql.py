"""Validate all JSON-like literals in data.sql for JSONB compatibility"""
import re
import json

with open(r'D:\earthquake\backend\src\main\resources\data.sql', encoding='utf-8') as f:
    data = f.read()

# Find all string literals that look like JSON (start with { or [)
# We need to handle nested quotes carefully
errors = []
total = 0

# Pattern: capture any single-quoted string starting with { or [
# Match nested quotes by counting
for m in re.finditer(r"'(\{[\s\S]*?\}| \[[\s\S]*?\])'", data):
    val = m.group(1)
    total += 1
    try:
        json.loads(val)
    except json.JSONDecodeError as e:
        errors.append((e.msg, e.lineno, val[:100]))

print(f'JSON literals found: {total}')
print(f'Errors: {len(errors)}')
for msg, line, val in errors[:20]:
    print(f'  Line {line}: {msg}')
    print(f'    Value: {val}')

# Also check for any trailing commas (JSONB rejects)
trailing_comma = []
for m in re.finditer(r"'(\{[^{}]*(\{[^{}]*\}[^{}]*)*\})'", data):
    val = m.group(1)
    if re.search(r',\s*[}\]]', val):
        trailing_comma.append(val[:80])

print(f'\nTrailing comma issues: {len(trailing_comma)}')
for v in trailing_comma[:10]:
    print(f'  - {v}')
