# datasets —— 真实人工标注数据

本目录存放 **公开数据集的人工标注影像**及其 YOLO 转换产物。不存放合成数据、不存放模型预测框。

```
datasets/
├── _raw/                 # 原始下载包与解压内容（不入库，被 .gitignore 忽略）
├── disaster-v1/          # 旧版（低分辨率滑坡源，保留作对比基线）
├── disaster-v2/          # ★ 当前训练用数据集
│   ├── images/{train,val,test}/
│   ├── labels/{train,val,test}/
│   ├── dataset.yaml
│   ├── stats.json        # 每个源的样本数、类别映射、合并/丢弃计数
│   ├── validation.json   # 完整性/一致性校验结果
│   └── DATASET_CARD.md   # ★ 数据卡：来源、许可、已知偏差、不能声称什么
└── README.md
```

## 当前数据集：disaster-v2

**770 张影像、2632 个实例框、2 类（`crack` / `landslide`）**，全部来自公开数据集的人工标注。

| 类别 | 来源 | 影像 | 实例 | 影像分辨率 | 许可 |
|---|---|---|---|---|---|
| `crack` | fcakyon/crack-instance-segmentation (COCO) | 433 | 634 | 448×448 | 上游未声明 |
| `landslide` | CAS Landslide Dataset · Wenchuan | 178 | 1252 | **512×512** | **CC-BY-NC-4.0** |
| `landslide` | CAS Landslide Dataset · Moxi UAV-1m | 159 | 746 | **512×512** | **CC-BY-NC-4.0** |

### v1 → v2 的关键变化

v1 的滑坡切片只有 122×122（中位），滑坡目标框边长中位仅 10px —— 放大到 640 训练等于纯插值，
细节早已丢失，这是 v1 滑坡 mAP50 只有 0.022 的根因。

v2 换成 CAS Landslide Dataset 的 512×512 子集：**影像面积 ×17.6，实例数 ×3.16，框边长中位 10px → 44px**。

### ⚠ 许可提醒

CAS 是 **CC-BY-NC-4.0（禁止商业使用）**，比 v1 的 CC-BY-4.0 更严格。
学术研究、毕设、内部验证可用（须署名并引用论文）；**任何商业部署必须先替换数据或取得授权**。
详见 `disaster-v2/DATASET_CARD.md` §6.5。

**它不能识别 rockfall / slump / debris flow** —— 这三类没有真实标注数据支撑。

## 重新获取原始数据

原始包合计约 390MB。Zenodo 单连接约 10 KB/s，**不要直接 `curl`**，用多连接分段下载器：

```bash
cd ai-service

# 1) 裂缝（COCO 实例分割，约 14MB，走 hf-mirror）
for s in train valid test; do
  curl -L -o "datasets/_raw/$s.zip" \
    "https://hf-mirror.com/datasets/fcakyon/crack-instance-segmentation/resolve/main/data/$s.zip"
  mkdir -p "datasets/_raw/crack_$s" && unzip -oq "datasets/_raw/$s.zip" -d "datasets/_raw/crack_$s"
done

# 2) CAS 滑坡子集（多连接分段，必须校验 CRC）
.venv/Scripts/python.exe scripts/dataset/fetch_parallel.py \
    "https://zenodo.org/api/records/10294997/files/Wenchuan.zip/content" \
    -o datasets/_raw/cas/Wenchuan.zip -w 24
.venv/Scripts/python.exe scripts/dataset/fetch_parallel.py \
    "https://zenodo.org/api/records/10294997/files/Moxi%20town%EF%BC%88UAV-1m%EF%BC%89.zip/content" \
    -o datasets/_raw/cas/Moxi_town_UAV-1m.zip -w 24

mkdir -p datasets/_raw/cas/wenchuan datasets/_raw/cas/moxi
unzip -q -o datasets/_raw/cas/Wenchuan.zip -d datasets/_raw/cas/wenchuan
unzip -q -o datasets/_raw/cas/Moxi_town_UAV-1m.zip -d datasets/_raw/cas/moxi

# 3) 【可选】v1 的两个低分辨率滑坡源，仅在需要复现 v1 时下载
#   Nepal: https://zenodo.org/api/records/3688363/files/Nepal%20landslide%20dataset.rar/content
#   Italy: https://zenodo.org/api/records/4287425/files/Italy%20landslide%20dataset.rar/content
```

> 下载后**必须**跑 `unzip -t` 校验。Zenodo 断流会产生"文件大小完全正确但 CRC 损坏"的 zip，
> 直接解压会在中途报 `bad CRC`，很容易被误判为数据源问题。

## 重建与校验

```bash
cd ai-service

# 构建（幂等：换源重跑会自动清理上次产物，并做文件数一致性自检）
.venv/Scripts/python.exe scripts/dataset/build_yolo_dataset.py \
    --config scripts/dataset/configs/disaster_v2.json --out datasets/disaster-v2

# 校验（有错误时退出码非 0）
.venv/Scripts/python.exe scripts/dataset/validate_yolo_dataset.py \
    --data datasets/disaster-v2 --samples datasets/disaster-v2/samples
```

构建脚本内置四道防假措施：

1. **源内去重**：同一数据集里 MD5 相同的影像只留一份。
2. **跨划分去重**：同一内容若同时落在 train/val/test，保留优先级最高的一份（实测命中 0）。
3. **碎片合并**：语义分割掩码用闭运算（kernel=15）把同一条滑坡的碎片连通域粘回一体，
   否则 512×512 图会产出几十个 16px 的框，检测退化为碎片学习。合并量记入 `stats.json`。
4. **填充修复**：旋转裁切留下的纯黑/纯白三角改用"最大内接矩形"裁掉（不生成任何像素），
   仅 1 张因有效区过小整张丢弃。替代 inpaint 以消除人造涂抹纹理伪影；不处理会让模型学到"黑三角 = 滑坡上下文"的虚假相关。

## 空间泄漏专项

航拍切片常出现"相邻瓦片高度重叠 → train/val 泄漏 → 指标虚高"。v2 对两个滑坡子集做了
16×16 dHash 全量两两比对：最近邻汉明距离中位 103 / 101（随机基线 127），最小 57 / 49，
**没有近似重复切片**。校验脚本的跨划分 MD5 去重同样为 0。详见数据卡 §5.1。
