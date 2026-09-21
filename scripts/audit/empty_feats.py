"""
Scan all Vue page files (.vue under src/views) and classify:
- CALLS_BACKEND: yes/no, and what api
- If 'no backend' - list page as potential empty feature
- Also look at components directory

Note: due to encoding issues with dashboardApi.js, we use errors='ignore'
"""
import re
from pathlib import Path

ROOT = r'd:\DisasterGuard'
files = list(Path(f'{ROOT}/src/views').rglob('*.vue')) + list(Path(f'{ROOT}/src/components').rglob('*.vue'))

empty_features = []
real_features = []
for fp in files:
    rel = str(fp).replace(ROOT + '\\', '').replace('\\', '/')
    try:
        content = open(fp, encoding='utf-8').read()
    except UnicodeDecodeError:
        content = open(fp, encoding='utf-8', errors='ignore').read()
    # Heuristics: must reference some kind of API/Dashboard call
    has_api = bool(re.search(r'dashboardApi\.|fetchSentinel|getAiInsights|request\(|axios\.|weatherService|satelliteCompareService|realtimeService|mapService|ruleEngineService|sentinelService|deviceMapService|driftMonitor|multiModal|aiApi', content))
    # Check for components used (delegation to subcomponents is OK)
    has_components = 'template' in content and ('el-form' in content or 'components/' in content or 'import ' in content)
    # Skip pure layout pages (composers of sub-components): like ProfilePage, SettingsPage, RuleEnginePage
    layout_patterns = [
        'import ProfileInfo',
        'import PasswordChange',
        'import NotificationPreference',
        'import ThemeLanguage',
        'import RuleList',
        'import RuleEditor',
        'import RuleTestPanel',
    ]
    is_layout_page = any(p in content for p in layout_patterns) and not has_api
    # Skip trivial error pages
    if 'ForbiddenPage' in rel or 'ServerError' in rel or 'WelcomePage' in rel:
        continue
    if not has_api and not is_layout_page and 'Template' not in content:
        empty_features.append(rel)
    elif has_api:
        real_features.append(rel)

print('Pages with NO detected API call (potential empty features):')
for p in empty_features:
    print(f'  - {p}')
print(f'\nTotal empty features: {len(empty_features)}')
