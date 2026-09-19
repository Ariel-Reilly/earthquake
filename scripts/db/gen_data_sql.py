"""
生成大规模 data.sql —— 按 dependency 顺序追加业务表 + AI 表 + 设备指标历史。

跑：python scripts/gen_data_sql.py > backend/src/main/resources/data.sql
"""
from __future__ import annotations

import random
from datetime import datetime, timedelta, timezone
from pathlib import Path

random.seed(42)

# ---------------------------------------------------------------------- #
# 1) 基础字典：sys_role / region / sys_user / sys_menu
# ---------------------------------------------------------------------- #
SECTION_HEADER = """
-- =====================================================================
-- DisasterGuard 种子数据（v1.5.0 起 demo 全量入库 —— 零虚拟数据）
--   * 启动顺序严格按 dependency：region → hazard → device → task →
--     inspection_record → warning_rule → warning_event → emergency →
--     file_attachment / risk_score_log → device_metric → ai_comprehensive_score
--     → ai_device_health
--   * 每个 INSERT 段都用 ON CONFLICT DO NOTHING 兜底（schema.sql 已建表，
--     重复启动幂等不报错）
--   * 完整规模：region 12 / hazard 25 / device 60 / task 40 / record 150
--     / warning 50 / emergency 10 / metric 960 / ai_comprehensive 25
--     / ai_device_health 60 —— 总计约 1300 行
-- =====================================================================
"""

ROLES = [
    ("admin", "管理员", "拥有全部模块访问权限", "*", "*"),
    ("attendant", "值班员", "负责态势、巡检和预警处置", "overview,hazards,tasks,warnings,emergency,analytics,ai-insight,weather", "overview:export,hazards:create,hazards:edit,tasks:create,tasks:edit,warnings:confirm,warnings:dispatch,warnings:close,emergency:dispatch,emergency:close,analytics:export,ai:insight:view,ai:insight:refresh,weather:view"),
    ("maintenance", "运维员", "负责设备和基础设施运维", "overview,hazards,devices,analytics,ai-insight,weather", "overview:export,hazards:export,devices:create,devices:edit,devices:delete,devices:export,analytics:export,ai:insight:view,weather:view"),
]

# 12 个区域：1 县 + 4 镇 + 7 村
REGIONS = [
    ("100000", "龙川县", "city", "100000", 1),
    ("100100", "龙安镇", "town", "100000/100100", 2),
    ("100101", "龙安镇青龙村", "village", "100000/100100/100101", 3),
    ("100102", "龙安镇石桥村", "village", "100000/100100/100102", 4),
    ("100200", "石桥镇", "town", "100000/100200", 5),
    ("100201", "石桥镇大岭村", "village", "100000/100200/100201", 6),
    ("100202", "石桥镇云台村", "village", "100000/100200/100202", 7),
    ("100300", "清河镇", "town", "100000/100300", 8),
    ("100301", "清河镇白石沟村", "village", "100000/100300/100301", 9),
    ("100302", "清河镇云台山村", "village", "100000/100300/100302", 10),
    ("100400", "清溪镇", "town", "100000/100400", 11),
    ("100401", "清溪镇石门沟村", "village", "100000/100400/100401", 12),
]

USERS = [
    ("admin", "李晨曦", "13800000000", "admin", "100000", "启用"),
    ("attendant", "周小雨", "13800000001", "attendant", "100100", "启用"),
    ("maintenance", "陈晓峰", "13800000002", "maintenance", "100000", "启用"),
]

MENUS = [
    ("overview", "态势总览", "/", "OverviewPage", "LayoutDashboard", "overview:view", 1),
    ("hazards", "灾害点管理", "/hazards", "HazardsListPage", "Mountain", "hazards:view", 2),
    ("tasks", "巡检任务", "/tasks", "TasksPage", "ListChecks", "tasks:view", 3),
    ("devices", "监测设备", "/devices", "DevicesPage", "Radio", "devices:view", 4),
    ("warnings", "预警中心", "/warnings", "WarningsPage", "Bell", "warnings:view", 5),
    ("emergency", "应急处置", "/emergency", "EmergencyPage", "ShieldCheck", "emergency:view", 6),
    ("analytics", "统计分析", "/analytics", "AnalyticsPage", "Activity", "analytics:view", 7),
    ("ai-insight", "AI 综合分析", "/ai-insight", "AiInsightPage", "Sparkles", "ai:insight:view", 8),
    ("weather", "气象监测", "/weather", "WeatherPage", "CloudRain", "weather:view", 9),
]

# ---------------------------------------------------------------------- #
# 2) hazard_point — 25 条覆盖 6 大灾害类型
# ---------------------------------------------------------------------- #
HAZARD_TEMPLATES = [
    # (name, hazard_type, risk_level, region_code, lon, lat, threat_object)
    ("青龙坡滑坡隐患点", "滑坡", "高风险", "100101", 107.210, 30.610, "居民区"),
    ("大岭村崩塌点", "崩塌", "关注", "100201", 107.340, 30.480, "学校"),
    ("白石沟泥石流", "泥石流", "正常", "100301", 107.550, 30.420, "道路"),
    ("云台山地裂缝", "地裂缝", "关注", "100302", 107.610, 30.670, "通信基站"),
    ("石门沟滑坡点", "滑坡", "正常", "100401", 107.140, 30.560, "农田"),
    ("桥头滑坡隐患", "滑坡", "高风险", "100101", 107.225, 30.625, "桥梁"),
    ("云台村崩塌点", "崩塌", "关注", "100202", 107.355, 30.495, "居民区"),
    ("清河滑坡群", "滑坡", "高风险", "100301", 107.560, 30.435, "村庄"),
    ("石门沟泥石流隐患", "泥石流", "关注", "100401", 107.150, 30.570, "道路"),
    ("大岭地裂缝群", "地裂缝", "关注", "100201", 107.350, 30.490, "学校"),
    ("清溪镇沉降点", "地面沉降", "关注", "100400", 107.420, 30.510, "建筑群"),
    ("清河镇塌陷点", "塌陷", "高风险", "100302", 107.620, 30.680, "道路"),
    ("桥头东崩塌", "崩塌", "正常", "100101", 107.230, 30.630, "居民区"),
    ("云台山西滑坡", "滑坡", "关注", "100302", 107.615, 30.675, "通信基站"),
    ("石桥镇沉降带", "地面沉降", "正常", "100200", 107.345, 30.485, "居民区"),
    ("清溪泥石流沟", "泥石流", "正常", "100400", 107.425, 30.520, "农田"),
    ("大岭村东滑坡", "滑坡", "关注", "100201", 107.355, 30.495, "学校"),
    ("白石沟西崩塌", "崩塌", "正常", "100301", 107.555, 30.425, "道路"),
    ("石门沟东地裂缝", "地裂缝", "正常", "100401", 107.155, 30.575, "通信基站"),
    ("清河镇南塌陷", "塌陷", "关注", "100302", 107.625, 30.685, "建筑群"),
    ("云台村北沉降", "地面沉降", "高风险", "100202", 107.360, 30.500, "居民区"),
    ("桥头东地裂缝", "地裂缝", "正常", "100101", 107.235, 30.635, "农田"),
    ("清溪镇南滑坡", "滑坡", "正常", "100400", 107.430, 30.525, "道路"),
    ("大岭村西崩塌", "崩塌", "高风险", "100201", 107.345, 30.485, "学校"),
    ("白石沟北泥石流", "泥石流", "关注", "100301", 107.560, 30.430, "道路"),
]

# ---------------------------------------------------------------------- #
# 3) monitoring_device — 60 条覆盖 4 种设备类型
# ---------------------------------------------------------------------- #
DEVICE_TYPES = ["GNSS位移", "裂缝计", "倾角计", "雨量计"]

def gen_devices_for_hazard(hazard_code: str, hazard_type: str) -> list[tuple[str, str, str, str, str, int, str, str, str]]:
    """为每个 hazard 生成 2-4 个设备。"""
    # 滑坡/崩塌 → 多 GNSS + 裂缝计；泥石流 → 雨量计为主；地裂缝/沉降 → 倾角计；塌陷 → GNSS
    if hazard_type in ("滑坡", "崩塌"):
        types = ["GNSS位移", "裂缝计", "雨量计"]
    elif hazard_type == "泥石流":
        types = ["雨量计", "GNSS位移"]
    elif hazard_type in ("地裂缝", "地面沉降"):
        types = ["倾角计", "裂缝计", "GNSS位移"]
    else:  # 塌陷
        types = ["GNSS位移", "倾角计"]

    n = random.randint(2, 4)
    types = types[:n]
    devs = []
    for i, dtype in enumerate(types, 1):
        dev_code = f"DV-{hazard_code[-3:]}-{i:02d}"
        dev_name = f"{dtype}-{hazard_code[-3:]}-{i:02d}"
        battery = random.randint(70, 100)
        signal = random.choice(["强", "强", "中"])
        online = random.choices(["在线", "在线", "在线", "离线"], weights=[85, 10, 5, 5])[0]
        devs.append((
            dev_code, dev_name, dtype, hazard_code, "100000",
            online, battery, signal, f"{dtype.split('类型')[0][:4]}山坡"
        ))
    return devs

# ---------------------------------------------------------------------- #
# 4) warning_event / emergency_event
# ---------------------------------------------------------------------- #
WARNING_STATUSES = ["待确认", "处理中", "已关闭", "已派单", "已解除"]
EMERGENCY_STATUSES = ["专家复核中", "处置中", "已启动", "已完成", "待派单"]

# ---------------------------------------------------------------------- #
# 5) ai 表生成数据
# ---------------------------------------------------------------------- #
DISASTER_TYPES_CN = ["滑坡", "崩塌", "泥石流", "地裂缝", "地面沉降", "塌陷"]
CONCERN_LABELS = ["稳定", "关注", "预警", "异常严重"]
RISK_LEVELS = ["LOW", "MEDIUM", "HIGH", "CRITICAL"]

def gauss_clamp(base: float, std: float, lo: float, hi: float) -> float:
    v = random.gauss(base, std)
    return max(lo, min(hi, v))


def main() -> None:
    out: list[str] = [SECTION_HEADER]

    # ---------- sys_role ----------
    out.append("-- ===== sys_role =====")
    out.append("""INSERT INTO sys_role (role_code, role_name, description, menu_permissions, button_permissions) VALUES""")
    vals = []
    for rc, rn, desc, mp, bp in ROLES:
        vals.append(f"('{rc}','{rn}','{desc}','{mp}','{bp}')")
    out.append(",\n".join(vals) + "\nON CONFLICT (role_code) DO NOTHING;\n")

    # ---------- region ----------
    out.append("-- ===== region =====")
    out.append("""INSERT INTO region (region_code, region_name, region_level, path, sort_order) VALUES""")
    vals = [f"('{c}','{n}','{lvl}','{p}',{o})" for c, n, lvl, p, o in REGIONS]
    out.append(",\n".join(vals) + "\nON CONFLICT (region_code) DO NOTHING;\n")

    # ---------- sys_user ----------
    out.append("-- ===== sys_user =====")
    out.append("""INSERT INTO sys_user (username, password_hash, real_name, phone, role_code, region_code, status) VALUES""")
    pwd = "$2b$10$e7J.wokpqTMslzHzJq/ateJNEhEnLh/pgz0gXB1HEv1mp6qDaI5pq"
    vals = [f"('{u}','{pwd}','{name}','{phone}','{role}','{region}','{status}')"
            for u, name, phone, role, region, status in USERS]
    out.append(",\n".join(vals) + "\nON CONFLICT (username) DO NOTHING;\n")

    # ---------- sys_menu ----------
    out.append("-- ===== sys_menu =====")
    out.append("""INSERT INTO sys_menu (menu_code, menu_name, menu_type, path, component, icon, permission_code, sort_order, visible) VALUES""")
    vals = [f"('{c}','{n}','MENU','{p}','{cmp}','{icon}','{perm}',{o},TRUE)"
            for c, n, p, cmp, icon, perm, o in MENUS]
    out.append(",\n".join(vals) + "\nON CONFLICT (menu_code) DO NOTHING;\n")

    # ---------- hazard_point ----------
    out.append("-- ===== hazard_point (25 个) =====")
    out.append("""INSERT INTO hazard_point (hazard_code, name, hazard_type, risk_level, region_code, owner, status, longitude, latitude, geom, threat_object) VALUES""")
    vals = []
    for i, (name, htype, risk, region, lon, lat, threat) in enumerate(HAZARD_TEMPLATES, 1):
        code = f"HZ-{i:03d}"
        status = f"{random.choice(['位移', '裂缝', '雨量'])} {random.choice(['+12mm', '+8mm', '18mm', '5mm', '2.1°', '8.2mm'])}"
        owner = region[2:8]  # 取乡镇名
        vals.append(f"('{code}','{name}','{htype}','{risk}','{region}','{owner}','{status}',{lon},{lat},ST_SetSRID(ST_MakePoint({lon},{lat}),4326),'{threat}')")
    out.append(",\n".join(vals) + "\nON CONFLICT (hazard_code) DO NOTHING;\n")

    # ---------- monitoring_device ----------
    all_devices: list[tuple] = []
    for i, (name, htype, risk, region, lon, lat, threat) in enumerate(HAZARD_TEMPLATES, 1):
        code = f"HZ-{i:03d}"
        for dev in gen_devices_for_hazard(code, htype):
            all_devices.append(dev + (lon, lat))
    # 截到 60 个
    all_devices = all_devices[:60]

    out.append(f"-- ===== monitoring_device ({len(all_devices)} 个) =====")
    out.append("""INSERT INTO monitoring_device (device_code, device_name, device_type, hazard_code, region_code, online_status, battery_level, signal_level, last_report_at, install_location, longitude, latitude, api_key, geom) VALUES""")
    vals = []
    now = datetime.now(timezone.utc)
    for i, (dcode, dname, dtype, hcode, region, online, battery, signal, loc, lon, lat) in enumerate(all_devices):
        last_min = random.randint(1, 30) if online == "在线" else random.randint(60, 240)
        last = (now - timedelta(minutes=last_min)).strftime("%Y-%m-%dT%H:%M:%SZ")
        api_key = f"devkey-{dcode.lower()}-secret"
        vals.append(f"('{dcode}','{dname}','{dtype}','{hcode}','{region}','{online}',{battery},'{signal}',TO_TIMESTAMP('{last}','YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'),'{loc}',{lon},{lat},'{api_key}',ST_SetSRID(ST_MakePoint({lon},{lat}),4326))")
    out.append(",\n".join(vals) + "\nON CONFLICT (device_code) DO NOTHING;\n")

    # ---------- inspection_task ----------
    out.append("-- ===== inspection_task (40 个) =====")
    out.append("""INSERT INTO inspection_task (task_code, name, task_type, executor, region_code, status, progress, start_time, due_time, created_by) VALUES""")
    task_types = ["雨后复查", "应急巡检", "专项排查", "日常巡检", "设备校准"]
    task_executors = ["王建国", "周小雨", "陈晓峰", "刘大壮", "赵晓东", "孙立", "李婷"]
    task_statuses = ["执行中", "待处理", "已完成", "执行中", "执行中", "已派单", "待处理"]
    vals = []
    for i in range(1, 41):
        code = f"TK-{i:03d}"
        name = f"{random.choice(task_types)}-{HAZARD_TEMPLATES[(i-1) % 25][0][-4:]}"
        ttype = random.choice(task_types)
        executor = random.choice(task_executors)
        region = HAZARD_TEMPLATES[(i-1) % 25][3]
        status = random.choice(task_statuses)
        progress = random.randint(20, 100) if status != "待处理" else random.randint(0, 30)
        start_offset_h = random.randint(-72, 1)
        due_offset_h = random.randint(4, 24)
        start = (now + timedelta(hours=start_offset_h)).strftime("%Y-%m-%d %H:%M:%S")
        due = (now + timedelta(hours=due_offset_h)).strftime("%Y-%m-%d %H:%M:%S")
        vals.append(f"('{code}','{name}','{ttype}','{executor}','{region}','{status}',{progress},'{start}','{due}','admin')")
    out.append(",\n".join(vals) + "\nON CONFLICT (task_code) DO NOTHING;\n")

    # ---------- inspection_record ----------
    out.append("-- ===== inspection_record (150 个) =====")
    out.append("""INSERT INTO inspection_record (record_code, task_code, hazard_code, inspector, inspect_at, sign_in_lon, sign_in_lat, geom, distance_m, abnormal_flag, inspection_result, note) VALUES""")
    vals = []
    for i in range(1, 151):
        rec_code = f"IR-{i:04d}"
        task_idx = ((i - 1) % 40) + 1
        task_code = f"TK-{task_idx:03d}"
        hz_idx = ((i - 1) % 25) + 1
        hz_code = f"HZ-{hz_idx:03d}"
        inspector = random.choice(task_executors)
        inspect_offset_h = random.randint(-72, -1)
        inspect_at = (now + timedelta(hours=inspect_offset_h)).strftime("%Y-%m-%d %H:%M:%S")
        lon = HAZARD_TEMPLATES[hz_idx - 1][4]
        lat = HAZARD_TEMPLATES[hz_idx - 1][5]
        dlon = random.gauss(0, 0.0008)
        dlat = random.gauss(0, 0.0008)
        sign_lon = round(lon + dlon, 6)
        sign_lat = round(lat + dlat, 6)
        dist = random.randint(20, 800)
        abnormal = random.random() < 0.18
        result = random.choice([
            "未见明显异常", "发现新增裂缝 3mm", "位移速率趋于平稳",
            "雨量较大排水不畅", "设备工作正常", "坡脚有零星落石", "需加密观测"
        ])
        note = "" if not abnormal else random.choice([
            "建议加密观测", "现场已设警戒", "通知运维复核",
            "上报值班长", "持续跟踪"
        ])
        note_sql = "NULL" if not note else f"'{note}'"
        vals.append(f"('{rec_code}','{task_code}','{hz_code}','{inspector}','{inspect_at}',{sign_lon},{sign_lat},ST_SetSRID(ST_MakePoint({sign_lon},{sign_lat}),4326),{dist},{str(abnormal).upper()},'{result}',{note_sql})")
    out.append(",\n".join(vals) + "\nON CONFLICT (record_code) DO NOTHING;\n")

    # ---------- warning_rule ----------
    out.append("-- ===== warning_rule =====")
    out.append("""INSERT INTO warning_rule (rule_code, rule_name, metric_code, expression, warning_level, enabled, remark) VALUES""")
    vals = [
        "('WR-001','位移速率预警','disp_rate','disp_rate > 5','橙色预警',TRUE,'位移速率超过阈值')",
        "('WR-002','裂缝宽度预警','crack_width','crack_width > 10','黄色预警',TRUE,'裂缝扩展异常')",
        "('WR-003','雨量阈值提醒','rain_1h','rain_1h > 50','蓝色提醒',TRUE,'小时雨量过高')",
        "('WR-004','位移累计预警','disp_total','disp_total > 80','黄色预警',TRUE,'累计位移超过阈值')",
        "('WR-005','倾角预警','tilt','tilt > 2','蓝色提醒',TRUE,'倾角超过警戒值')",
    ]
    out.append(",\n".join(vals) + "\nON CONFLICT (rule_code) DO NOTHING;\n")

    # ---------- warning_event ----------
    out.append("-- ===== warning_event (50 个) =====")
    out.append("""INSERT INTO warning_event (warning_code, rule_code, hazard_code, title, warning_level, trigger_value, status, triggered_at, rule_snapshot) VALUES""")
    rule_metrics = {
        "WR-001": ("disp_rate", 5),
        "WR-002": ("crack_width", 10),
        "WR-003": ("rain_1h", 50),
        "WR-004": ("disp_total", 80),
        "WR-005": ("tilt", 2),
    }
    levels = ["蓝色提醒", "黄色预警", "橙色预警", "红色预警"]
    vals = []
    for i in range(1, 51):
        code = f"WG-{i:03d}"
        rule = random.choice(list(rule_metrics.keys()))
        metric, th = rule_metrics[rule]
        hz_idx = ((i - 1) % 25) + 1
        hz_code = f"HZ-{hz_idx:03d}"
        title = HAZARD_TEMPLATES[hz_idx - 1][0]
        lvl = random.choice(levels)
        trigger = f"{metric}={random.uniform(th, th * 3):.2f}"
        status = random.choice(WARNING_STATUSES)
        triggered_offset_min = random.randint(5, 240)
        triggered = (now - timedelta(minutes=triggered_offset_min)).strftime("%Y-%m-%dT%H:%M:%SZ")
        snapshot = f'{{"metric":"{metric}","threshold":{th}}}'
        vals.append(f"('{code}','{rule}','{hz_code}','{title}','{lvl}','{trigger}','{status}',TO_TIMESTAMP('{triggered}','YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'),'{snapshot}')")
    out.append(",\n".join(vals) + "\nON CONFLICT (warning_code) DO NOTHING;\n")

    # ---------- emergency_event ----------
    out.append("-- ===== emergency_event (10 个) =====")
    out.append("""INSERT INTO emergency_event (event_code, warning_code, title, status, commander, step_name, result_summary, started_at) VALUES""")
    vals = []
    for i in range(1, 11):
        code = f"EM-{i:03d}"
        wg_idx = ((i - 1) % 50) + 1
        wg_code = f"WG-{wg_idx:03d}"
        hz_idx = ((i - 1) % 25) + 1
        title = HAZARD_TEMPLATES[hz_idx - 1][0]
        status = random.choice(EMERGENCY_STATUSES)
        commander = random.choice(["自然资源局值班组", "应急管理局", "现场指挥部", "专家会商组"])
        step = random.choice(["会商", "现场处置", "专家复核", "应急疏散", "警戒设置"])
        summary = random.choice([
            "正在研判滑坡演化趋势", "已转移受威胁居民 32 人", "正在组织专家会商",
            "现场警戒设置完毕", "已启动三级应急响应", "无人机航拍复核中"
        ])
        started_offset = random.randint(5, 120)
        started = (now - timedelta(minutes=started_offset)).strftime("%Y-%m-%dT%H:%M:%SZ")
        vals.append(f"('{code}','{wg_code}','{title}','{status}','{commander}','{step}','{summary}',TO_TIMESTAMP('{started}','YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'))")
    out.append(",\n".join(vals) + "\nON CONFLICT (event_code) DO NOTHING;\n")

    # ---------- file_attachment (保留原 3 条) ----------
    out.append("-- ===== file_attachment =====")
    out.append("""INSERT INTO file_attachment (business_type, business_code, file_name, file_size, file_type, storage_path, file_url, uploader)
SELECT * FROM (VALUES
  ('hazard', 'HZ-001', '现场照片.jpg', 1240000, 'image/jpeg', 'hazard/HZ-001/现场照片.jpg', '/disasterguard/hazard/HZ-001/现场照片.jpg', 'admin'),
  ('task', 'TK-001', '巡检轨迹.kml', 86000, 'application/vnd.google-earth.kml+xml', 'task/TK-001/巡检轨迹.kml', '/disasterguard/task/TK-001/巡检轨迹.kml', 'admin'),
  ('warning', 'WG-001', '处置记录.docx', 420000, 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'warning/WG-001/处置记录.docx', '/disasterguard/warning/WG-001/处置记录.docx', 'admin')
) AS v(business_type, business_code, file_name, file_size, file_type, storage_path, file_url, uploader)
WHERE NOT EXISTS (SELECT 1 FROM file_attachment);
""")

    # ---------- risk_score_log ----------
    out.append("-- ===== risk_score_log =====")
    out.append("""INSERT INTO risk_score_log (hazard_code, score, risk_level, factors)
SELECT * FROM (VALUES
  ('HZ-001', 86.50, '高风险', '{"disp_rate":6.2,"rain_1h":58}'::jsonb),
  ('HZ-002', 63.20, '关注', '{"crack_width":18,"rain_1h":40}'::jsonb),
  ('HZ-004', 61.00, '关注', '{"rain_1h":58,"tilt":2.1}'::jsonb)
) AS v(hazard_code, score, risk_level, factors)
WHERE NOT EXISTS (SELECT 1 FROM risk_score_log);
""")

    # ---------- device_metric (大块：每 device 16 条历史) ----------
    out.append(f"-- ===== device_metric ({len(all_devices) * 16} 条：每 device 16 条近 48h 历史) =====")
    metric_specs = {
        "GNSS位移": [("disp_rate", 2.0, 1.5), ("disp_total", 30.0, 25.0)],
        "裂缝计":   [("crack_width", 6.0, 4.0)],
        "倾角计":   [("tilt", 1.0, 0.8)],
        "雨量计":   [("rain_1h", 8.0, 10.0), ("rain_24h", 30.0, 35.0)],
    }
    vals = []
    for dev in all_devices:
        dcode, _, dtype, hcode, _, online, _, _, _, _, _ = dev
        hz_idx = int(hcode[-3:]) - 1
        risk = HAZARD_TEMPLATES[hz_idx][2]
        amp = {"高风险": 1.8, "关注": 1.3, "正常": 0.7}[risk]
        specs = metric_specs.get(dtype, metric_specs["GNSS位移"])
        for j in range(16):
            ts = (now - timedelta(hours=48 - j * 3)).strftime("%Y-%m-%d %H:%M:%S")
            for metric, base, std in specs:
                v = random.gauss(base, std * amp)
                if metric.startswith("rain") or metric.startswith("disp"):
                    v = max(0.0, v)
                v = round(v, 3)
                vals.append(f"('{dcode}','{hcode}','{metric}',{v},'{ts}','{{\"seed\":true,\"amp\":{amp}}}')")
    # 批量拼接（每 2000 行一个 INSERT 段，避免单语句过长）
    chunk = 2000
    chunks = [vals[i:i + chunk] for i in range(0, len(vals), chunk)]
    for i, c in enumerate(chunks):
        prefix = "INSERT INTO device_metric (device_code, hazard_code, metric_code, value, recorded_at, raw_payload) VALUES"
        if i == 0:
            out.append(prefix)
            out.append(",\n".join(c) + "\nON CONFLICT DO NOTHING;\n")
        else:
            out.append(prefix)
            out.append(",\n".join(c) + "\nON CONFLICT DO NOTHING;\n")

    # ---------- ai_comprehensive_score (25 条 = 每个 hazard 一条) ----------
    out.append("-- ===== ai_comprehensive_score (25 条 = 每个 hazard 预生成一份) =====")
    out.append("""INSERT INTO ai_comprehensive_score (hazard_code, risk_level, risk_score, source, disaster_type, concern_label, disaster_type_source, disaster_type_confidence, confidence, model, degraded, device_available, analyzed_at) VALUES""")
    vals = []
    for i, (name, htype, risk, region, lon, lat, threat) in enumerate(HAZARD_TEMPLATES, 1):
        code = f"HZ-{i:03d}"
        rl_map = {"高风险": ("HIGH", 72.0), "关注": ("MEDIUM", 48.0), "正常": ("LOW", 22.0)}
        rl, score = rl_map[risk]
        # 加点抖动让分数看起来真实
        score = round(score + random.gauss(0, 5), 2)
        score = max(5.0, min(95.0, score))
        if score >= 60: rl = "HIGH"
        elif score >= 35: rl = "MEDIUM"
        else: rl = "LOW"
        concern = {"LOW": "稳定", "MEDIUM": "关注", "HIGH": "预警", "CRITICAL": "异常严重"}.get(rl, "关注")
        dtype_source = "declared"
        dtype_conf = round(random.uniform(0.85, 0.98), 3)
        conf = round(random.uniform(0.75, 0.92), 3)
        device_avail = "TRUE" if random.random() < 0.7 else "FALSE"
        degraded = "TRUE" if device_avail == "FALSE" else "FALSE"
        model = "xgboost-fusion" if device_avail == "TRUE" else "heuristic-geo-weather+xgboost-fusion"
        analyzed = (now - timedelta(minutes=random.randint(1, 10))).strftime("%Y-%m-%dT%H:%M:%SZ")
        vals.append(f"('{code}','{rl}',{score},'AI_AUTO','{htype}','{concern}','{dtype_source}',{dtype_conf},{conf},'{model}',{degraded},{device_avail},TO_TIMESTAMP('{analyzed}','YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'))")
    out.append(",\n".join(vals) + "\nON CONFLICT (hazard_code) DO NOTHING;\n")

    # ---------- ai_device_health (60 条 = 每个 device 一条) ----------
    out.append(f"-- ===== ai_device_health ({len(all_devices)} 条 = 每个 device 预生成一份) =====")
    out.append("""INSERT INTO ai_device_health (device_code, health_level, health_score, anomaly_score, forecast_risk, metric_coverage, confidence, model, degraded, analyzed_at) VALUES""")
    vals = []
    for dev in all_devices:
        dcode, _, dtype, hcode, _, online, _, _, _, _, _ = dev
        hz_idx = int(hcode[-3:]) - 1
        risk = HAZARD_TEMPLATES[hz_idx][2]
        # 设备健康分 = 100 - 异常分 - 预测风险
        anomaly = random.uniform(5, 70) if risk == "高风险" else (random.uniform(2, 30) if risk == "关注" else random.uniform(0, 12))
        forecast = random.uniform(5, 30) if risk == "高风险" else random.uniform(2, 12)
        health = max(0, min(100, 100 - anomaly * 0.6 - forecast * 0.4))
        if health >= 80: hl = "HEALTHY"
        elif health >= 60: hl = "ATTENTION"
        elif health >= 35: hl = "WARN"
        else: hl = "CRITICAL"
        conf = round(random.uniform(0.7, 0.95), 3)
        model = "ai-anomaly+forecast+adaptive-norm"
        degraded = "TRUE" if online == "离线" else "FALSE"
        analyzed = (now - timedelta(minutes=random.randint(1, 10))).strftime("%Y-%m-%dT%H:%M:%SZ")
        vals.append(f"('{dcode}','{hl}',{round(health,2)},{round(anomaly,2)},{round(forecast,2)},2,{conf},'{model}',{degraded},TO_TIMESTAMP('{analyzed}','YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'))")
    out.append(",\n".join(vals) + "\nON CONFLICT (device_code) DO NOTHING;\n")

    # ---------- operation_log (审计) ----------
    out.append("-- ===== operation_log (30 条近 7 天审计) =====")
    out.append("""INSERT INTO operation_log (user_id, username, action, business_type, business_code, detail, ip_address, created_at)
SELECT * FROM (VALUES""")
    actions = [("CREATE", "hazard"), ("UPDATE", "warning"), ("CLOSE", "emergency"), ("EXPORT", "hazard"), ("REFRESH", "ai-analysis")]
    vals = []
    for i in range(1, 31):
        uid = random.randint(1, 3)
        uname = USERS[uid - 1][0]
        act, btype = random.choice(actions)
        bidx = random.randint(1, 25)
        bcode = f"HZ-{bidx:03d}" if btype == "hazard" else f"WG-{bidx:03d}" if btype == "warning" else f"EM-{((bidx-1)%10)+1:03d}"
        detail = json.dumps({"action": act, "target": bcode}) if False else f'"{act} {btype} {bcode}"'
        ip = f"192.168.1.{random.randint(2, 250)}"
        created = (now - timedelta(days=random.randint(0, 7), hours=random.randint(0, 23))).strftime("%Y-%m-%d %H:%M:%S")
        vals.append(f"({uid},'{uname}','{act}','{btype}','{bcode}','{detail}','{ip}','{created}')")
    out.append(",\n".join(vals) + """) AS v(user_id, username, action, business_type, business_code, detail, ip_address, created_at);
""")

    print("".join(out))


if __name__ == "__main__":
    import json  # noqa: F401
    main()