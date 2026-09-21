# GL-Sandbox · 混合沙盘 C方案（硬件在环演示台）

混合方案核心理念：**物理沙盘作为"真实感知层"，数字平台作为"决策与指挥层"，两者通过 MQTT/WebSocket 实时双向联动。**

沙盘尺寸 **1.5m x 1.0m x 0.15m**，分三个功能区：
- **A区 山区/滑坡区** (0.6m x 1.0m) — 高地，坡度25-35，可动滑坡体
- **B区 河谷/洪水区** (0.5m x 1.0m) — 低洼，亚克力水槽，水位可控
- **C区 城镇/避难区** (0.4m x 1.0m) — 平地，仿真建筑群，LED预警屏

完整方案见 [`docs/HARDWARE_SANDBOX.md`](../docs/HARDWARE_SANDBOX.md)。

---

## 30 分钟快速上手（不需要任何硬件）

```bash
cd hardware-sandbox

# 0) 生成混合沙盘地形模型（C方案三区一体化）
python tools/build_mixed_sandbox.py --out examples/mixed_sandbox
#   -> 打开 examples/mixed_sandbox/preview.html 检查地形
#   -> model.ifc 可直接用 Revit 打开

# ① 生成 DEM 驱动泡沫切割图（备选：真实地形数据）
python tools/dem_to_foam.py --synthetic --out ./out
#   -> 打开 out/preview.html 检查分层是否合理

# ①b 可选：全要素 Revit 模型（地形 + 水体 + 道路 + 建筑）
python tools/build_revit_model.py --fetch --out ../examples/revit_model
python tools/verify_ifc.py ../examples/revit_model/model.ifc    # 必须 PASS

# ①c 可选：沙盘零件加工图（建筑/道路/水体/总装定位）
python tools/osm_to_foam_parts.py --out ../examples/foam_parts

# ② 静态自检：哨兵指标 / 字段完整性 / 冲突 / 上报节奏
python tools/selftest.py

# ③ 看一眼网关会发出什么（不连 MQTT、不需要硬件）
python gateway/sandbox_gateway.py --mode dryrun --print-only --once

# ④ 跑剧本（S1 暴雨型滑坡）
python gateway/sandbox_gateway.py --mode scenario --scenario s1 --print-only --steps 5
```

依赖：`numpy`（必需）、`pyyaml`（必需）、`matplotlib`（可选）、
`rasterio`（读 GeoTIFF）、`pillow`（在线瓦片）、`paho-mqtt`+`pyserial`（真机运行）。

---

## 真机运行顺序

```bash
# 1) 启动基础设施（EMQX / PostGIS）
cd .. && docker compose up -d emqx postgres

# 2) 注册隐患点与设备（幂等，可重复执行）
python tools/register_devices.py

# 3) 校验注册结果（确认每台设备都有 hazard_code）
python tools/register_devices.py --check

# 4) 烧录固件（先开 SIMULATE_SENSORS=1 跑通链路，再接真传感器）
#    firmware/esp32_node/esp32_node.ino 中修改 NODE_ROLE = 1 / 2 / 3
#    Node-A: A区传感器（裂缝/位移/倾角/土壤/雨量/振动）
#    Node-B: B区传感器（水位/流速）+ 执行器（水泵）
#    Node-C: C区执行器（LED屏/声光报警器）

# 5) 启动网关
python gateway/sandbox_gateway.py --mode serial      # 读真实串口
python gateway/sandbox_gateway.py --mode scenario --scenario s1

# 6) 每次重新演示前，归档上一轮预警（否则第二次不触发）
python tools/register_devices.py --reset-warnings
```

---

## 三个最容易翻车的点

1. **哨兵指标**：规则引擎有门控 `if (!metrics.containsKey(rule.metricCode)) continue;`
   而 `warning_rule.metric_code` 是 `rainfall_24h_disp` 这类组合键。
   只发 `rainfall_24h` + `displacement_rate`，**R002 永远不会触发**。网关已自动注入，别删。

2. **二次演示哑火**：同一 `(ruleCode, hazardCode)` 存在未关闭预警时不再创建新的。
   演示前必须 `register_devices.py --reset-warnings`。

3. **泡沫别用 502/万能胶/硝基漆**：溶剂会直接溶解泡沫，地形报废。
   只用 PVA 白乳胶、丙烯颜料、水性涂料。**另外禁止激光切割泡沫**（有毒烟气）。

---

## 目录

```
hardware-sandbox/
├── tools/
│   ├── build_mixed_sandbox.py 混合沙盘 C方案 地形建模（IFC/OBJ/SVG/CSV/HTML）
│   ├── dem_to_foam.py         真实 DEM -> 泡沫分层切割图（SVG/CSV/HTML/STL）
│   ├── build_revit_model.py   真实 DEM + OSM -> 全要素 Revit 模型（IFC/OBJ/CSV）
│   ├── osm_to_foam_parts.py   OSM 地物 -> 泡沫零件加工图（建筑/道路/水体/总装）
│   ├── verify_ifc.py          IFC 交付前自检（索引越界/退化面/包围盒）
│   ├── register_devices.py    隐患点 + 设备注册 / 预警清理 / 自查
│   ├── register_devices.sql   同上纯 SQL 版
│   └── selftest.py            端到端自检
├── examples/
│   ├── mixed_sandbox/         ★ C方案混合沙盘模型（1.5 x 1.0 m 三区）
│   ├── revit_model/           真实地形版 Revit 模型（1:1）
│   ├── revit_sandbox/         沙盘实体版 Revit 模型（1.2 x 0.8 m）
│   └── foam_parts/            沙盘零件加工图 + 零件清单
├── gateway/
│   ├── config.yaml            比尺 / 标定 / 设备映射 / 坐标换算 / 三区布局
│   ├── sandbox_gateway.py     采集 -> 标定 -> 哨兵注入 -> MQTT
│   └── scenario_player.py     剧本编排（S1~S4）
└── firmware/
    └── esp32_node/esp32_node.ino   采集固件（Node-A/B/C）
```
