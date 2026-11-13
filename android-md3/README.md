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

## 1.2.0 — 算法级能力补齐 + 16 项核心算法测试

对照 `lib/genome`（23k 行，60+ 导出函数）与 `lib/gene` 逐项核查，补齐 5 类能力。

### 新增能力
| 原版实现 | 原生实现 | 说明 |
|---|---|---|
| `genome/primerAnalysis.ts` | `core/PrimerAnalysis.kt` | SantaLucia 1998 最近邻 Tm（16 组二核苷酸参数 + 盐修正 + 末端 AT 惩罚）、NEB Tm、Ta 近似、自/交叉二聚体（反向互补连续 ≥4）、发夹（茎区反向互补搜索）、3′ 端稳定性、poly-X |
| `genome/alignment.ts` | `core/SangerValidate.kt` | k-mer 定锚 + 半全局 Needleman-Wunsch，输出一致性、错配/插入/缺失、覆盖区间、变异列表与判定 |
| `genome/cloningMethods.ts`（8 种） | `core/CloningMethods.kt` | 限制酶 / TA / TOPO / Gateway（attB1-B2）/ In-Fusion / NEBuilder HiFi / 重叠延伸 PCR / GC 克隆，含同源臂校验、读框与内部终止检查 |
| `genome/crispr.ts` `predictOffTargets` | `CloningPlus.predictOffTargets` | 全序列扫描 ≤3 错配 + NGG PAM，3′ 错配优先警示 |
| `genome/formats.ts` | `core/SeqIO.kt` 扩展 | FASTQ 读取（含平均质量 Q）、EMBL 读取与导出、格式自动分派 |

### UI 接入
- 克隆 → 扩增引物设计表：显示 NN/NEB 双 Tm、3′ 稳定性、交叉二聚体、建议 Ta、逐条警告
- 克隆 → 载体模板组装：8 种方法 chip 切换，实时输出产物长度、所需同源臂、步骤与警告
- 克隆 → CRISPR：新增以当前序列为基因组的脱靶扫描
- 分析 → Sanger 色谱：read 与参考序列比对，给出一致性/变异/覆盖

### 测试（`:app:testDebugUnitTest`，16 项全部通过）
酶库 532 组/3732 名/820 口径、别名大小写、IUPAC 切点、引物 Tm 区间、二聚体检出、发夹检出、Sanger 完全匹配、Sanger 单碱基错配检出、8 种克隆方法均可运行、TA 无 A 尾告警、Gateway 缺 att 告警、CRISPR 脱靶约束、FASTQ 往返、EMBL 往返、mmCIF 导出与回读、PDB 仅取 CA、SHA-256 标准向量。

测试过程中修掉两个真实缺陷：二聚体判据把“反向互补后再互补”写反（恒为假，永远检不出二聚体）；发夹检测的双重互补循环恒不成立。

