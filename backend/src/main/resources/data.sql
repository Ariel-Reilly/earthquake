-- =====================================================================
-- DisasterGuard v2.0.0 数据初始化脚本（幂等：ON CONFLICT DO NOTHING）
-- 初始化顺序：字典 → 权限 → 角色 → 用户 → 菜单 → 区域
--           → 系统配置 → AI模型 → 数据源 → 同步任务
--           → 规则引擎 → 设备 → 灾害 → AI分析 → 预警 → 应急
--           → 资源/预案/模板 → 会签/审批/消息
--
-- BCrypt 哈希：所有测试账号密码均为 "admin123"
-- BCrypt($2b$, cost=10): $2b$10$sl2SHlS/I0NUwrSWgqXs8e5XRUpLxPi6eRVQakX/0AfeWycTAKU1S
-- =====================================================================

-- =====================================================================
-- 1. 系统字典 sys_dict（灾种/等级/状态/渠道/设备类型/资源类型/通知类型）
-- =====================================================================
INSERT INTO sys_dict (type, label, value, sort_order, description) VALUES
-- 灾种
('disaster_type', '地震',         'earthquake',    1, '地震'),
('disaster_type', '洪水',         'flood',         2, '洪水'),
('disaster_type', '滑坡',         'landslide',     3, '滑坡'),
('disaster_type', '泥石流',       'debris_flow',   4, '泥石流'),
('disaster_type', '火灾',         'fire',          5, '火灾'),
('disaster_type', '台风',         'typhoon',       6, '台风'),
('disaster_type', '干旱',         'drought',       7, '干旱'),
('disaster_type', '内涝',         'waterlogging',  8, '城市内涝'),
('disaster_type', '崩塌',         'collapse',      9, '崩塌'),
('disaster_type', '地裂缝',       'ground_fissure',10,'地裂缝'),
-- R3：地面沉降在其他各端（前端三个页面、后端 WarningService#HAZARD_TYPE_CODES）
-- 均作为一等灾种使用，但字典里缺失，导致"前端有、字典无"的漂移。此处补齐，
-- 使 sys_dict.disaster_type 成为完整的 11 项权威集合（与 utils/hazardTypes.js 一致）。
('disaster_type', '地面沉降',     'subsidence',    11,'地面沉降'),

-- 预警等级
('alert_level', '红色',  'red',    1, 'Ⅰ级（特别重大）'),
('alert_level', '橙色',  'orange', 2, 'Ⅱ级（重大）'),
('alert_level', '黄色',  'yellow', 3, 'Ⅲ级（较大）'),
('alert_level', '蓝色',  'blue',   4, 'Ⅳ级（一般）'),

-- 事件状态（统一状态机）
('event_status', '待认领',        'pending_claim',    1,  'AI结果待值班员认领'),
('event_status', '待初审',        'pending_first',    2,  '值班员初审中'),
('event_status', '待二次确认',    'pending_second',   3,  '专家二次确认中'),
('event_status', '待发布',        'pending_publish',  4,  '预警待发布审批'),
('event_status', '已发布',        'published',        5,  '已发布'),
('event_status', '跟踪中',        'tracking',         6,  '持续监测跟踪'),
('event_status', '已解除',        'resolved',         7,  '已解除'),
('event_status', '已归档',        'archived',         8,  '归档为误报'),
('event_status', '驳回',          'rejected',         9,  '驳回'),

-- 设备类型
('device_type', 'GNSS位移计',    'gnss',            1,  '高精度卫星定位位移监测'),
('device_type', '裂缝计',         'crack_meter',      2,  '裂缝位移监测'),
('device_type', '雨量计',         'rain_gauge',       3,  '降雨量监测'),
('device_type', '水位计',         'water_level',      4,  '地表水位监测'),
('device_type', '倾角计',         'inclinometer',     5,  '边坡倾角监测'),
('device_type', '土壤含水率',      'soil_moisture',    6,  '土壤湿度监测'),
('device_type', '气象站',         'weather_station',  7,  '气象综合监测'),
('device_type', '摄像头',         'camera',           8,  '视觉监控'),
('device_type', '无人机',         'drone',            9,  '无人机巡查'),
('device_type', '地震仪',         'seismograph',     10, '地震动监测'),

-- 通知渠道
('channel_type', '短信',          'sms',       1, 'SMS短信'),
('channel_type', 'APP推送',       'app',       2, '移动应用推送'),
('channel_type', '邮件',          'email',     3, '电子邮件'),
('channel_type', 'LED大屏',       'led',       4, 'LED信息屏'),
('channel_type', 'Webhook',       'webhook',   5, 'HTTP回调'),
('channel_type', '声光报警',       'sound_light',6,'声光报警器'),

-- 应急资源类型
('resource_type', '救援队伍',    'rescue_team',   1, '专业救援队伍'),
('resource_type', '物资',         'supplies',      2, '防汛/救援物资'),
('resource_type', '避难场所',      'shelter',       3, '应急避难场所'),
('resource_type', '装备',          'equipment',     4, '特种装备'),
('resource_type', '车辆',          'vehicle',       5, '应急车辆'),

-- 审批类型
('approval_type', '预警发布',     'WARNING_PUBLISH',   1, '预警发布审批'),
('approval_type', '预警派发',      'WARNING_DISPATCH',  2, '预警派发审批'),
('approval_type', '路径规划',     'ROUTE_PLAN',        3, '路径规划审批'),
('approval_type', '资源指派',     'RESOURCE_ASSIGN',   4, '应急资源指派审批'),

-- AI模型类型
('model_type', 'XGBoost融合',     'xgboost',        1, '梯度提升树融合模型'),
('model_type', 'LSTM时序预测',    'lstm',            2, '长短期记忆网络时序预测'),
('model_type', 'YOLO视觉检测',    'yolo',            3, 'YOLO目标检测'),
('model_type', '综合研判',        'comprehensive',    4, '多模态综合研判融合'),
('model_type', '设备健康评分',    'device_health',    5, '设备健康异常检测'),
('model_type', '灾损评估',        'disaster_assessment',6,'灾害损失评估'),
('model_type', '异常检测',        'isolation_forest', 7, '隔离森林异常检测'),

-- 数据源类型
('datasource_type', '卫星影像',    'satellite',   1, '卫星遥感数据'),
('datasource_type', '气象数据',    'weather',     2, '气象观测/预报数据'),
('datasource_type', 'MQTT设备',    'mqtt',        3, '物联网设备接入'),
('datasource_type', '地形高程',    'terrain',     4, 'DEM地形高程数据'),
('datasource_type', '自定义',      'custom',      5, '第三方自定义API')
ON CONFLICT DO NOTHING;

-- =====================================================================
-- 2. 权限表 sys_permission（与前端 routePermissionMap 完全对应）
-- =====================================================================
INSERT INTO sys_permission (code, name, type, route_path, sort_order) VALUES
-- 综合态势 & 地图
('overview:view',       '查看综合态势',         'ROUTE', '/dashboard',              1),
('overview:export',     '导出综合态势',         'OP',    '/dashboard',              2),
('map:view',            '查看实时地图',          'ROUTE', '/dashboard/real-time-map', 10),
('map:export',          '导出地图',              'OP',    '/dashboard/real-time-map', 11),
('map:edit',           '编辑地图圈画',          'OP',    '/dashboard/real-time-map', 12),
('satellite:view',     '查看时相对比',          'ROUTE', '/dashboard/satellite-compare',13),
('device_map:view',    '查看设备地图',          'ROUTE', '/dashboard/device-map',    14),
('zone:view',          '查看区域大屏',          'ROUTE', '/dashboard/zone',          15),
('weather:view',       '查看气象分析',          'ROUTE', '/dashboard/weather',       16),
('history:view',       '查看历史灾害库',        'ROUTE', '/dashboard/history-hazards',17),
('history:export',     '导出历史灾害',          'OP',    '/dashboard/history-hazards',18),

-- AI任务 & 结果 & 研判 & 模型
('ai:task:view',       '查看AI任务',            'ROUTE', '/dashboard/ai-tasks',     20),
('ai:task:create',     '创建AI任务',            'OP',    '/dashboard/ai-tasks',     21),
('ai:task:edit',       '编辑AI任务',            'OP',    '/dashboard/ai-tasks',     22),
('ai:task:delete',     '删除AI任务',            'OP',    '/dashboard/ai-tasks',     23),
('ai:task:retry',      '重试AI任务',            'OP',    '/dashboard/ai-tasks',     24),
('ai:task:terminate',  '终止AI任务',            'OP',    '/dashboard/ai-tasks',     25),
('ai:result:view',     '查看AI结果',            'ROUTE', '/dashboard/ai-results',   30),
('ai:result:transfer', 'AI结果转人工确认',      'OP',    '/dashboard/ai-results',   31),
('ai:result:review',   'AI结果复核',            'OP',    '/dashboard/ai-results',   32),
('ai:insight:view',    '查看AI综合研判',        'ROUTE', '/dashboard/ai-insight',  40),
('ai:insight:refresh', '刷新AI研判',            'OP',    '/dashboard/ai-insight',  41),
('ai:model:view',      '查看AI模型',            'ROUTE', '/dashboard/ai-models',   50),
('ai:model:manage',    '管理AI模型',            'OP',    '/dashboard/ai-models',   51),
('ai:model:edit',      '编辑AI模型参数',        'OP',    '/dashboard/ai-models',   52),

-- 灾害 & 确认台 & 会签
('hazards:view',       '查看灾害列表',          'ROUTE', '/dashboard/hazards',     60),
('hazards:create',     '创建灾害',              'OP',    '/dashboard/hazards',     61),
('hazards:edit',       '编辑灾害',              'OP',    '/dashboard/hazards',     62),
('hazards:delete',     '删除灾害',              'OP',    '/dashboard/hazards',     63),
('hazards:export',     '导出灾害',              'OP',    '/dashboard/hazards',     64),
('confirmation:view',  '查看人工确认台',        'ROUTE', '/dashboard/confirmation-workbench',70),
('confirmation:claim', '认领确认任务',          'OP',    '/dashboard/confirmation-workbench',71),
('confirmation:confirm','初审/二次确认',        'OP',    '/dashboard/confirmation-workbench',72),
('confirmation:reject', '驳回确认任务',          'OP',    '/dashboard/confirmation-workbench',73),
('confirmation:transfer','转派确认任务',        'OP',    '/dashboard/confirmation-workbench',74),
('approval:view',      '查看审批中心',          'ROUTE', '/dashboard/approvals',   80),
('approval:sign',      '会签审批',              'OP',    '/dashboard/confirmation-sign',  81),
('approval:initiate',  '发起审批',              'OP',    '/dashboard/approvals',   82),
('approval:approve',   '审批通过',              'OP',    '/dashboard/approvals',   83),
('approval:reject',    '审批拒绝',              'OP',    '/dashboard/approvals',   84),
('sign:view',          '查看会签',              'ROUTE', '/dashboard/confirmation-sign',  90),
('sign:create',        '发起会签',              'OP',    '/dashboard/confirmation-sign',  91),
('sign:confirm',       '会签确认',              'OP',    '/dashboard/confirmation-sign',  92),

-- 预警 & 应急 & 资源
('warnings:view',       '查看预警',              'ROUTE', '/dashboard/warnings',    100),
('warnings:create',     '创建预警',              'OP',    '/dashboard/warnings',   101),
('warnings:confirm',   '确认预警',              'OP',    '/dashboard/warnings',   102),
('warnings:dispatch',  '派发预警',              'OP',    '/dashboard/warnings',   103),
('warnings:close',     '关闭预警',              'OP',    '/dashboard/warnings',   104),
('warnings:publish',   '发布预警',              'OP',    '/dashboard/warnings',   105),
('warnings:export',    '导出预警',              'OP',    '/dashboard/warnings',   106),
('warnings:resend',    '重发预警',              'OP',    '/dashboard/warnings/:id/delivery',107),
('emergency:view',     '查看应急事件',          'ROUTE', '/dashboard/emergency',   110),
('emergency:start',    '启动应急',              'OP',    '/dashboard/emergency',   111),
('emergency:dispatch', '应急派发',              'OP',    '/dashboard/emergency',   112),
('emergency:close',    '关闭应急',              'OP',    '/dashboard/emergency',   113),
('emergency:export',   '导出应急',              'OP',    '/dashboard/emergency',   114),
('resource:view',      '查看资源调度',          'ROUTE', '/dashboard/resource-dispatch',120),
('resource:dispatch',  '资源调度',              'OP',    '/dashboard/resource-dispatch',121),
('resource:assign',    '资源指派',              'OP',    '/dashboard/resource-dispatch',122),
('resource:create',    '新增资源',              'OP',    '/dashboard/resource-dispatch',123),
('resource:edit',      '编辑资源',              'OP',    '/dashboard/resource-dispatch',124),
('route:view',         '查看路径规划',          'ROUTE', '/dashboard/route-planning',130),
('route:plan',         '规划路径',              'OP',    '/dashboard/route-planning',131),
('route:approve',      '审批路径',              'OP',    '/dashboard/route-planning',132),
('route:export',       '导出路径',              'OP',    '/dashboard/route-planning',133),

-- 设备 & 数据源 & 同步
('devices:view',       '查看设备',              'ROUTE', '/dashboard/devices',     140),
('devices:create',     '创建设备',              'OP',    '/dashboard/devices',     141),
('devices:edit',       '编辑设备',              'OP',    '/dashboard/devices',     142),
('devices:delete',     '删除设备',              'OP',    '/dashboard/devices',     143),
('devices:export',     '导出设备',              'OP',    '/dashboard/devices',     144),
('devices:control',    '设备控制',              'OP',    '/dashboard/devices',     145),
('devices:ota',        '设备OTA升级',           'OP',    '/dashboard/devices/ota', 146),
('datasource:view',    '查看数据源',            'ROUTE', '/dashboard/data-sources',150),
('datasource:create',  '创建数据源',            'OP',    '/dashboard/data-sources',151),
('datasource:edit',    '编辑数据源',            'OP',    '/dashboard/data-sources',152),
('datasource:delete',  '删除数据源',            'OP',    '/dashboard/data-sources',153),
('datasource:config',  '配置数据源',            'OP',    '/dashboard/data-sources',154),
('sync:view',          '查看数据同步',          'ROUTE', '/dashboard/data-sync',   160),
('sync:manage',        '管理数据同步',          'OP',    '/dashboard/data-sync',   161),
('rule:view',          '查看规则引擎',          'ROUTE', '/dashboard/rule-engine', 170),
('rule:create',        '创建规则',              'OP',    '/dashboard/rule-engine', 171),
('rule:edit',          '编辑规则',              'OP',    '/dashboard/rule-engine', 172),
('rule:delete',        '删除规则',              'OP',    '/dashboard/rule-engine', 173),

-- 系统管理
('analytics:view',     '查看统计分析',          'ROUTE', '/dashboard/analytics',  180),
('analytics:export',   '导出统计',              'OP',    '/dashboard/analytics',  181),
('reports:view',       '查看报表中心',          'ROUTE', '/dashboard/reports',     190),
('reports:create',     '创建报表',              'OP',    '/dashboard/reports',     191),
('reports:delete',     '删除报表',              'OP',    '/dashboard/reports',     192),
('users:view',         '查看用户',              'ROUTE', '/dashboard/user-management',200),
('users:create',       '创建用户',              'OP',    '/dashboard/user-management',201),
('users:edit',         '编辑用户',              'OP',    '/dashboard/user-management',202),
('users:delete',       '删除用户',              'OP',    '/dashboard/user-management',203),
('roles:view',         '查看角色',              'ROUTE', '/dashboard/user-management',204),
('roles:edit',         '编辑角色',              'OP',    '/dashboard/user-management',205),
('audit:view',         '查看日志审计',          'ROUTE', '/dashboard/audit-log',  210),
('audit:export',       '导出审计日志',          'OP',    '/dashboard/audit-log',  211),
('monitor:view',       '查看系统监控',          'ROUTE', '/dashboard/system-monitor',220),
('monitor:alert',      '处理监控告警',          'OP',    '/dashboard/system-monitor',221),
('backup:view',        '查看备份恢复',          'ROUTE', '/dashboard/backup-recovery',230),
('backup:manage',      '管理备份',              'OP',    '/dashboard/backup-recovery',231),
('settings:view',       '查看系统设置',          'ROUTE', '/dashboard/settings',    240),
('settings:edit',       '编辑系统设置',          'OP',    '/dashboard/settings',    241),
('data:catalog:view',   '查看数据目录',          'ROUTE', '/dashboard/data-catalog',250),
('messages:view',       '查看消息中心',          'ROUTE', '/dashboard/message-center',260),
('messages:send',       '发送消息',              'OP',    '/dashboard/message-center',261),
('todo:view',           '查看待办中心',          'ROUTE', '/dashboard/todo',       270),
('notice:view',         '查看通知模板',          'ROUTE', '/dashboard/notice-templates',280),
('notice:edit',         '编辑通知模板',          'OP',    '/dashboard/notice-templates',281),
('plans:view',          '查看应急预案',          'ROUTE', '/dashboard/plans',       290),
('plans:edit',          '编辑应急预案',          'OP',    '/dashboard/plans',       291),
('plans:launch',        '启动应急预案',          'OP',    '/dashboard/plans',       292),
('profile:view',        '查看个人中心',          'ROUTE', '/dashboard/profile',    300),
('profile:edit',        '编辑个人中心',          'OP',    '/dashboard/profile',    301)
ON CONFLICT (code) DO NOTHING;

-- =====================================================================
-- 3. 角色表 sys_role（7角色，替换旧3角色数据）
-- =====================================================================
INSERT INTO sys_role (role_code, role_name, description) VALUES
('admin',               '超管',         '平台最高权限，拥有全部模块访问权限，负责系统初始化和极端故障恢复'),
('system_admin',        '系统管理员',   '负责系统运维、日志审计和用户管理，管系统不管业务'),
('emergency_commander', '应急指挥',     '灾害发生时的最高指挥官，负责态势研判、预警发布和资源调度'),
('attendant',           '值班员',       '7×24 小时盯屏，AI 初筛的第一响应人，负责初审和草稿起草'),
('expert',              '领域专家',     '提供专业背书，防止 AI 误报漏报，负责二次确认和会签复核'),
('ai_engineer',         'AI工程师',     '负责模型迭代、规则配置和数据管道，造模型的不管业务'),
('maintenance',         '现场运维',     '负责设备安装、抢修和状态回报，一线设备保障')
ON CONFLICT (role_code) DO NOTHING;

-- =====================================================================
-- 4. 角色-权限关联 sys_role_permission（超管全量，其余按矩阵精确授权）
-- =====================================================================
-- 超管：全部权限
INSERT INTO sys_role_permission (role_id, perm_code)
SELECT r.id, p.code
FROM sys_role r, sys_permission p
WHERE r.role_code = 'admin'
ON CONFLICT (role_id, perm_code) DO NOTHING;

-- 系统管理员
INSERT INTO sys_role_permission (role_id, perm_code) VALUES
((SELECT id FROM sys_role WHERE role_code='system_admin'),'overview:view'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'overview:export'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'analytics:view'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'analytics:export'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'history:view'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'history:export'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'reports:view'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'reports:create'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'reports:delete'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'users:view'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'users:create'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'users:edit'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'users:delete'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'roles:view'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'roles:edit'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'audit:view'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'audit:export'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'monitor:view'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'backup:view'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'backup:manage'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'sync:view'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'settings:view'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'settings:edit'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'messages:view'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'messages:send'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'todo:view'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'profile:view'),
((SELECT id FROM sys_role WHERE role_code='system_admin'),'profile:edit')
ON CONFLICT (role_id, perm_code) DO NOTHING;

-- 应急指挥
INSERT INTO sys_role_permission (role_id, perm_code) VALUES
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'overview:view'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'overview:export'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'map:view'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'map:edit'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'satellite:view'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'device_map:view'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'zone:view'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'ai:task:view'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'ai:result:view'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'ai:insight:view'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'ai:insight:refresh'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'hazards:view'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'hazards:create'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'hazards:edit'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'confirmation:view'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'sign:view'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'sign:create'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'sign:confirm'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'warnings:view'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'warnings:create'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'warnings:publish'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'warnings:dispatch'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'warnings:close'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'warnings:export'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'warnings:resend'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'notice:view'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'plans:view'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'plans:edit'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'plans:launch'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'emergency:view'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'emergency:start'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'emergency:dispatch'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'emergency:close'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'resource:view'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'resource:dispatch'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'resource:assign'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'resource:create'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'resource:edit'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'route:view'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'route:plan'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'route:approve'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'route:export'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'devices:view'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'approval:view'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'approval:approve'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'approval:reject'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'reports:view'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'reports:create'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'analytics:view'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'analytics:export'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'profile:view'),
((SELECT id FROM sys_role WHERE role_code='emergency_commander'),'profile:edit')
ON CONFLICT (role_id, perm_code) DO NOTHING;

-- 值班员
INSERT INTO sys_role_permission (role_id, perm_code) VALUES
((SELECT id FROM sys_role WHERE role_code='attendant'),'overview:view'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'overview:export'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'map:view'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'map:edit'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'satellite:view'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'device_map:view'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'zone:view'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'ai:task:view'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'ai:task:create'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'ai:result:view'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'ai:result:transfer'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'ai:insight:view'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'ai:insight:refresh'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'hazards:view'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'hazards:create'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'hazards:edit'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'confirmation:view'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'confirmation:claim'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'confirmation:confirm'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'confirmation:reject'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'confirmation:transfer'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'warnings:view'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'warnings:create'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'warnings:confirm'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'warnings:dispatch'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'warnings:export'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'notice:view'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'notice:edit'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'plans:view'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'plans:edit'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'plans:launch'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'emergency:view'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'emergency:dispatch'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'devices:view'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'weather:view'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'analytics:view'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'reports:view'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'approval:view'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'approval:initiate'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'sign:view'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'sign:create'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'route:view'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'route:export'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'messages:view'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'todo:view'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'settings:view'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'profile:view'),
((SELECT id FROM sys_role WHERE role_code='attendant'),'profile:edit')
ON CONFLICT (role_id, perm_code) DO NOTHING;

-- 领域专家
INSERT INTO sys_role_permission (role_id, perm_code) VALUES
((SELECT id FROM sys_role WHERE role_code='expert'),'overview:view'),
((SELECT id FROM sys_role WHERE role_code='expert'),'zone:view'),
((SELECT id FROM sys_role WHERE role_code='expert'),'ai:task:view'),
((SELECT id FROM sys_role WHERE role_code='expert'),'ai:result:view'),
((SELECT id FROM sys_role WHERE role_code='expert'),'ai:result:review'),
((SELECT id FROM sys_role WHERE role_code='expert'),'ai:insight:view'),
((SELECT id FROM sys_role WHERE role_code='expert'),'ai:model:view'),
((SELECT id FROM sys_role WHERE role_code='expert'),'hazards:view'),
((SELECT id FROM sys_role WHERE role_code='expert'),'hazards:create'),
((SELECT id FROM sys_role WHERE role_code='expert'),'hazards:edit'),
((SELECT id FROM sys_role WHERE role_code='expert'),'confirmation:view'),
((SELECT id FROM sys_role WHERE role_code='expert'),'confirmation:claim'),
((SELECT id FROM sys_role WHERE role_code='expert'),'confirmation:confirm'),
((SELECT id FROM sys_role WHERE role_code='expert'),'confirmation:reject'),
((SELECT id FROM sys_role WHERE role_code='expert'),'confirmation:transfer'),
((SELECT id FROM sys_role WHERE role_code='expert'),'approval:view'),
((SELECT id FROM sys_role WHERE role_code='expert'),'approval:sign'),
((SELECT id FROM sys_role WHERE role_code='expert'),'approval:approve'),
((SELECT id FROM sys_role WHERE role_code='expert'),'approval:reject'),
((SELECT id FROM sys_role WHERE role_code='expert'),'sign:view'),
((SELECT id FROM sys_role WHERE role_code='expert'),'sign:create'),
((SELECT id FROM sys_role WHERE role_code='expert'),'sign:confirm'),
((SELECT id FROM sys_role WHERE role_code='expert'),'warnings:view'),
((SELECT id FROM sys_role WHERE role_code='expert'),'warnings:dispatch'),
((SELECT id FROM sys_role WHERE role_code='expert'),'plans:view'),
((SELECT id FROM sys_role WHERE role_code='expert'),'data:catalog:view'),
((SELECT id FROM sys_role WHERE role_code='expert'),'emergency:view'),
((SELECT id FROM sys_role WHERE role_code='expert'),'emergency:dispatch'),
((SELECT id FROM sys_role WHERE role_code='expert'),'rule:view'),
((SELECT id FROM sys_role WHERE role_code='expert'),'reports:view'),
((SELECT id FROM sys_role WHERE role_code='expert'),'analytics:view'),
((SELECT id FROM sys_role WHERE role_code='expert'),'route:view'),
((SELECT id FROM sys_role WHERE role_code='expert'),'messages:view'),
((SELECT id FROM sys_role WHERE role_code='expert'),'todo:view'),
((SELECT id FROM sys_role WHERE role_code='expert'),'settings:view'),
((SELECT id FROM sys_role WHERE role_code='expert'),'profile:view'),
((SELECT id FROM sys_role WHERE role_code='expert'),'profile:edit')
ON CONFLICT (role_id, perm_code) DO NOTHING;

-- AI工程师
INSERT INTO sys_role_permission (role_id, perm_code) VALUES
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'overview:view'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'analytics:view'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'ai:task:view'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'ai:task:create'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'ai:task:edit'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'ai:task:delete'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'ai:task:retry'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'ai:task:terminate'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'ai:result:view'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'ai:result:transfer'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'ai:insight:view'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'ai:model:view'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'ai:model:manage'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'ai:model:edit'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'rule:view'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'rule:create'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'rule:edit'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'rule:delete'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'datasource:view'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'datasource:create'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'datasource:edit'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'datasource:delete'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'datasource:config'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'sync:view'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'sync:manage'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'data:catalog:view'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'reports:view'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'monitor:view'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'messages:view'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'todo:view'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'profile:view'),
((SELECT id FROM sys_role WHERE role_code='ai_engineer'),'profile:edit')
ON CONFLICT (role_id, perm_code) DO NOTHING;

-- 现场运维
INSERT INTO sys_role_permission (role_id, perm_code) VALUES
((SELECT id FROM sys_role WHERE role_code='maintenance'),'overview:view'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'map:view'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'device_map:view'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'zone:view'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'weather:view'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'hazards:view'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'warnings:view'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'emergency:view'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'emergency:dispatch'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'resource:view'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'route:view'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'devices:view'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'devices:create'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'devices:edit'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'devices:delete'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'devices:export'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'devices:control'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'devices:ota'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'datasource:view'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'data:catalog:view'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'analytics:view'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'monitor:view'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'backup:view'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'messages:view'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'todo:view'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'settings:view'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'profile:view'),
((SELECT id FROM sys_role WHERE role_code='maintenance'),'profile:edit')
ON CONFLICT (role_id, perm_code) DO NOTHING;

-- =====================================================================
-- 5. 用户表 sys_user（7用户，替换旧3用户数据）
-- 密码：admin123 → BCrypt($2b$10$): $2b$10$sl2SHlS/I0NUwrSWgqXs8e5XRUpLxPi6eRVQakX/0AfeWycTAKU1S
-- =====================================================================
INSERT INTO sys_user (username, password_hash, real_name, phone, email, role_code, region_code, status) VALUES
('admin',        '$2b$10$sl2SHlS/I0NUwrSWgqXs8e5XRUpLxPi6eRVQakX/0AfeWycTAKU1S', '李晨曦',  '13800000001', 'admin@disasterguard.com',       'admin',               '500000', '启用'),
('sysadmin',     '$2b$10$sl2SHlS/I0NUwrSWgqXs8e5XRUpLxPi6eRVQakX/0AfeWycTAKU1S', '王建国',  '13800000002', 'sysadmin@disasterguard.com',     'system_admin',        '500000', '启用'),
('commander',    '$2b$10$sl2SHlS/I0NUwrSWgqXs8e5XRUpLxPi6eRVQakX/0AfeWycTAKU1S', '赵明远',  '13800000003', 'cmd@disasterguard.com',          'emergency_commander', '500000', '启用'),
('duty',         '$2b$10$sl2SHlS/I0NUwrSWgqXs8e5XRUpLxPi6eRVQakX/0AfeWycTAKU1S', '周小雨',  '13800000004', 'duty@disasterguard.com',         'attendant',           '500103', '启用'),
('expert',       '$2b$10$sl2SHlS/I0NUwrSWgqXs8e5XRUpLxPi6eRVQakX/0AfeWycTAKU1S', '张海燕',  '13800000005', 'expert@disasterguard.com',       'expert',              '500106', '启用'),
('aiengineer',   '$2b$10$sl2SHlS/I0NUwrSWgqXs8e5XRUpLxPi6eRVQakX/0AfeWycTAKU1S', '陈志远',  '13800000006', 'ai@disasterguard.com',           'ai_engineer',         '500109', '启用'),
('fieldops',     '$2b$10$sl2SHlS/I0NUwrSWgqXs8e5XRUpLxPi6eRVQakX/0AfeWycTAKU1S', '刘强',    '13800000007', 'ops@disasterguard.com',          'maintenance',         '500103', '启用')
ON CONFLICT (username) DO NOTHING;

-- =====================================================================
-- 6. 区域 region（重庆市，替换旧龙川县数据）
-- =====================================================================
INSERT INTO region (region_code, region_name, region_level, path, sort_order, center_lon, center_lat, geo_type) VALUES
('500000', '重庆市',          'city',     '500000',     1, 106.5500, 29.5600, 'MOUNTAIN'),
('500100', '主城都市区',      'district', '500000/500100', 2, 106.5500, 29.5600, 'HILL'),
('500103', '渝中区',          'district', '500000/500100', 3, 106.5600, 29.5600, 'PLAIN'),
('500104', '大渡口区',        'district', '500000/500100', 4, 106.4800, 29.4900, 'HILL'),
('500105', '江北区',          'district', '500000/500100', 5, 106.5700, 29.6100, 'PLAIN'),
('500106', '沙坪坝区',        'district', '500000/500100', 6, 106.4500, 29.5800, 'HILL'),
('500107', '九龙坡区',        'district', '500000/500100', 7, 106.5100, 29.5300, 'HILL'),
('500108', '南岸区',          'district', '500000/500100', 8, 106.5700, 29.5300, 'HILL'),
('500109', '北碚区',          'district', '500000/500109', 9, 106.5000, 29.8000, 'MOUNTAIN'),
('500110', '渝北区',          'district', '500000/500100',10, 106.6300, 29.7400, 'HILL'),
('500112', '巴南区',          'district', '500000/500100',11, 106.5200, 29.4000, 'HILL'),
-- 重点隐患监测乡镇
('500109101', '歇马镇',       'town',     '500000/500109/500109101', 21, 106.4300, 29.8300, 'MOUNTAIN'),
('500109102', '蔡家岗镇',     'town',     '500000/500109/500109102', 22, 106.4700, 29.7800, 'HILL'),
('500106401', '歌乐山镇',     'town',     '500000/500106/500106401', 23, 106.4300, 29.5700, 'MOUNTAIN')
ON CONFLICT (region_code) DO NOTHING;

-- =====================================================================
-- 7. AI模型 ai_model（7个模型，全部在线）
-- =====================================================================
INSERT INTO ai_model (model_code, model_name, version, model_type, status, metrics, config, description, deployed_at) VALUES
('xgboost_fusion', 'XGBoost融合模型',        'v1.2.1', 'xgboost',               'ONLINE',
 '{"accuracy":0.92,"f1":0.89,"precision":0.91,"recall":0.87,"auc":0.95}',
 '{"threshold":0.75,"window_hours":24,"features":["rainfall","slope","displacement"]}',
 '综合位移+雨量+地形特征，主流预警模型', NOW() - INTERVAL '30 days'),
('lstm_precip',    'LSTM时序降雨预测',        'v1.1.0', 'lstm',                    'ONLINE',
 '{"mae":0.12,"rmse":0.18,"r2":0.87}',
 '{"threshold":50,"window_hours":6,"forecast_horizon_hours":24}',
 '基于ERA5和本地雨量站融合的6小时预报', NOW() - INTERVAL '20 days'),
('yolo_landslide', 'YOLO视觉滑坡检测',        'v1.0.5', 'yolo',                    'ONLINE',
 '{"map":0.78,"recall":0.85,"precision":0.72,"f1":0.78}',
 '{"confidence_threshold":0.6,"iou_threshold":0.5,"classes":["landslide","erosion","scarp"]}',
 'Sentinel-2 影像地物变化检测', NOW() - INTERVAL '15 days'),
('comprehensive',  '综合研判融合模型',         'v1.0.0', 'comprehensive',          'ONLINE',
 '{"f1":0.91,"precision":0.93,"recall":0.89,"auc":0.97}',
 '{"weights":{"image":0.35,"precipitation":0.25,"geographic":0.20,"device":0.20}}',
 '多模态综合评分：卫星影像+气象+地形+设备综合', NOW() - INTERVAL '10 days'),
('device_health',  '设备健康异常检测',         'v1.0.2', 'device_health',          'ONLINE',
 '{"auc":0.94,"accuracy":0.91,"f1":0.88}',
 '{"threshold":0.5,"lookback_hours":168}',
 '基于设备上报指标的异常检测', NOW() - INTERVAL '45 days'),
('disaster_assess','灾损评估模型',            'v1.0.0', 'disaster_assessment',     'ONLINE',
 '{"accuracy":0.88,"f1":0.86}',
 '{"exposure_weights":{"building":1.0,"population":0.8,"road":0.5}}',
 '灾害发生后承灾体损失快速评估', NOW() - INTERVAL '60 days'),
('isolation_forest','设备漂移检测',           'v1.0.0', 'isolation_forest',        'ONLINE',
 '{"precision":0.87,"recall":0.83,"f1":0.85}',
 '{"contamination":0.05,"n_estimators":100}',
 '设备数据漂移与模型降级检测', NOW() - INTERVAL '90 days')
ON CONFLICT (model_code) DO NOTHING;

-- =====================================================================
-- 8. 数据源 data_source
-- =====================================================================
INSERT INTO data_source (ds_code, ds_name, ds_type, endpoint, auth_type, status, last_sync_at, sync_interval) VALUES
('ds_sentinel1',  'Sentinel-1 SAR影像',     'satellite', 'https://dataspace.copernicus.eu',      'OAuth2',  'CONNECTED', NOW() - INTERVAL '1 hour', 86400),
('ds_sentinel2', 'Sentinel-2多光谱影像',   'satellite', 'https://dataspace.copernicus.eu',      'OAuth2',  'CONNECTED', NOW() - INTERVAL '2 hour', 86400),
('ds_era5',       'ERA5气象再分析',          'weather',   'https://cds.climate.copernicus.eu',   'API_KEY', 'CONNECTED', NOW() - INTERVAL '30 minute', 3600),
('ds_gpm',        'GPM IMERG降雨',           'weather',   'https://gpm1.gesdisc.eosdis.nasa.gov', 'BASIC',   'CONNECTED', NOW() - INTERVAL '15 minute', 1800),
('ds_mqtt_local', '本地MQTT设备接入',         'mqtt',      'tcp://localhost:1883',                'TOKEN',   'CONNECTED', NOW(), 60),
('ds_srtm',       'SRTM DEM地形高程',         'terrain',   'https://earthengine.googleapis.com',   'API_KEY', 'CONNECTED', NOW() - INTERVAL '1 day', 259200)
ON CONFLICT (ds_code) DO NOTHING;

-- =====================================================================
-- 9. 数据同步任务 data_sync_task
-- =====================================================================
INSERT INTO data_sync_task (task_code, task_name, data_source_id, sync_type, cron_expr, status, last_run_at, last_status, next_run_at) VALUES
(1, 'Sentinel-2每日同步', 2, 'PERIODIC', '0 0 2 * * ?', 'RUNNING', NOW() - INTERVAL '1 day', 'SUCCESS', NOW() + INTERVAL '1 day'),
(2, 'ERA5逐小时降雨同步', 3, 'PERIODIC', '0 0 * * * ?', 'RUNNING', NOW() - INTERVAL '1 hour', 'SUCCESS', NOW() + INTERVAL '1 hour'),
(3, 'GPM卫星降水同步',   4, 'PERIODIC', '0 0 * * * ?', 'RUNNING', NOW() - INTERVAL '15 minute', 'SUCCESS', NOW() + INTERVAL '15 minute'),
(4, '设备MQTT实时入库',  5, 'REALTIME', NULL,            'RUNNING', NOW(), 'RUNNING', NULL)
ON CONFLICT (task_code) DO NOTHING;

-- =====================================================================
-- 10. 规则引擎 rule（替换旧3条为完整规则集）
-- =====================================================================
INSERT INTO rule (rule_code, name, disaster_type, level, priority, conditions, actions, dsl_text, enabled, created_by) VALUES
('R001', '滑坡黄色预警（雨量+坡度）',
 'landslide','yellow',90,
 '{"logic":"AND","conditions":[{"field":"rainfall_24h","operator":">","value":50},{"field":"slope","operator":">","value":30}]}',
 '{"type":"CREATE_EVENT","hazard_type":"landslide","level":"yellow","confidence":0.80}',
 'rainfall_24h > 50 AND slope > 30', TRUE, 'aiengineer'),

('R002', '滑坡橙色预警（雨量+位移）',
 'landslide','orange',80,
 '{"logic":"AND","conditions":[{"field":"rainfall_24h","operator":">","value":80},{"field":"displacement_rate","operator":">","value":5}]}',
 '{"type":"CREATE_EVENT","hazard_type":"landslide","level":"orange","confidence":0.88}',
 'rainfall_24h > 80 AND displacement_rate > 5', TRUE, 'aiengineer'),

('R003', '洪水红色预警（水位+雨量）',
 'flood','red',95,
 '{"logic":"AND","conditions":[{"field":"water_level","operator":">","value":"warning_level"},{"field":"rainfall_6h","operator":">","value":100}]}',
 '{"type":"CREATE_EVENT","hazard_type":"flood","level":"red","confidence":0.93}',
 'water_level > warning_level AND rainfall_6h > 100', TRUE, 'aiengineer'),

('R004', '设备离线告警',
 'device','blue',70,
 '{"logic":"AND","conditions":[{"field":"device_status","operator":"==","value":"OFFLINE"},{"field":"duration_minutes","operator":">","value":10}]}',
 '{"type":"CREATE_TODO","assign_role":"maintenance","priority":"HIGH"}',
 'device_status == OFFLINE AND duration_minutes > 10', TRUE, 'sysadmin'),

('R005', '地震触发巡检',
 'earthquake','yellow',85,
 '{"logic":"AND","conditions":[{"field":"magnitude","operator":">=","value":4.5},{"field":"distance_km","operator":"<","value":100}]}',
 '{"type":"TRIGGER_AI_TASK","task_name":"earthquake_inspection"}',
 'magnitude >= 4.5 AND distance_km < 100', TRUE, 'aiengineer'),

('R006', '崩塌黄色预警（裂缝扩展）',
 'collapse','yellow',75,
 '{"logic":"AND","conditions":[{"field":"crack_width_rate","operator":">","value":2.0},{"field":"rainfall_24h","operator":">","value":30}]}',
 '{"type":"CREATE_EVENT","hazard_type":"collapse","level":"yellow","confidence":0.78}',
 'crack_width_rate > 2.0 AND rainfall_24h > 30', TRUE, 'aiengineer')
ON CONFLICT (rule_code) DO NOTHING;

-- =====================================================================
-- 10b. 预警规则 warning_rule（warning_event.rule_code 的 FK 目标）
--   与 rule 表共用同一套规则编号；语义层面 warning_rule 是面向预警发布
--   的子集，rule 是规则引擎的完整定义。
-- =====================================================================
INSERT INTO warning_rule (rule_code, rule_name, metric_code, expression, warning_level, enabled, remark) VALUES
('R001', '滑坡黄色预警（雨量+坡度）', 'rainfall_24h_slope', 'rainfall_24h > 50 AND slope > 30',           '黄色预警', TRUE, 'AI 引擎规则 R001 的预警镜像'),
('R002', '滑坡橙色预警（雨量+位移）', 'rainfall_24h_disp',  'rainfall_24h > 80 AND displacement_rate > 5','橙色预警', TRUE, 'AI 引擎规则 R002 的预警镜像'),
('R003', '洪水红色预警（水位+雨量）', 'water_level_rain6h', 'water_level > warning_level AND rainfall_6h > 100', '红色预警', TRUE, 'AI 引擎规则 R003 的预警镜像'),
('R004', '设备离线告警',              'device_status',      'device_status == OFFLINE AND duration_minutes > 10', '蓝色预警', TRUE, '运维告警'),
('R005', '地震触发巡检',              'magnitude_distance', 'magnitude >= 4.5 AND distance_km < 100', '黄色预警', TRUE, '触发 AI 巡检任务'),
('R006', '崩塌黄色预警（裂缝扩展）', 'crack_rate_rain',    'crack_width_rate > 2.0 AND rainfall_24h > 30', '黄色预警', TRUE, 'AI 引擎规则 R006 的预警镜像')
ON CONFLICT (rule_code) DO NOTHING;

-- =====================================================================
-- 12. 灾害事件源 unified_event（v2.1.0 Task#1 P1：hazard_point 是该表视图源）
--   * 必须在 monitoring_device / ai_comprehensive_score / inspection_task /
--     warning_event / emergency_event / confirmation_task 等 hazard_code
--     外键 INSERT 之前执行，否则 hazard_point 视图还没有 HZ-xxx 数据，
--     子表 INSERT 会违反 fk_*_hazard 外键。
--   * v2.1.0 起 hazard_point 是 unified_event WHERE kind='HAZARD' 的视图，
--     下面直接 INSERT INTO unified_event 让 ON CONFLICT 与部分唯一索引
--     (uq_unified_hazard_code) 显式匹配，避免视图重写时 ON CONFLICT 推断失败。
--   * 旧脚本兼容：本 INSERT 通过 hazard_point 视图也能跑（自动可写视图），
--     但 seed 阶段建议直接走统一事件源表。
-- =====================================================================
INSERT INTO unified_event (
  kind, event_code, state, source,
  hazard_code, name, hazard_type, risk_level, region_code, owner, hazard_status,
  longitude, latitude, geom, threat_object, hazard_source, discovered_at, detection_confidence
) VALUES
('HAZARD','EVT-HZ-001','DETECTED','AI_AUTO','HZ-001','青龙坡滑坡隐患点','landslide','高风险','500109','北碚区自然资源局','位移+12mm',106.4500,29.5600,ST_SetSRID(ST_MakePoint(106.4500,29.5600),4326),'青龙坡居民区（30户）','AI_MONITOR',NOW() - INTERVAL '3 hour',0.86),
('HAZARD','EVT-HZ-002','DETECTED','AI_AUTO','HZ-002','歌乐山崩塌隐患','collapse','中风险','500106','沙坪坝区应急局','裂缝18mm',106.4300,29.5700,ST_SetSRID(ST_MakePoint(106.4300,29.5700),4326),'歌乐山小学（500师生）','AI_MONITOR',NOW() - INTERVAL '5 hour',0.78),
('HAZARD','EVT-HZ-003','DETECTED','AI_AUTO','HZ-003','嘉陵江洪涝风险','flood','高风险','500103','渝中区水务局','警戒水位',106.5780,29.5600,ST_SetSRID(ST_MakePoint(106.4500,29.5600),4326),'千厮门历史街区','DEVICE',NOW() - INTERVAL '6 hour',0.93),
('HAZARD','EVT-HZ-004','DETECTED','AI_AUTO','HZ-004','歇马泥石流沟','debris_flow','中风险','500109101','北碚区应急局','沟道淤积',106.4350,29.8350,ST_SetSRID(ST_MakePoint(106.4350,29.8350),4326),'歇马镇主街道','MANUAL',NOW() - INTERVAL '2 day',0.71),
('HAZARD','EVT-HZ-005','DETECTED','AI_AUTO','HZ-005','石门沟地裂缝','ground_fissure','低风险','500109102','蔡家岗镇政府','缝宽2.1mm',106.4700,29.7800,ST_SetSRID(ST_MakePoint(106.4700,29.7800),4326),'农田与村道','DEVICE',NOW() - INTERVAL '7 day',0.65)
ON CONFLICT (hazard_key) DO NOTHING;

-- =====================================================================
-- 11. 监测设备 monitoring_device（替换旧4台，新增演示设备）
-- =====================================================================
INSERT INTO monitoring_device (device_code, device_name, device_type, hazard_code, region_code, online_status, battery_level, signal_level, last_report_at, install_location, longitude, latitude, geom) VALUES
-- 渝中区
('DV-001','嘉陵江水位计-01','water_level',  NULL,'500103','在线',100,'强',NOW() - INTERVAL '1 minute','嘉陵江千厮门', 106.5780,29.5600, ST_SetSRID(ST_MakePoint(106.5780,29.5600),4326)),
-- 北碚区
('DV-002','青龙坡GNSS-01',   'gnss',         'HZ-001','500109','在线', 92,'强',NOW() - INTERVAL '2 minute','青龙坡滑坡隐患点',106.4500,29.5600, ST_SetSRID(ST_MakePoint(106.4500,29.5600),4326)),
('DV-003','歇马雨量站-01',   'rain_gauge',   NULL,'500109101','在线', 88,'中',NOW() - INTERVAL '3 minute','歇马镇政府', 106.4300,29.8300, ST_SetSRID(ST_MakePoint(106.4300,29.8300),4326)),
-- 沙坪坝区
('DV-004','歌乐山裂缝计-01', 'crack_meter',   'HZ-002','500106','在线', 76,'中',NOW() - INTERVAL '1 minute','歌乐山崩塌点',106.4300,29.5700, ST_SetSRID(ST_MakePoint(106.4300,29.5700),4326)),
('DV-005','歌乐山倾角计-01', 'inclinometer',  'HZ-002','500106','在线', 80,'强',NOW() - INTERVAL '2 minute','歌乐山崩塌点',106.4310,29.5710, ST_SetSRID(ST_MakePoint(106.4310,29.5710),4326)),
-- 离线设备
('DV-006','嘉陵江摄像头-01', 'camera',        NULL,'500103','离线', 45,'无',NOW() - INTERVAL '2 hour', '千厮门大桥', 106.5790,29.5610, ST_SetSRID(ST_MakePoint(106.5790,29.5610),4326)),
('DV-007','北碚气象站-01',   'weather_station',NULL,'500109','在线',100,'强',NOW() - INTERVAL '5 minute', '北碚区政府', 106.5000,29.8000, ST_SetSRID(ST_MakePoint(106.5000,29.8000),4326)),
-- 土壤含水率
('DV-008','歇马土壤站-01',   'soil_moisture', NULL,'500109101','在线', 82,'中',NOW() - INTERVAL '1 minute','歇马镇农业基地',106.4320,29.8320, ST_SetSRID(ST_MakePoint(106.4320,29.8320),4326))
ON CONFLICT (device_code) DO NOTHING;

-- =====================================================================
-- 12. 灾害点 hazard_point（v2.1.0 Task#1 P1：写入 unified_event 视图源）
--   * 本段已上移到 11 之前执行（统一事件源必须先于所有 hazard_code 外键 INSERT）。
--   * v2.1.0 起 hazard_point 是 unified_event WHERE kind='HAZARD' 的视图。
-- =====================================================================

-- =====================================================================
-- 13. AI 综合评分 ai_comprehensive_score（每个灾害点一条最新）
-- =====================================================================
INSERT INTO ai_comprehensive_score (hazard_code, risk_level, risk_score, source, disaster_type, confidence, dimensions_json, reasoning_json, suggested_actions_json, analyzed_at) VALUES
('HZ-001','orange',78.50,'AI_AUTO','landslide',0.86,
 '{"image":0.82,"precipitation":0.76,"geographic":0.90,"device":0.85}',
 '{"primary_factor":"连续降雨导致坡体饱和","secondary_factor":"位移速率超过阈值","contributing":"土壤含水率持续上升"}',
 '{"action":"启动橙色预警","next_step":"专家二次确认","monitoring":"加密GNSS监测频率"}', NOW() - INTERVAL '2 hour'),
('HZ-002','yellow',62.30,'AI_AUTO','collapse',0.78,
 '{"image":0.75,"precipitation":0.68,"geographic":0.80,"device":0.72}',
 '{"primary_factor":"裂缝计示数持续增大","secondary_factor":"近期无强降雨但裂缝扩展趋势明显"}',
 '{"action":"加强巡查","next_step":"安排专家现场踏勘","monitoring":"增加裂缝计读数频率"}', NOW() - INTERVAL '4 hour'),
('HZ-003','red',   93.20,'AI_AUTO','flood',   0.93,
 '{"image":0.88,"precipitation":0.95,"geographic":0.91,"device":0.94}',
 '{"primary_factor":"6小时降雨量超100mm","secondary_factor":"嘉陵江水位逼近警戒线","contributing":"上游来水量持续增大"}',
 '{"action":"立即启动红色预警","next_step":"组织沿江群众撤离","monitoring":"每30分钟上报水位"}', NOW() - INTERVAL '5 hour'),
('HZ-004','orange',71.10,'AI_AUTO','debris_flow',0.71,
 '{"image":0.65,"precipitation":0.80,"geographic":0.78,"device":0.60}',
 '{"primary_factor":"沟道上游汇水面积大","secondary_factor":"历史泥石流记录","contributing":"物源区松散堆积物丰富"}',
 '{"action":"发布橙色预警","next_step":"禁止沟道通行","monitoring":"雨量站实时监控"}', NOW() - INTERVAL '1 day'),
('HZ-005','blue',  45.00,'AI_AUTO','ground_fissure',0.65,
 '{"image":0.55,"precipitation":0.60,"geographic":0.70,"device":0.65}',
 '{"primary_factor":"地裂缝与地质构造线吻合","secondary_factor":"年际变化率低"}',
 '{"action":"纳入年度监测计划","next_step":"年度复查","monitoring":"季度复测"}', NOW() - INTERVAL '6 day')
ON CONFLICT (hazard_code) DO NOTHING;

-- =====================================================================
-- 14. 巡检任务 inspection_task（替换旧3任务）
-- =====================================================================
INSERT INTO inspection_task (task_code, name, task_type, executor, hazard_code, region_code, status, progress, start_time, due_time, created_by) VALUES
('TK-001','雨后重点灾害点加密巡检',    '雨后复查',  'duty',   'HZ-001','500109',  '执行中',50, NOW(), NOW() + INTERVAL '4 hour', 'duty'),
('TK-002','歌乐山崩塌隐患专项排查',      '专项排查',  'expert', 'HZ-002','500106',  '待处理', 0, NOW(), NOW() + INTERVAL '8 hour','duty'),
('TK-003','嘉陵江堤防设施检查',         '常规巡查',  'fieldops','HZ-003','500103', '执行中',80, NOW(), NOW() + INTERVAL '2 hour', 'commander'),
('TK-004','歇马泥石流沟年度排查',       '专项排查',  'duty',   'HZ-004','500109101','待处理', 0, NOW(), NOW() + INTERVAL '3 day','expert'),
('TK-005','石门沟地裂缝季度复查',        '定期复查',  'fieldops','HZ-005','500109102','已完成',100, NOW()-INTERVAL '2 day', NOW()-INTERVAL '1 day','aiengineer')
ON CONFLICT (task_code) DO NOTHING;

-- =====================================================================
-- 15. 预警事件 warning_event（替换旧3条）
-- =====================================================================
INSERT INTO warning_event (warning_code, rule_code, hazard_code, title, warning_level, trigger_value, status, triggered_at, rule_snapshot) VALUES
('WG-001','R003','HZ-003','嘉陵江洪水红色预警','红色预警','water_level=28.5m, rainfall_6h=108mm','已发布',NOW() - INTERVAL '5 hour','{"metric":"water_level","threshold":"warning_level"}'),
('WG-002','R002','HZ-001','青龙坡滑坡橙色预警','橙色预警','rainfall_24h=82mm, displacement_rate=6.2mm','待发布',NOW() - INTERVAL '2 hour','{"metric":"rainfall_24h","threshold":80}'),
('WG-003','R006','HZ-002','歌乐山崩塌黄色预警','黄色预警','crack_width_rate=2.3mm/day','处理中',NOW() - INTERVAL '4 hour','{"metric":"crack_width_rate","threshold":2.0}')
ON CONFLICT (warning_code) DO NOTHING;

-- =====================================================================
-- 16. 应急事件 emergency_event（替换旧1条）
-- =====================================================================
INSERT INTO emergency_event (event_code, warning_code, hazard_code, title, status, commander, step_name, result_summary, started_at) VALUES
('EM-001','WG-001','HZ-003','嘉陵江洪水应急响应','进行中','commander','启动预案','已启动Ⅰ级响应，沿江群众开始撤离',NOW() - INTERVAL '4 hour')
ON CONFLICT (event_code) DO NOTHING;

-- =====================================================================
-- 17. 会签/确认任务 confirmation_task
-- =====================================================================
INSERT INTO confirmation_task (task_code, hazard_code, source, risk_level, confidence, status, priority, claimed_by, claimed_at, first_confirmed_by, first_confirmed_at, created_at) VALUES
('CF-001','HZ-001','AI_RESULT','orange',0.86,'PENDING_SECOND','HIGH','duty',NOW() - INTERVAL '1 hour','duty',NOW() - INTERVAL '30 minute', NOW() - INTERVAL '2 hour'),
('CF-002','HZ-002','AI_RESULT','yellow',0.78,'PENDING_CLAIM','MEDIUM',NULL,NULL,NULL,NULL, NOW() - INTERVAL '4 hour'),
('CF-003','HZ-003','DEVICE','red',0.93,'CONFIRMED','HIGH','duty',NOW() - INTERVAL '3 hour','expert',NOW() - INTERVAL '2 hour', NOW() - INTERVAL '5 hour')
ON CONFLICT (task_code) DO NOTHING;

-- =====================================================================
-- 18. 应急资源 resource
-- =====================================================================
INSERT INTO resource (res_code, res_name, res_type, region_code, longitude, latitude, geom, status, capacity, contact_person, contact_phone) VALUES
('RS-001','重庆市应急救援一队','rescue_team','500103',106.5500,29.5500,ST_SetSRID(ST_MakePoint(106.5500,29.5500),4326),'AVAILABLE','50人/冲锋舟5艘','张队长','13900001001'),
('RS-002','北碚区综合救援队','rescue_team','500109',106.5000,29.8000,ST_SetSRID(ST_MakePoint(106.5000,29.8000),4326),'AVAILABLE','30人/救援车3辆','李队长','13900001002'),
('RS-003','渝中区应急避难场所A','shelter','500103',106.5600,29.5550,ST_SetSRID(ST_MakePoint(106.5600,29.5550),4326),'OPEN','可容纳2000人','刘主任','13900001003'),
('RS-004','北碚区物资储备库','supplies','500109',106.4900,29.7900,ST_SetSRID(ST_MakePoint(106.4900,29.7900),4326),'AVAILABLE','编织袋5万只/砂石500方','王管理员','13900001004'),
('RS-005','重庆市应急指挥车','vehicle','500103',106.5400,29.5600,ST_SetSRID(ST_MakePoint(106.5400,29.5600),4326),'AVAILABLE','通信/指挥/照明','陈驾驶员','13900001005'),
('RS-006','北碚消防救援站','rescue_team','500109',106.4800,29.8100,ST_SetSRID(ST_MakePoint(106.4800,29.8100),4326),'DEPLOYED','40人/消防车6辆','周站长','13900001006')
ON CONFLICT (res_code) DO NOTHING;

-- =====================================================================
-- 19. 应急预案 plan
-- =====================================================================
INSERT INTO plan (plan_code, plan_name, disaster_type, level, content, owner_dept, contact_person, contact_phone, status) VALUES
('PL-001','重庆市洪水应急预案',       'flood',     'red',   '启动Ⅰ级响应，组织沿江撤离，关闭地下通道，开启应急广播。','重庆市应急管理局','赵局长','13900000011','ACTIVE'),
('PL-002','北碚区滑坡防治应急预案',   'landslide', 'orange','划定危险区，转移受威胁群众，加密监测频率，通知下游。','北碚区自然资源局','钱主任','13900000012','ACTIVE'),
('PL-003','重庆市崩塌地质灾害预案',   'collapse',  'yellow','设置警示标识，封闭危险路段，派人值守，适时组织撤离。','重庆市规划和自然资源局','孙高工','13900000013','ACTIVE'),
('PL-004','重庆市泥石流防治预案',     'debris_flow','orange','禁止沟道通行，清理淤积物，监测雨量，发布预警。','北碚区应急管理局','李队长','13900000014','ACTIVE')
ON CONFLICT (plan_code) DO NOTHING;

-- =====================================================================
-- 20. 通知模板 notice_template
-- =====================================================================
INSERT INTO notice_template (template_code, template_name, disaster_type, level, channel, title_template, content_template, enabled) VALUES
('NT-001','洪水红色短信','flood','red','sms',
 '【地脉智巡 红色预警】{{region}}发生{{disaster}}，{{content}}，请{{action}}。',
 '水位持续上涨，已超警戒线{{value}}米。未来{{hours}}小时仍有强降雨。请沿江居民立即撤离至高处。',TRUE),
('NT-002','滑坡橙色短信','landslide','orange','sms',
 '【地脉智巡 橙色预警】{{region}}发生{{disaster}}，{{content}}，请{{action}}。',
 '{{area}}区域位移速率持续增大，存在滑坡风险。请周边居民注意避让，服从现场指挥。',TRUE),
('NT-003','预警APP推送','*','*','app',
 '【{{level}}预警】{{title}}',
 '{{region}}于{{time}}发布{{level}}预警：{{content}}。详情请查看地脉智巡平台。',TRUE),
('NT-004','专家会签邮件','*','*','email',
 '【地脉智巡 待会签】{{title}}',
 '您好，{{region}}发生{{disaster}}事件，请登录地脉智巡系统进行会签确认。事件详情：{{content}}',TRUE),
('NT-005','LED大屏展示','*','*','led',
 '{{level}}：{{title}}',
 '{{region}}提醒：{{content}}。请广大市民注意安全，配合应急指挥。',TRUE)
ON CONFLICT (template_code) DO NOTHING;

-- =====================================================================
-- 21. 审批记录 approval
-- =====================================================================
INSERT INTO approval (approval_code, biz_type, biz_id, title, applicant_id, approver_id, status, opinion, created_at) VALUES
('AP-001','WARNING_PUBLISH','WG-002','青龙坡滑坡橙色预警发布申请',
 (SELECT id FROM sys_user WHERE username='duty'),
 (SELECT id FROM sys_user WHERE username='expert'),
 'PENDING',NULL, NOW() - INTERVAL '30 minute'),
('AP-002','WARNING_PUBLISH','WG-001','嘉陵江洪水红色预警发布申请',
 (SELECT id FROM sys_user WHERE username='commander'),
 (SELECT id FROM sys_user WHERE username='admin'),
 'APPROVED','同意发布，已启动Ⅰ级响应。', NOW() - INTERVAL '5 hour')
ON CONFLICT (approval_code) DO NOTHING;

-- =====================================================================
-- 22. 用户消息 user_message
-- =====================================================================
INSERT INTO user_message (user_id, msg_type, title, content, biz_type, biz_id, priority, read_status, created_at) VALUES
((SELECT id FROM sys_user WHERE username='duty'),   'CONFIRM',  '待人工确认','青龙坡滑坡隐患点待您转人工确认（置信度86%）','hazard','HZ-001','HIGH',FALSE, NOW() - INTERVAL '2 hour'),
((SELECT id FROM sys_user WHERE username='expert'), 'CONFIRM',  '待二次确认','青龙坡滑坡待您二次确认（橙色预警）','hazard','HZ-001','HIGH',FALSE, NOW() - INTERVAL '30 minute'),
((SELECT id FROM sys_user WHERE username='duty'),   'WARNING',  '预警待发布','青龙坡滑坡橙色预警待您提交发布申请','warning','WG-002','HIGH',FALSE, NOW() - INTERVAL '20 minute'),
((SELECT id FROM sys_user WHERE username='commander'),'WARNING','应急进行中','嘉陵江洪水红色预警已发布，请继续关注','warning','WG-001','HIGH',TRUE, NOW() - INTERVAL '4 hour'),
((SELECT id FROM sys_user WHERE username='aiengineer'),'SYSTEM','模型漂移告警','XGBoost融合模型检测到近期降雨特征漂移，建议重新训练','system','model:xgboost_fusion','MEDIUM',FALSE, NOW() - INTERVAL '1 hour')
ON CONFLICT DO NOTHING;

-- =====================================================================
-- 23. 用户待办 user_todo
-- =====================================================================
INSERT INTO user_todo (user_id, todo_type, biz_id, title, description, priority, deadline, status, created_at) VALUES
((SELECT id FROM sys_user WHERE username='expert'),  'CONFIRMATION','HZ-001','青龙坡滑坡二次确认','橙色预警需专家二次确认并修改风险等级','HIGH',NOW() + INTERVAL '2 hour','PENDING', NOW() - INTERVAL '30 minute'),
((SELECT id FROM sys_user WHERE username='duty'),   'WARNING_DRAFT','WG-002','提交预警发布申请','青龙坡滑坡橙色预警需提交给应急指挥审批','HIGH',NOW() + INTERVAL '1 hour','PENDING', NOW() - INTERVAL '20 minute'),
((SELECT id FROM sys_user WHERE username='fieldops'),'DEVICE_UPDATE','DV-006','嘉陵江摄像头离线排查','摄像头DV-006已离线2小时，需现场排查','MEDIUM',NOW() + INTERVAL '4 hour','PENDING', NOW() - INTERVAL '2 hour'),
((SELECT id FROM sys_user WHERE username='aiengineer'),'MODEL_TRAIN','xgboost_fusion','XGBoost融合模型重训','检测到数据漂移，需使用近30天数据重新训练','MEDIUM',NOW() + INTERVAL '3 day','PENDING', NOW() - INTERVAL '1 hour')
ON CONFLICT DO NOTHING;

-- =====================================================================
-- 24. 设备轨迹样本 device_trajectory（演示轨迹回放）
-- =====================================================================
INSERT INTO device_trajectory (device_code, longitude, latitude, geom, altitude_m, speed_kmh, heading_deg, battery_level, source, recorded_at) VALUES
('DV-002',106.4500,29.5600,ST_SetSRID(ST_MakePoint(106.4500,29.5600),4326),285.3,0.0,NULL,92,'MQTT',NOW() - INTERVAL '10 minute'),
('DV-002',106.4502,29.5602,ST_SetSRID(ST_MakePoint(106.4502,29.5602),4326),285.1,2.1,45.0,92,'MQTT',NOW() - INTERVAL '9 minute'),
('DV-002',106.4504,29.5603,ST_SetSRID(ST_MakePoint(106.4504,29.5603),4326),285.0,1.8,50.0,92,'MQTT',NOW() - INTERVAL '8 minute'),
('DV-004',106.4300,29.5700,ST_SetSRID(ST_MakePoint(106.4300,29.5700),4326),310.5,0.0,NULL,76,'MQTT',NOW() - INTERVAL '5 minute'),
('DV-004',106.4301,29.5701,ST_SetSRID(ST_MakePoint(106.4301,29.5701),4326),310.4,1.5,120.0,76,'MQTT',NOW() - INTERVAL '4 minute')
ON CONFLICT DO NOTHING;

-- =====================================================================
-- 25. 设备指标样本 device_metric（演示实时数据）
-- =====================================================================
INSERT INTO device_metric (device_code, hazard_code, metric_code, value, recorded_at) VALUES
('DV-002','HZ-001','displacement_rate', 6.2, NOW() - INTERVAL '2 minute'),
('DV-002','HZ-001','displacement_total', 156.8, NOW() - INTERVAL '2 minute'),
('DV-003',NULL,    'rainfall_1h',       12.5, NOW() - INTERVAL '3 minute'),
('DV-003',NULL,    'rainfall_24h',      82.0, NOW() - INTERVAL '3 minute'),
('DV-004','HZ-002','crack_width',       18.3, NOW() - INTERVAL '1 minute'),
('DV-004','HZ-002','crack_width_rate',   2.3, NOW() - INTERVAL '1 minute'),
('DV-005','HZ-002','tilt_angle',         3.8, NOW() - INTERVAL '2 minute'),
('DV-001',NULL,    'water_level',       28.5, NOW() - INTERVAL '1 minute'),
('DV-001',NULL,    'flow_rate',         4500.0,NOW() - INTERVAL '1 minute'),
('DV-007',NULL,    'temperature',       26.5, NOW() - INTERVAL '5 minute'),
('DV-007',NULL,    'humidity',          78.0, NOW() - INTERVAL '5 minute'),
('DV-007',NULL,    'wind_speed',        3.2,  NOW() - INTERVAL '5 minute'),
('DV-008',NULL,    'soil_moisture',    42.5, NOW() - INTERVAL '1 minute')
ON CONFLICT DO NOTHING;

-- =====================================================================
-- 26. 风险评分日志 risk_score_log（替换旧3条）
-- =====================================================================
INSERT INTO risk_score_log (hazard_code, score, risk_level, source, factors) VALUES
('HZ-001',78.50,'高风险','AI_AUTO','{"displacement_rate":6.2,"rainfall_24h":82,"soil_moisture":42}'::jsonb),
('HZ-002',62.30,'中风险','AI_AUTO','{"crack_width":18.3,"crack_width_rate":2.3,"rainfall_24h":35}'::jsonb),
('HZ-003',93.20,'高风险','DEVICE', '{"water_level":28.5,"rainfall_6h":108,"flow_rate":4500}'::jsonb),
('HZ-004',71.10,'中风险','AI_AUTO','{"rainfall_24h":95,"drainage_area":2.5,"debris_potential":"high"}'::jsonb),
('HZ-005',45.00,'低风险','DEVICE', '{"fissure_width":2.1,"historical_change_rate":0.02}'::jsonb)
ON CONFLICT DO NOTHING;

-- =====================================================================
-- 27. 审计日志 operation_log（演示登录记录）
-- =====================================================================
INSERT INTO operation_log (operator, module_name, action_name, request_method, request_path, result_status, ip_address, created_at) VALUES
('admin',      '系统',        '用户登录',        'POST','/api/auth/login',         '成功','127.0.0.1',NOW() - INTERVAL '1 day'),
('sysadmin',   '系统',        '用户登录',        'POST','/api/auth/login',         '成功','127.0.0.1',NOW() - INTERVAL '1 day'),
('commander',  '系统',        '用户登录',        'POST','/api/auth/login',         '成功','127.0.0.1',NOW() - INTERVAL '1 day'),
('duty',       '系统',        '用户登录',        'POST','/api/auth/login',         '成功','127.0.0.1',NOW() - INTERVAL '20 minute'),
('expert',     '系统',        '用户登录',        'POST','/api/auth/login',         '成功','127.0.0.1',NOW() - INTERVAL '30 minute'),
('aiengineer', '系统',        '用户登录',        'POST','/api/auth/login',         '成功','127.0.0.1',NOW() - INTERVAL '1 hour'),
('fieldops',   '系统',        '用户登录',        'POST','/api/auth/login',         '成功','127.0.0.1',NOW() - INTERVAL '2 hour'),
('duty',       '灾害',        '认领确认任务',    'POST','/api/confirmation/claim', '成功','127.0.0.1',NOW() - INTERVAL '1 hour'),
('expert',     '灾害',        '二次确认',        'POST','/api/confirmation/confirm','成功','127.0.0.1',NOW() - INTERVAL '30 minute'),
('commander',  '预警',        '发布预警',        'POST','/api/warnings/publish',   '成功','127.0.0.1',NOW() - INTERVAL '5 hour'),
('aiengineer', 'AI',          '触发AI任务',      'POST','/api/ai/tasks/trigger',  '成功','127.0.0.1',NOW() - INTERVAL '3 hour')
ON CONFLICT DO NOTHING;

-- =====================================================================
-- 28. 文件附件 file_attachment（演示已有）
-- =====================================================================
INSERT INTO file_attachment (business_type, business_code, file_name, file_size, file_type, storage_path, file_url, uploader)
SELECT * FROM (VALUES
  ('"hazard"'::jsonb,'HZ-001','青龙坡现场照片.jpg',    1240000,'image/jpeg',
   'hazard/HZ-001/现场照片.jpg','/disasterguard/hazard/HZ-001/现场照片.jpg','duty'),
  ('"hazard"'::jsonb,'HZ-002','歌乐山裂缝照片.jpg',    980000, 'image/jpeg',
   'hazard/HZ-002/裂缝照片.jpg','/disasterguard/hazard/HZ-002/裂缝照片.jpg','expert'),
  ('"task"'::jsonb,  'TK-001','雨后巡检轨迹.kml',     86000, 'application/vnd.google-earth.kml+xml',
   'task/TK-001/巡检轨迹.kml','/disasterguard/task/TK-001/巡检轨迹.kml','duty'),
  ('"warning"'::jsonb,'WG-001','嘉陵江水位监测.jpg',   1450000,'image/jpeg',
   'warning/WG-001/水位监测.jpg','/disasterguard/warning/WG-001/水位监测.jpg','commander'),
  ('"hazard"'::jsonb,'HZ-003','堤防检查报告.pdf',     2340000,'application/pdf',
   'hazard/HZ-003/检查报告.pdf','/disasterguard/hazard/HZ-003/检查报告.pdf','fieldops')
) AS v(business_type, business_code, file_name, file_size, file_type, storage_path, file_url, uploader)
WHERE NOT EXISTS (SELECT 1 FROM file_attachment LIMIT 1);

-- =====================================================================
-- 29. 用户扩展 profile / preference
-- =====================================================================
INSERT INTO user_profile (username, display_name, phone, email, department)
SELECT * FROM (VALUES
  ('admin',       '李晨曦（管理员）','13800000001','admin@disasterguard.com',      '系统管理部'),
  ('sysadmin',    '王建国',         '13800000002','sysadmin@disasterguard.com',   '信息技术部'),
  ('commander',   '赵明远',         '13800000003','cmd@disasterguard.com',         '应急指挥中心'),
  ('duty',        '周小雨',         '13800000004','duty@disasterguard.com',        '值班室'),
  ('expert',      '张海燕',         '13800000005','expert@disasterguard.com',      '地质专家库'),
  ('aiengineer',  '陈志远',         '13800000006','ai@disasterguard.com',          'AI研发部'),
  ('fieldops',    '刘强',           '13800000007','ops@disasterguard.com',         '设备运维部')
) AS v(username, display_name, phone, email, department)
WHERE NOT EXISTS (SELECT 1 FROM user_profile LIMIT 1);

INSERT INTO user_preference (user_id, channels, levels, theme, compact_mode, quiet_hours_enabled)
SELECT u.id, '["sms","app"]', '["red","orange","yellow"]', 'dark', FALSE, TRUE
FROM sys_user u WHERE u.role_code = 'attendant'
  AND NOT EXISTS (SELECT 1 FROM user_preference WHERE user_id = u.id)
LIMIT 1;
