# SPICE 原生 Android 版（Kotlin + Jetpack Compose + Material 3）

纯原生渲染、零 WebView。`native` 分支上的 Kotlin 实现（同分支另有 Rust + egui 版本）。

## 构建

```bash
export JAVA_HOME=<jdk17>
export ANDROID_HOME=<android-sdk>
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

- `applicationId com.spice.nativeapp`，`minSdk 24`，`targetSdk 29`
- 签名经 `SPICE_KS / SPICE_KSPASS / SPICE_ALIAS` 注入
- 0.5.0 起内置 onnxruntime-android 原生库（arm64-v8a / armeabi-v7a），模型权重首次使用时从 HuggingFace 下载，不打包进 APK
- TLS 中间人代理网络下需给 Gradle JVM 指定信任库
- AGP 9 需 `android.builtInKotlin=false`

## 功能对照（vs WebView 版）

| WebView 原版 | 原生实现 | 状态 |
|---|---|---|
| 底部导航 / 顶栏 | MD3 Scaffold + NavigationBar 六 Tab | 一致 |
| 环状质粒图谱 | Canvas 骨架 + 特征弧 + 自适应刻度 + 酶切刻度 + 拖动旋转 + 点选 | 基本一致（无滚轮缩放） |
| 线性图谱 | 特征分道 + 刻度尺 + 酶切竖线 | 一致 |
| 序列视图 | 60 bp/行、碱基着色、切点标记 | 一致 |
| 酶切分析 | 46 酶 + IUPAC + 环状跨起点 | 酶库子集 |
| 双酶切推荐 | 缓冲液兼容配对 | 一致 |
| PCR 引物 | 最近邻 Tm + GC clamp + 二聚体粗筛 | 一致 |
| Gibson / Golden Gate | 同源臂 + BsaI 位点筛查 | 一致 |
| 蛋白性质 | MW / pI / pH7 电荷 / GRAVY / A280 | 一致 |
| 蛋白 3D | 螺旋+β 启发式骨架，可旋转 | **非 ONNX 预测** |
| 凝胶模拟 | log10 迁移 + 3 套 ladder + 泳道/片段表 | 一致 |
| 凝胶照片识别 | 灰度 → 泳道切分 → 行投影峰 → log10 标定 bp | 已实现（纯 Kotlin） |
| qPCR | ΔΔCt + 效率自检 | 计算版（无 Ct 表导入） |
| Western blot | 泳道强度 + 内参归一化 | 示意版 |
| 96 孔板 | 8×12 布板 + 四类计数 | 一致 |
| 系统树 | FASTA + 4-mer Jaccard + UPGMA | 简化（无 MSA） |
| 菌落计数 | 手工点选 + 照片自动计数（连通域） | 已实现 |
| 图形工作室 | 盒/标签/箭头/位图 + 描述 + PNG 1600×900 + 原生 OOXML PPTX（形状/文本/箭头可编辑，位图嵌入 media 部件并挂 rels） | 已实现 |
| 图像标注 | 1/2/4/6 面板 + 比例尺 + 图注 | 排版版 |
| 文件 IO | SAF 打开 FASTA/GenBank + 剪贴板 + 分享 | 一致 |
| PDF 报告 | PdfDocument 单页 A4 + MediaStore 分享 | 已实现 |
| AI 助手 | 本地规则 + OpenAI 兼容对话（自填 Key） | 已实现（需自备 Key） |
| 项目中心 | JSON 持久化 + 备注 + 操作历史 | 部分一致（无附件/演示） |

## 已知缺口

1. **ONNX 权重推理**：原版 GelGenie U-Net 运行时从 HuggingFace 下载；原生用经典 CV 顶替。要逐像素一致需集成 onnxruntime-android + 权重（APK +~100 MB）。
2. ~~PPTX 导出~~ 已实现（0.6.0）；剩余多文档富文本、blot 多膜内参校准、qPCR 仪器 XLSX 直读。
3. **项目进阶**：附件管理、演示模式、多文档富文本。
4. **照片定量**：blot 灰度定量、qPCR 板图导入。

## 1.0.0 — 按原版菜单逐项补齐（原版基因页 39 项 + 蛋白页 4 组）

审计对象：WebView 版 `src/routes/gene/+page.svelte` 的 retro 菜单栏（FILE 9 项 / EDIT 8 项 / CLONING 22 项）与 `src/routes/protein/+page.svelte`（FILE / MODELING / MUTATION / WINDOW）。

### 基因页 · FILE（9/9 已接线）
| 原版菜单 | 原生实现 |
|---|---|
| 打开序列文件… | SAF OpenDocument → `SeqIO.parse`（GenBank/FASTA 自动识别） |
| 从 NCBI 导入… | `SeqFetch` 调 E-utilities `efetch` 取 FASTA |
| 从 Ensembl 导入… | REST `sequence/id/{id}?type=genomic` |
| 从 UniProt 导入… | REST `uniprotkb/{acc}.fasta` |
| 导入测序色谱 (.ab1/.scf) | 新增 `AbiReader` 解析 ABIF 的 PBAS/BASE 段取碱基 |
| 项目集合管理… | 跳转项目页 |
| 保存项目 | 当前序列写入 ProjectStore |
| 导出当前序列… | SAF CreateDocument 写 GenBank |

### 基因页 · EDIT（8/8）
撤销 / 重做（20 步栈）、反向互补、清除选择区、添加特征注释、自动注释特征（启动子/引物/AmpR/KanR/ori/6xHis 命中标注）、序列搜索（含反向互补，命中直接设为选区）、切换拓扑。

### 基因页 · CLONING（22/22，全部有落地面板）
设计舱、酶切位点与片段长度、密码子优化（规避 BsaI/BsmBI）、引物设计表（Tm/GC/警告）、PCR 仿真（退火温度与延伸时间）、凝胶预测迁移、表达盒构建器、载体同源臂、CRISPR（NGG PAM + GC/3' 偏好打分）、连接/稀释/PCR 体系计算、引物库存、合成基因 QC（GC/同聚物/重复/位点）、NGS 统计与 ORF、标记引物、退火寡核苷酸、沉默突变、反向翻译、5' 修饰、末端编辑、融合阅读框、核糖体滑移。

### 蛋白页（4 组）
- 文件：导出 FASTA / 性质报告；PDB、mmCIF 结构解析仍属桌面端能力
- 建模：折叠推理、MD 验证 —— **依赖 `spice_infer_fixed.onnx`（无公开权重）与 SPICE Engine，未移植**，点击给出明确说明
- 突变：高疏水残基扫描、单点突变设计（同义密码子 + 疏水性变化）、批量筛选（依赖 ΔΔG 打分，未移植）
- 窗口：面板切换说明

### 仍未移植（需外部引擎/数据）
折叠推理、MD 验证、ΔΔG 批量筛选、MAFFT 多序列比对、SPD 公共库发布。其余菜单项均已可用。

