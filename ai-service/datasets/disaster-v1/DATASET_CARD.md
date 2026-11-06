# 灾害影像检测数据集 · 数据卡（disaster-v1）

> 版本：v1　构建时间：2026-11-05　构建脚本：`scripts/dataset/build_yolo_dataset.py`
> 校验脚本：`scripts/dataset/validate_yolo_dataset.py`　最近一次校验：**通过**

---

## 1. 一句话说明

这是一份 **767 张图像、1263 个实例框、2 个类别**的 YOLO 检测数据集，全部标注来自**公开数据集的人工标注**，
经确定性几何转换得到，**不含任何模型预测框、不含随机生成框、不含合成图像**。

它**不能**识别 rockfall（落石）、slump（崩塌）、debris（泥石流）—— 这三类没有任何真实标注数据支撑，
早期版本在代码里写死过 5 类，属于虚报能力，已在本版本收敛为真实覆盖的 2 类。

---

## 2. 数据来源

| 类别 | 来源 | 影像数 | 实例数 | 原始标注 | 许可 |
|---|---|---|---|---|---|
| `crack` | [fcakyon/crack-instance-segmentation](https://huggingface.co/datasets/fcakyon/crack-instance-segmentation) | 433 | 634 | COCO 实例分割（bbox + polygon） | **上游未声明**，Roboflow 社区导出 |
| `landslide` | [Nepal landslide dataset (Zenodo 3688363)](https://zenodo.org/records/3688363) | 230 | 442 | 人工像素级掩码（0/1 PNG） | CC-BY-4.0 |
| `landslide` | [Italy landslide dataset (Zenodo 4287425)](https://zenodo.org/records/4287425) | 104 | 187 | 人工像素级掩码（0/1 PNG） | CC-BY-4.0 |

### 2.1 关于 crack 类别名的合并

上游 COCO 里有 `cracks-and-spalling`（203+44+22 实例）和 `object`（256+58+51 实例）两个类。
`object` 是 Roboflow 导出时丢失类别名的占位符。人工抽检 6 张 `object` 裁剪图，6/6 均为混凝土/路面裂缝，
因此把两者合并为 `crack`。逐项映射计数见 `stats.json` 的 `class_mapping` 字段，可复核。

### 2.2 为什么弃用 Nepal 的官方划分

Nepal 原始包提供 Training(230) / Validation(35) / Test(10)。实测发现 **Validation 的 35 张里有 33 张
与 Training 内容完全相同**（MD5 一致），直接使用会让验证指标虚高。因此本数据集只取其 Training 的 230 张，
按文件名排序做确定性重划分（8:1:1）。Italy 上游无划分，同样按排序确定性划分。

---

## 3. 类别体系

| id | 名称 | 含义 | 实例数 |
|---|---|---|---|
| 0 | `crack` | 混凝土/路面/墙面裂缝与剥落 | 634 |
| 1 | `landslide` | 滑坡疤痕（航拍/卫星视角） | 629 |

**未覆盖**：rockfall、slump（结构性崩塌）、debris flow、以及"normal"背景类。
YOLO 本身不需要 normal 类，无目标的图用空标签文件表示（本数据集有 41 张空标签图）。

---

## 4. 标注是怎么来的（可审计）

| 步骤 | 操作 | 是否引入新信息 |
|---|---|---|
| 1 | COCO 的 `bbox` 字段直接做像素→归一化线性映射 | 否，纯单位换算 |
| 2 | COCO 中缺 bbox 的实例，取其 polygon 的外接矩形 | 否，几何运算 |
| 3 | 掩码源：对人工二值掩码做 8 邻域连通域分析，每个连通域取外接矩形 | 否，几何运算 |
| 4 | 面积 < 20px 的连通域视为标注噪点丢弃 | 是（过滤），已计数：190 个 |
| 5 | 超出图像边界的框裁剪到边界内 | 是（裁剪），已计数：53 个 |

**没有做**：图像增强、随机裁剪、模型预标注、标签插值。
`samples/` 目录下是随机抽样的叠框可视化，可直接目检标注是否贴合。

---

## 5. 校验结果

来自 `validation.json`（由 `validate_yolo_dataset.py` 生成）：

| 检查项 | 结果 |
|---|---|
| 图像 / 标签配对 | 767 / 767，无孤儿 |
| 标签格式（5 列、数值） | 全部合法 |
| 坐标范围 [0,1] | 全部合法 |
| class id 越界 | 0 |
| 不可解码图像 | 0 |
| **跨 split 内容泄漏** | **0** |
| 极小框（短边 < 0.01） | 0 |

划分与分布：

| split | 图像 | 实例 | 空标签图 |
|---|---|---|---|
| train | 590 | — | 30 |
| val | 106 | — | 5 |
| test | 71 | — | 6 |
| 合计 | **767** | **1263** | 41 |

---

## 6. 已知偏差与限制（务必阅读）

1. **两个类别尺度差异极大。** crack 是 448×448 的近距离拍摄，框中位边长 210px；landslide 是航拍小图块
   （Nepal 约 137×137、Italy 100×100），框中位边长只有 **10px**。同一模型很难同时做好这两类。
   建议分两个模型训练，或在训练时用 `imgsz=1024` 并开启 SAHI 切片推理。
2. **crack 有相当比例的"区域级"大框。** 634 个框里有 136 个面积超过半图（标注的是整片剥落区域而非单条裂缝），
   这类框会让模型倾向输出大框。若下游需要"单条裂缝"粒度的框，需重新标注。
3. **landslide 目标极小、图像分辨率低。** 原图仅 100–140px，放大到 640 训练后细节仍然有限。
   这是当前公开数据的天花板，不是代码问题。要提升滑坡识别精度，必须补高分辨率（≥1024px）的航拍/卫星标注数据。
4. **类别不均衡。** crack 634 : landslide 629 实例数接近，但 landslide 分在两个地域子集上，
   Italy 子集图像模糊，实际有效信息量低于 Nepal。
5. **许可风险。** crack 数据集上游未声明明确许可，当前按"公开研究数据集"使用；**商用前须向原始作者确认**。
   两个滑坡数据集是 CC-BY-4.0，使用时必须署名原始作者与 Zenodo DOI。
6. **这不是本项目自己采集的数据。** 若对外宣称"自建灾害影像数据集"，属于虚假陈述。
   正确表述是"基于三个公开人工标注数据集构建的灾害检测数据集"。

---

## 7. 复现方式

原始数据因 Zenodo 限速未入库，需按下表下载后放到 `ai-service/datasets/_raw/`：

| 文件 | 下载地址 | 放置位置 |
|---|---|---|
| crack train/valid/test.zip | `https://hf-mirror.com/datasets/fcakyon/crack-instance-segmentation/resolve/main/data/{split}.zip` | 解压到 `_raw/crack_{train,valid,test}/` |
| Nepal landslide dataset.rar | `https://zenodo.org/api/records/3688363/files/Nepal%20landslide%20dataset.rar/content` | 解压到 `_raw/nepal/` |
| Italy landslide dataset.rar | `https://zenodo.org/api/records/4287425/files/Italy%20landslide%20dataset.rar/content` | 解压到 `_raw/italy/` |

> rar 解压：本机无 unrar 时可用 `bsdtar -xf xxx.rar -C 目标目录`（libarchive 支持 RAR5）。

然后：

```bash
cd ai-service

# 1) 构建（幂等，可重复执行）
.venv/Scripts/python.exe scripts/dataset/build_yolo_dataset.py \
    --config scripts/dataset/configs/disaster_v1.json \
    --out datasets/disaster-v1

# 2) 校验（失败时退出码非 0，可接 CI）
.venv/Scripts/python.exe scripts/dataset/validate_yolo_dataset.py \
    --data datasets/disaster-v1 --samples datasets/disaster-v1/samples

# 3) 训练（需要 ultralytics + torch）
.venv/Scripts/python.exe scripts/training/train_crack_yolo.py \
    --data datasets/disaster-v1 --epochs 50 --imgsz 640 --export-onnx
```

产物：`datasets/disaster-v1/{images,labels}/{train,val,test}`、`dataset.yaml`、`stats.json`、`validation.json`。

---

## 8. 冒烟训练验证（2026-11-05 实测）

为确认「数据集真的能训」，在本机 CPU 上跑了 3 epoch（不是最终模型，仅验证管线）：

```bash
python scripts/training/train_crack_yolo.py \
    --data datasets/disaster-v1 --epochs 3 --imgsz 640 --project runs/smoke --name v1
```

| 指标 | 全部 | crack | landslide |
|---|---|---|---|
| Precision | 0.178 | 0.268 | 0.088 |
| Recall | 0.219 | 0.363 | 0.076 |
| mAP50 | **0.121** | **0.220** | 0.022 |
| mAP50-95 | 0.045 | 0.081 | 0.008 |

耗时 8 分钟（i9-13980HX，CPU，590 张/epoch 约 95 秒）。

**怎么读这组数字**：

- 管线是通的：数据加载、标签解析（590 张 / 30 张背景图被正确识别）、训练、验证、导出全部正常。
- **0.121 的 mAP50 不能当作模型能力**。3 epoch 远未收敛，只够证明"能训"。
- crack 已经有 0.22，说明这个类别的标注和分布是可学的；landslide 只有 0.022，印证第 6 节第 3 条
  ——10px 的目标在 640 输入下几乎不可见。
- 导出 ONNX 后输出形状为 `[1, 6, 8400]`（4 + 2 类），被 `app/models/yolo_model.py` 正确识别为
  业务模型（`num_classes=2`），对真实裂缝图输出 `crack` 置信度 0.93/0.63/0.57。

**没有覆盖 `models/yolo_crack.onnx`**：3 epoch 的权重不值得上线，保留 COCO 占位模型并让代码明确告警，
比替换成一个"看起来能识别但很弱"的模型更诚实。要产出可用模型，至少 50–100 epoch，
并建议把 crack 与 landslide 拆成两个模型分别训练。

---

## 9. 引文

```
Nepal landslide dataset — Ghorbanzadeh et al., Zenodo, doi:10.5281/zenodo.3688363, CC-BY-4.0
Italy landslide dataset — Zenodo, doi:10.5281/zenodo.4287425, CC-BY-4.0
crack-instance-segmentation — fcakyon, exported from Roboflow Universe, license unspecified upstream
```
