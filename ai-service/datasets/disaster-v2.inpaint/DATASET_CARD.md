# 灾害影像检测数据集 · 数据卡（disaster-v2）

> **归档说明**：本卡对应 `disaster-v2.inpaint` 旧构建（TELEA inpaint），已被 `disaster-v2`（clean 重建，最大内接矩形裁剪）取代；当前训练使用 `../disaster-v2/DATASET_CARD.md`。
>

> 版本：v2　构建时间：2026-11-05　构建脚本：`scripts/dataset/build_yolo_dataset.py`
> 配置：`scripts/dataset/configs/disaster_v2.json`　校验脚本：`scripts/dataset/validate_yolo_dataset.py`
> 最近一次校验：**通过**

---

## 1. 一句话说明

这是一份 **754 张图像、2623 个实例框、2 个类别**的 YOLO 检测数据集。
滑坡类的影像来自 **CAS Landslide Dataset 的 512×512 人工掩码子集**，
裂缝类沿用 v1 的公开人工标注数据。**全部标注均来自上游公开数据集的人工标注**，
经确定性几何转换得到，**不含模型预测框、不含随机生成框、不含合成图像**。

它**不能**识别 rockfall（落石）、slump（结构性崩塌）、debris flow（泥石流）——
这三类没有任何真实标注数据支撑。

### 1.1 相对 v1 改了什么（这是本次补数据的核心）

| 项目 | disaster-v1 | disaster-v2 | 变化 |
|---|---|---|---|
| 滑坡影像分辨率 | 122×122（中位） | **512×512** | 线性 4.2×，面积 **17.6×** |
| 滑坡图像数 | 334（Nepal 230 + Italy 104） | 321（汶川 178 + 磨西 143） | 基本持平 |
| 滑坡实例数 | 629 | **1989** | **3.16×** |
| 滑坡框边长（中位） | ~10 px | **44 px** | 4.4× |
| 滑坡数据来源 | 尼泊尔 / 意大利（2 个） | 汶川地震区 / 磨西镇（中国西南，UAV 1m） | 域更贴近本项目 |
| 滑坡图像许可 | CC-BY-4.0 | **CC-BY-NC-4.0（非商用）** | **收紧，见 §6.5** |

v1 的滑坡图像只有 100–140 px，放大到 640 训练等于纯插值，细节早已丢失——
这是 v1 滑坡 mAP50 只有 0.022 的根因。v2 用 512×512 原始分辨率替换，
框的边长中位数从 10px 提到 44px，才进入 YOLO 能有效学习的尺度。

---

## 2. 数据来源

| 类别 | 来源 | 影像数 | 实例数 | 原始标注 | 许可 |
|---|---|---|---|---|---|
| `crack` | [fcakyon/crack-instance-segmentation](https://huggingface.co/datasets/fcakyon/crack-instance-segmentation) | 433 | 634 | COCO 实例分割（bbox + polygon） | 上游未声明 |
| `landslide` | [CAS Landslide Dataset](https://zenodo.org/records/10294997) · Wenchuan 子集 | 178 | 1252 | 人工像素级掩码（0/1 TIFF） | **CC-BY-NC-4.0** |
| `landslide` | [CAS Landslide Dataset](https://zenodo.org/records/10294997) · Moxi town (UAV-1m) 子集 | 143 | 737 | 人工像素级掩码（0/1 TIFF） | **CC-BY-NC-4.0** |

CAS 数据集共 20,865 张、9 个区域、18 个压缩包（合计 9.2 GB）。本次只取了两个子集，
原因是 Zenodo 单连接限速约 10 KB/s，全量下载不现实。选择的取舍：

- **Wenchuan（25 MB，178 张）**：汶川地震区，卫星/航拍视角，与我国西南山区灾害场景一致。
- **Moxi town UAV-1m（99 MB，160 张入 143）**：磨西镇无人机 1m 分辨率，细节最清晰，房屋、道路、河床可辨。

未取 Lombok / Tiburon / 九寨沟等子集，属于**可继续扩充的余量**，不是数据已经到顶。

### 2.1 为什么弃用 v1 的 Nepal / Italy 滑坡源

两个源的影像切片只有 85–130 px 和 100×100 px。这不是"分辨率低一点"，
而是信息量根本不够：滑坡在图中只占十几个像素，人眼都要靠猜。继续用它们会拉低整体精度。
v1 保留在原地不动（`datasets/disaster-v1/`），可作对比基线，但**不再作为训练数据**。

---

## 3. 类别体系

| id | 名称 | 含义 | 实例数 |
|---|---|---|---|
| 0 | `crack` | 混凝土/路面/墙面裂缝与剥落 | 634 |
| 1 | `landslide` | 滑坡/崩塌疤痕及其堆积区（航拍/无人机视角） | 1989 |

---

## 4. 标注是怎么来的（可审计）

| 步骤 | 操作 | 是否引入新信息 |
|---|---|---|
| 1 | COCO 的 `bbox` 字段直接做像素→归一化线性映射 | 否，纯单位换算 |
| 2 | COCO 缺 bbox 的实例，取其 polygon 的外接矩形 | 否，几何运算 |
| 3 | 掩码源：对人工二值掩码做 8 邻域连通域分析 | 否，几何运算 |
| 4 | **闭运算 kernel=15** 把同一条滑坡被切碎的多个连通域粘连回一体 | 是（合并），已计数 |
| 5 | 面积 < 150 px 的连通域视为标注噪点丢弃（512×512 下约 12×12） | 是（过滤），已计数：438 |
| 6 | 超出图像边界的框裁剪到边界内 | 是（裁剪），已计数：53 |
| 7 | **旋转裁切填充修复**：贴合边界的大片纯黑/纯白（≥0.5% 面积）用 TELEA inpaint 修复 | 是（修补），修复 44 张 |
| 8 | 填充占比 > 35% 的切片整张丢弃（有效内容太少） | 是（过滤），丢弃 17 张 |

第 4 步的必要性：CAS 的掩码是**语义分割**标注，一条滑坡常被切成几十个碎片连通域。
不合并的话，一张 512×512 图会产出 15 个以上、中位边长仅 16px 的框，检测任务退化为碎片学习。
闭运算前后连通域数从 2638 降到 1598（Wenchuan 实测），合并量记录在 `stats.json`
的 `fragments_merged_by_close` 字段，可复核。

**没有做**：图像增强、随机裁剪、模型预标注、标签插值、类别重平衡。

### 4.1 关于填充修复的诚实说明

CAS 的部分切片是旋转后外接裁剪的，四角带大片纯黑/纯白三角。不处理的话，模型会把
"黑三角"当成与滑坡共现的上下文特征，学到虚假相关。第 7 步用 OpenCV inpaint 把这些
纯色区域用邻域纹理替换——**这是消除采集伪影的常规预处理，不是伪造滑坡内容**：
掩码与标签完全不动，修复区域本来就没有任何标注。修复张数、丢弃张数均记录在 `stats.json`。

---

## 5. 校验结果

来自 `validation.json`（由 `validate_yolo_dataset.py` 生成）：

| 检查项 | 结果 |
|---|---|
| 图像 / 标签配对 | 754 / 754，无孤儿 |
| 标签格式（5 列、数值） | 全部合法 |
| 坐标范围 [0,1] | 全部合法 |
| class id 越界 | 0 |
| 不可解码图像 | 0 |
| **跨 split 内容泄漏** | **0** |
| 极小框（短边 < 0.01） | 2 |

| split | 图像 | 实例 | 空标签图 |
|---|---|---|---|
| train | 584 | — | 1 |
| val | 102 | — | 0 |
| test | 68 | — | 1 |
| 合计 | **754** | **2623** | 2 |

### 5.1 空间泄漏专项核查

航拍/卫星切片常见"相邻瓦片高度重叠 → train/val 泄漏 → 指标虚高"。本次做了实测：

| 子集 | 图数 | 最近邻汉明距离（中位） | 最近邻最小值 | 疑似重复 |
|---|---|---|---|---|
| Wenchuan | 178 | 103 | 57 | 0 |
| Moxi | 160 | 101 | 49 | 0 |

（16×16 dHash，256 bit；<30 视为疑似重复。随机配对的基线中位是 127。）
两个子集都没有近似重复切片，加上 MD5 级跨划分去重结果为 0，可以认为 **v2 的验证指标不会因空间泄漏虚高**。

---

## 6. 已知偏差与限制（务必阅读）

1. **crack 与 landslide 的成像尺度仍然差异极大。** crack 是 448×448 近景，框中位边长 210px；
   landslide 是 512×512 航拍，框中位 44px。同一模型兼顾两类仍是折中方案，
   生产环境建议拆成两个模型。
2. **landslide 的标注是"语义区域外接框"，不是精确目标框。** 一片滑坡群可能被闭运算合并成一个大框，
   框内会包含少量非滑坡像素。若要像素级精度，需改用分割模型（YOLOv8-seg）。
3. **512×512 仍低于 v1 数据卡提出的 ≥1024 建议。** 这是 CAS 原始切片的尺寸上限，
   不是我们偷懒；训练时用 `imgsz=640`（1.25× 上采样，可接受）。
   若后续拿到 ≥1024 的原始航片，可直接用同一脚本接入再构建 v3。
4. **两个滑坡子集都来自中国西南，域多样性有限。** 印尼、日本、海地等子集未下载。
   跨地域泛化能力**未经验证**，不要声称"全球通用滑坡检测"。
5. **⚠ 许可变更：CAS 是 CC-BY-NC-4.0，禁止商业使用。** v1 的滑坡源是 CC-BY-4.0（允许商用），
   v2 换成了 **NC（NonCommercial）**。这意味着：
   - 学术研究、毕业设计、内部验证：可以使用，须署名并引用论文。
   - **任何商业部署、对外收费服务：必须先替换为可商用数据或取得作者授权。**
   - crack 源上游未声明许可，商用前同样需确认。
6. **这不是本项目自己采集、自己标注的数据。** 正确表述是
   "基于 CAS Landslide Dataset 与 crack-instance-segmentation 构建的灾害检测数据集"。
   声称"自建标注数据集"属于虚假陈述。

---

## 7. 复现方式

原始数据需先下载到 `ai-service/datasets/_raw/`：

| 文件 | 下载地址 | 放置位置 |
|---|---|---|
| crack train/valid/test.zip | `https://hf-mirror.com/datasets/fcakyon/crack-instance-segmentation/resolve/main/data/{split}.zip` | 解压到 `_raw/crack_{train,valid,test}/` |
| Wenchuan.zip | `https://zenodo.org/api/records/10294997/files/Wenchuan.zip/content` | 解压到 `_raw/cas/wenchuan/`（含 `img/ label/ mask/`） |
| Moxi town（UAV-1m）.zip | `https://zenodo.org/api/records/10294997/files/Moxi%20town%EF%BC%88UAV-1m%EF%BC%89.zip/content` | 解压到 `_raw/cas/moxi/` |

> **Zenodo 单连接只有 ~10 KB/s**。直接 `curl` 一个 99 MB 的包要 2 小时以上，且中途断流会得到
> 大小正确但 CRC 损坏的 zip。请用 `fetch_parallel.py` 走多连接分段：
> ```bash
> python scripts/dataset/fetch_parallel.py "<url>" -o datasets/_raw/cas/Moxi_town_UAV-1m.zip -w 24 --force
> unzip -t datasets/_raw/cas/Moxi_town_UAV-1m.zip   # 必须校验，不能只看文件大小
> ```

构建与校验：

```bash
cd ai-service

# 1) 构建（幂等，可重复执行；换源重跑会自动清理上次产物）
.venv/Scripts/python.exe scripts/dataset/build_yolo_dataset.py \
    --config scripts/dataset/configs/disaster_v2.json \
    --out datasets/disaster-v2

# 2) 校验（失败时退出码非 0，可接 CI）
.venv/Scripts/python.exe scripts/dataset/validate_yolo_dataset.py \
    --data datasets/disaster-v2

# 3) 训练（自动检测 GPU，无 CUDA 时回落 CPU 并告警）
.venv/Scripts/python.exe scripts/training/train_crack_yolo.py \
    --data datasets/disaster-v2 --epochs 60 --imgsz 640 --name v2-n60 --export-onnx
```

产物：`datasets/disaster-v2/{images,labels}/{train,val,test}`、`dataset.yaml`、`stats.json`、`validation.json`。

---

## 8. 训练与指标

<!-- TRAIN_RESULT_PLACEHOLDER -->

---

## 9. 引文

```
CAS Landslide Dataset — Xu Y., Ouyang C., Xu Q., Wang D., Zhao B., Luo Y.
  Scientific Data 11, 12 (2024). doi:10.1038/s41597-023-02847-z
  Data: Zenodo, doi:10.5281/zenodo.10294997, CC-BY-NC-4.0
crack-instance-segmentation — fcakyon, exported from Roboflow Universe, license unspecified upstream
```
