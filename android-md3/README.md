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
| AI 助手 | SPICE AI Co-Pilot（OpenAI 兼容端点 + 工作区上下文 + 突变/DNA 块一键导入） | 已实现（需自备 Key） |
| 项目中心 | JSON 持久化 + 备注 + 操作历史 | 已实现（附件/多文档/演示模式/qPCR XLSX/blot 多膜） |

## 已知缺口

1. **ONNX 权重推理**：原版 GelGenie U-Net 运行时从 HuggingFace 下载；原生用经典 CV 顶替。要逐像素一致需集成 onnxruntime-android + 权重（APK +~100 MB）。

## 1.7.1/1.7.2 — AI 默认配置内置 + ONNX 神经网络核设置面板

- **1.7.1**：AI 默认配置预填 apihub.agnes-ai.com 端点 + agnes-3.0-flash，首次启动开箱即用
- **1.7.2**：补齐 main 设置页「ONNX 神经网络核」面板（此前缺失）——模型加载状态 LED + 路径/大小展示、
  手动指定本地模型绝对路径（应用）、从 SPICE 云端下载固定权重（URL 持久化 + 进度条 + 下载后回填路径）。
  原生版暂未集成 onnxruntime 推理运行时（已知缺口），面板负责权重就位与状态管理

## 1.7.0 — SPICE AI Co-Pilot 移植 + endpoint 归一化修复

对齐 main 分支全局 AI 侧栏（`AiCopilot.svelte` + `aiState.svelte.ts` + `src/lib/ai/*`）：

- **`core/AiClient.kt`**：OpenAI 兼容传输层（非流式 chat completion、模型列表、测试连接、错误分类 AUTH/RATE_LIMIT/SERVER/NETWORK/TIMEOUT/HTTP/PARSE、429 Retry-After 解析）
  - **修复 main 侧 endpoint bug**：main 直接 POST 用户填写的 endpoint，填 base URL（如 `https://apihub.agnes-ai.com/v1/`）时打到 `/v1` 报 404 `Invalid URL (POST /v1)`。原生实现 `normalizeChatUrl`：去空白、去尾斜杠、缺 `/chat/completions` 时自动补全（已含则原样保留），`deriveModelsUrl` 同源推导 `/models`
  - 已用真实端点验证：base URL + agnes-3.0-flash 此前 404，归一化后 200 正常回包
- **`core/AiBlocks.kt`**：main `src/lib/ai/parse.ts` 的 1:1 移植——```json 突变块（裸数组或 `{"mutations":[...]}` 包装、pos≥1 整数、from/to 大写）与 ```dna 块（去空白、大写、IUPAC 校验）解析；`validateMutations` 对照当前序列拒绝野生型不匹配/越界/非法残基
- **`ui/AiCopilotSheet.kt`**：基因/蛋白页右上浮动 AI 按钮打开——聊天 + 设置双 Tab、工作区上下文注入（序列截断 4000）、配置持久化（SharedPreferences）、测试连接、助手回复中的突变块一键导入蛋白购物车、DNA 块一键应用为基因页序列
- 系统提示与欢迎文案对齐 main（浓缩为无工具场景）；默认配置内置 apihub.agnes-ai.com 端点 + agnes-3.0-flash（1.7.1 起）

**本版相对 main 的已知取舍**：非流式（无 SSE 打字机效果）；未移植 agent 工具调用与 MCP（tools.ts 800+ 行）；未移植本地工具箱三件套（突变稳定器/密码子优化/SOP 生成——对应原生功能已在各页面/计算器中）；聊天历史不跨 sheet 关闭持久化（main 存 localStorage）。

**测试**：新增 21 项（归一化 10 + 块解析 11），总 138/138 通过。

## 1.6.6 — Material 3 主题规范修复

`Theme.kt` 重写 + 全局卡片容器色规范化：

- **色板补全**：light/dark 两套 scheme 补齐全部 MD3 色彩角色（primary/secondary/tertiary/error 四色的 on/container、surface 家族 surfaceDim/Bright/surfaceContainer Low→Highest、inverse 系列、outline/outlineVariant、scrim、surfaceTint）。此前 light 只定义 7 个角色、dark 缺 surfaceTint 等——未定义角色落到 M3 baseline 紫色，ElevatedCard 高度 tint 与错误色在部分界面泄露紫色
- **surfaceTint 对齐主色**：dark=品牌青 #7FE7D6、light=品牌 teal #00695C，卡片/阴影不再泛紫
- **dynamicColor 参数**：新增 Android 12+ 动态取色入口（`SpiceTheme(dynamicColor = true)`），默认关闭保留实验室仪器品牌视觉
- **窗口处理安全化**：状态/导航栏染色与图标明暗改用 ContextWrapper 逐层解包取 Activity，消除非 Activity 上下文强转崩溃风险；补 `isAppearanceLightNavigationBars`
- **卡片容器色规范**：16 处 `surfaceVariant.copy(alpha=0.35f)` 自混色替换为 `surfaceContainer` 角色直用（MD3 无影卡面，对比度由规范保证）

## 1.6.5 — minSdk 维持 24

- 需求确认为「最低 API 不大于 29」：`minSdk` 回退并维持 24（Android 7.0+），`targetSdk` 保持 29；117 项测试与 APK 构建通过
- 附注：若未来提升 `minSdk` 至 ≥27，AGP 会改为不压缩存储 dex（ART 直接 mmap），debug APK 将从 ~46 MB 增至 ~84 MB，属预期打包行为

## 1.6.3 — 蛋白页菜单条目 1:1 清理

对照 main `protein/+page.svelte` 菜单树逐项复核，移除 native 版自扩条目、功能入口移入页面内：

- 文件菜单：删除 main 无有的「导出蛋白序列 (FASTA)…」「导出性质报告…」两项，恢复 main 的「打开 ‖ RCSB ‖ 导出 mmcif/突变体 mmcif/spicep」结构
- 突变与设计菜单：删除 main 无有的「单点突变设计（加入购物车）」「清除突变购物车」两项（main 的购物车编辑在突变面板内，android 的 MutationCartPanel 已含单条移除/应用/清空），恢复 main 六项结构
- 上述功能不丢失：「单点突变设计…/导出 FASTA/性质报告」三个按钮移至左栏序列输入区下方常显
- 折叠/MD/批量菜单项不挂 busy 禁用条件：native 端为同步瞬时操作，与 main 的 Web 异步 busy 语义等价（相图扫描因原生为长任务已对齐 busy 启停）

## 1.6.2 — 文件菜单「导出」模态框与 .spiceg 工程文件

对齐 main `showExportModal` + `exportSequence` + `loadSpiceg` 的完整工程文件链路：

- 新增 `core/Spiceg.kt`：.spiceg 基因工程文件写出/读取，schema 与 main `SpiceGeneFile` 1:1（format/version/meta{plasmidName,hostPreference,createdAt}/sequence/features{name,start,end,type,color,forward}/selectedEnzymes/selectionNotes/notes/notebookPages，2 空格缩进输出）
- 「导出当前序列…」从单 GenBank 启动器改为导出模态框五项：SPICE 基因 (.spiceg) / GenBank (.gb) / FASTA (.fasta) / EMBL (.embl) / 保存工程文件（语义映射到项目库保存，native 项目模型差异已在代码注明）；文件扩展名与 main 一致（.spiceg/.gb/.fa/.embl）
- 「打开序列文件」支持 .spiceg（对齐 main handleGeneFile 分支）：还原序列/特征（forward→strand）并恢复所选酶组
- `SeqIO.parse` 增加 spiceg 路由兜底；移除 main 基因菜单中不存在的「导出可复现包 (ZIP)」条目（Repro 模块保留，仍可由项目中心/测试调用）
- 新增 `SpicegTest.kt` 7 项（schema 完备性/往返恢复/检测/拒绝非法 format/缺省字段容错/SeqIO 路由/2 空格缩进），总测试 117 项全通过

## 1.6.1 — 「分析与多视图 → 导入比对文件」动作落地

对齐 main `handleAlignmentFile` + `advancedTools.ts parseAlignmentFile` 的完整导入链路（此前为占位文案）：

- 新增 `core/AlignmentIO.kt`：六种比对格式解析器 1:1 移植（Clustal 块累计 / FASTA / NEXUS MATRIX 正则 / PHYLIP 10 字符名 / MSF `//` 后解析 / Stockholm 跳 `#` 行），头部自动检测与 main 逐分支一致
- `AnalysisPanels.kt`：MSA 网格渲染提取为共享组件 `MsaGrid`；IMPORT_ALIGN 面板改为 `ImportAlignView`——文件选择器 → 解析 → 过滤空序列 → `MultipleAlign.multipleAlign` → 着色网格 + 共识行 + 保守性条带 + 复制比对 FASTA
- 新增 `AlignmentIOTest.kt` 10 项（六格式检测/解析细节/标尺行跳过/NEXUS 无 matrix/导入喂 multipleAlign），总测试 110 项全通过

## 1.6.0 — 菜单逻辑全面对齐

对照 main 分支 `gene/+page.svelte` 与 `protein/+page.svelte` 的菜单树，逐组对齐条目、顺序、分组分隔、启用条件与动态标签。

### 基因页（文件 / 编辑 / 分子克隆工具 / 分析与多视图 / 窗口与设置）
- 菜单组名与条目顺序 1:1：文件菜单按「打开 / Ensembl / NCBI / UniProt / SPD / 集合 ‖ 保存 / 导出 ‖ Sanger 色谱」重排；编辑菜单按「撤销/重做 ‖ 拓扑/反向互补/清除选区 ‖ 特征/注释/搜索」重排，拓扑项为动态目标标签（切换拓扑：线性/环状）
- 分子克隆工具菜单按 main 的 6 组分隔展示（酶/密码子/引物/PCR/凝胶 → 设计舱/表达盒/载体/CRISPR → 计算器/LIMS → QC/NGS/重组/协同设计 → 寡核苷酸工具组 → 批量）
- 新增**协同设计舱**（`core/CodeSign.kt`）：Rust `pareto_codesign_evaluate` 的 1:1 移植——三宿主（E. coli/酵母/人）密码子权重表、最优密码子回译、CAI 几何平均、mRNA 5' ΔG（Nussinov 滑窗）、(ΔE, −CAI) 双目标非支配排序；候选清单解析与应用 DNA 变体
- 窗口与设置菜单：单链紧凑/双链模式切换（序列视图互补链逐列对齐渲染）、碱基配色方案 7 套菜单内直选（新增「GC 含量突出」）、标题对齐（自定义界面风格设置 / 重置分栏宽度）
- 菜单栏右侧新增**未保存/已保存 LED 指示**（applyRecord 置脏、保存项目清除）
- 分析与多视图、分子克隆工具全部条目标签与 main i18n 文案对齐

### 蛋白页（文件 / 建模与仿真 / 突变与设计 / 窗口与设置）
- 文件菜单：拆分「导出当前预测结构 (.mmcif)」「导出突变体结构 (.mmcif)」并挂禁用条件（无结构禁用）；新增 **SPICE 状态包 (.spicep) 导出/导入**（`core/Spicep.kt`，JSON 结构与 main 一致：meta/environment/coordinates/simulation_history）
- 建模与仿真：口袋监测改为**开/关切换**（对齐 main 的 toggle 语义）；相图扫描移至突变与设计组
- 突变与设计：新增「应用突变购物车到预测结构」（应用后重折叠）、相图扫描改为**异步可取消**（`scanCancellable` 逐点检查取消标志，结果与同步版完全一致）、「开启批量序列抗崩溃筛选」
- 窗口与设置：5 个面板标签对齐（结构折叠/动力学轨迹/环境相图/突变分析/批量设计筛选），就绪项加粗高亮
- 12 项新测试（总 100 项通过）：CAI 手算对照、最优密码子表、Pareto 支配判定、CDS 越界拒绝、可取消扫描等价性、spicep 往返

## 1.4.0 — 基因页分析与多视图对齐

- `core/RnaStructure.kt`：Nussinov DP + 回溯 → dot-bracket + 发夹识别 + 滑窗结构扫描；RNA 视图以 Canvas 绘制弧图
- `core/GenomicsTools.kt`：TFBS 五共识、RBS（Salis 模型）、σ70 启动子、信号肽三分区、跨膜 KD 滑窗、GC/AT skew、CpG 岛（Gardiner-Garden）、串联重复 SSR、GFF3/GTF/BED 解析、原核基因预测（双链三框）、CRISPR array 检测
- `core/CloneValidation.kt`：克隆一致性 99.5%/覆盖 95% 阈值、读链反向互补重比对、变异效应（错义/同义/无义/移码/stop-lost/基因间）
- `core/MultipleAlign.kt`：半全局 NW pairwise + 渐进式 MSA + 共识与逐列保守性（首见序计票）
- `AbiReader.kt` 全量重写：按 ABIF 规范 28 字节目录项布局读取 PBAS1/PCON1/PLOC1/DATA1-4（G/A/T/C 通道）；Sanger 视图四色峰图 + 一键克隆验证
- `AnalysisPanels.kt` 四视图重写、CRT 终端风格日志面板、`.scf` 引用改为仅 `.ab1`；22 项测试（总 62 项通过）

## 1.3.0 — 蛋白工作站对齐

- `core/ProteinBiophysics.kt`（~700 行）：折叠自由能估计、粗粒度 MD、相行为、口袋检测、突变 ΔΔE、批量分析，算法级 1:1 移植自 `lib/protein/biophysics.ts`
- 蛋白页六面板（fold/MD/phase/pocket/mut/batch）+ RCSB 结构拉取 + 24 项生物物理测试

## 1.5.0 — 反应计算器全套 + 批量筛选引擎

对照 `lib/genome/biophysics/calculators.ts` 与 `lib/genome/batch.ts`，补齐 8 个反应计算器与 BatchEngine 批处理。

### 新增能力
| 原版实现 | 原生实现 | 说明 |
|---|---|---|
| `calculators.ts` 8 个函数 | `core/Calculators.kt` | 稀释 C1V1=C2V2（含补水量）、连接插入量（可配置摩尔比，原 1:3 固定）、PCR master mix（Taq/Phusion/Q5 三档 × 孔数 × 体积 × 10% 移液余量）、DNA 浓度换算 ng/µL↔nM（ds/ss 分子量）、梯度 PCR 12 孔优化（Tm-5 法则 + 最优先列）、凝胶条带半对数回归（含 R²）、ELISA 4PL 标准曲线（log-logit 线性化 + 回算浓度）、酶动力学 Michaelis-Menten（Lineweaver-Burk） |
| `batch.ts` BatchEngine | `core/BatchEngine.kt` | 多 FASTA 解析（`parseFastas`）、批量酶切筛选（切点数与明细）、批量引物设计（区间收敛 + 每序列一对）、批量导出（FASTA/GenBank + 剪贴板复制） |

### UI 接入
- 克隆 → 分子克隆与 PCR 计算器：从 3 项扩展为 8 节全量（连接/稀释/master mix/浓度换算/梯度 PCR/凝胶/ELISA/酶动力学），FilterChip 切酶类型与换算方向
- 克隆 → 批量筛选与批处理（新面板）：多序列 FASTA 输入（留空用当前序列）、酶名列表、批量酶切/引物/导出三键执行

### 测试（总 88 项全部通过，新增 26 项）
稀释正反例、连接比 1:1/1:3/非法输入、Taq/Phusion/Q5 体系体积守恒与余量、ds/ss 分子量与 ng↔nM 往返、梯度最优先列与边缘 Poor、凝胶完美线性 R²=1 与不足标准带、ELISA 4PL 回算与点数不足、酶动力学 Vmax/Km 精确恢复、多 FASTA 解析（杂行忽略/空输入）、批量酶切计数（REBASE 主名/别名按位点断言）、批量引物区间收敛、批量导出 LOCUS/ORIGIN 结构。

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

