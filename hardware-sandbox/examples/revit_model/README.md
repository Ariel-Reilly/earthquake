# 渝中半岛全要素 Revit 模型（真实地形版）

地形、水体、道路、城市建筑均来自真实数据；乡村民居为程序化补充（已标注）。

## 1. 模型构成

| 分类 | ObjectType | 数量 | 数据来源 | 真实性 |
|------|-----------|------|---------|--------|
| 山体 | `TERRAIN` | 1（9604 点 / 19596 面） | Copernicus/SRTM 地形瓦片 zoom15（约 4.2 m/px） | 真实 |
| 水体 | `WATER` | 1（1753 点 / 2944 面） | DEM 河谷 ∩ 水位 168 m + OSM 面状水体 | 真实地形 |
| 山路/梯道 | `ROAD-MTN` | 198 | OSM `highway=footway/path/track/steps` | 真实 |
| 城市道路 | `ROAD-URBAN` | 337 | OSM `highway=primary…service` | 真实 |
| 城市建筑 | `BLD-CITY` | 213 | OSM `building` footprint | 真实轮廓 |
| 公共建筑 | `BLD-PUBLIC` | 35 | OSM 医院/学校/车站等 | 真实轮廓 |
| 乡村民居 | `BLD-RURAL` | 40 | 坡地程序化布点（坡屋顶） | **程序化，非真实建筑** |

合计 **825 个构件 / 35507 个三角形**，包围盒 2905 × 1934 × 320 m。

场地范围：`106.5150,29.5425 ~ 106.5450,29.5600`（渝中半岛李子坝—佛图关—两江段）。
OSM 河道中心线确认：北为**嘉陵江**、南为**长江**，与"两江夹峙"地形一致。

## 2. 文件清单

| 文件 | 用途 |
|------|------|
| `model.ifc` | ★ Revit 主交付物：打开后「另存为」即得到 `.rvt` |
| `model.obj` | 按要素分组（g 组名即 ObjectType），供 Blender / Lumion / Twinmotion / 3ds Max |
| `terrain_points.csv` | Revit「体量和场地」→「地形表面」→「通过导入创建」→「指定点文件」，单位选**米** |
| `features.csv` | 要素清单：分类 / 名称 / OSM 类型 / X,Y,Z / 高度 / 占地面积 / 层数 |
| `_osm_cache.json` | OSM 原始数据缓存，重跑时无需再联网 |

## 3. Revit 导入步骤

### 方式 A：导入全要素（推荐）

1. Revit →「打开」→ 文件类型选 **IFC (*.ifc)** → 选 `model.ifc`
2. 弹出「IFC 导入选项」→ 确认 → 生成新项目
3. 「另存为」→ 类型 `.rvt`，完成

导入后构件落在「常规模型」类别（IFC 的 `IfcBuildingElementProxy` 映射为 DirectShape）。
按 ObjectType 批量选中并替换为真实族：

- 「管理」→「选择」→ 或用项目浏览器「常规模型」列表，按名称前缀筛选
- 例如选中所有 `BLD-CITY*`，替换为「多层住宅」族；`ROAD-MTN*` 替换为「道路/梯道」族

### 方式 B：只建地形表面

1. 「体量和场地」→「地形表面」→「通过导入创建」→「指定点文件」
2. 选 `terrain_points.csv`，单位「米」

> 点文件方式只能得到地形，水体/道路/建筑不会进来。要做全要素模型请用方式 A。

## 4. 已知取舍

- **乡村民居是程序化的**。该 bbox 位于渝中核心城区，OSM 无乡村建筑数据；
  脚本在坡度 5~28°、非建成区、非水体的坡地自动布点 40 座坡屋顶民居，
  用于演示城乡差异。`features.csv` 中 `osm_kind` 列标为 `rural(程序化)` 以示区分。
  若要全部真实，可把 bbox 挪到城乡结合部后重跑。
- **建筑高度**：OSM 有 `height` / `building:levels` 的用真值（如某医院 22 层 66 m），
  缺失时按 footprint 面积估算高层，属合理推断而非实测。
- **水体**：河谷形态来自 DEM（真实），水位为参数（默认 168 m），
  可用 `--water-level` 调整到汛期/枯水期水位做对比方案。
- **不要用 `waterway=river` 生成水面**：OSM 的 river 是河道**中心线**（LineString），
  不是面。工具已区分处理，中心线仅用于标注河名。

## 5. 重新生成

```bash
cd hardware-sandbox/tools

# 真实地形版（1:1，与 GIS 对齐）
python build_revit_model.py --fetch --out ../examples/revit_model

# 沙盘实体版（1.2 × 0.8 m，VE=2.5）→ 输出在 ../examples/revit_sandbox
python build_revit_model.py --fetch --target sandbox --out ../examples/revit_sandbox

# 交付前自检
python verify_ifc.py ../examples/revit_model/model.ifc
```

常用参数：`--water-level 175`（汛期水位）、`--rural 0`（不要程序化乡村）、
`--building-scale 1.5`（沙盘上建筑放大以便观看）、`--refresh-osm`（重新抓取 OSM）。
