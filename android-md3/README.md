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

## 1.7.15 — 五修：工具手势/色带1:1/OVE箭头/切点指注（versionCode 49）

用户对 v1.7.14 反馈五项问题，对照 main 逐项修复：

1. **工具手势**（main CircularMapCanvas handlePointerDown/Move/Up + LinearMapCanvas 同名 verbatim 语义）：
   - 环状 select 失效修复：`bpAt` 去掉 0.55r–1.15r 距离钳制——main `getMouseBpOnCircle` 无任何距离限制，任意位置按角度换算 bp，特征外层/圆心均可按下拖选；
   - 线性 pan 失效修复：else 分支 `detectTapGestures`（挂起永不返回）后跟 `awaitEachGesture` 永不可达 → 重构为单一 `awaitEachGesture`；酶名标签命中优先（main handlePointerDown 先查 hitZones）；
   - 两图手势统一：select 按下即设 selection（拖动扩区间）/ pan 单指平移（线性 panBp = startPan + (−dx/(linW·zoom))·seqLen）/ rotate 单指旋转（main (dx/160)·π ≈ 1.125°/px）；
   - **双指=仅捏合缩放**：第二指落下取消一切单指拖动（修复"同时旋转+移动"的反人类操作，main pinchStart 语义）。
2. **色带颜色 1:1**：main `drawDirectedPiePiece` 第 9 参 0.7 是 tailThickness（尾弧厚度比）而非 alpha——v1.7.14 误当 alpha 画暗。现特征/引物带全色填充；线性箭头同吃 globalAlpha 0.85；浮动质粒特征卡行上特征色（main tooltip verbatim：名称 feat.color 粗体、类型徽章同色描边+13%底+同色字）。
3. **方向箭头改标尾部**（用户自纠）：按 tg-oss OVE 原版 `drawDirectedPiePiece` verbatim 重写几何——细尾弧（0.7 厚度）+全高箭头楔形；fwd 尖点在特征起点角指向逆时针 / rev 尖点在终点角指向顺时针（均背离色带体=标记在尾部）；箭头长恒 80/(2π)；active 白描边 0.5px。引物带同款（9 高/0.6/cyan α0.45 填充+#06b6d4 描边）。
4. **切点酶指注**：main `restrictionSites = COMMON_ENZYMES(47 项) ∪ selectedEnzymes` verbatim → 新增 `Enzymes.mapCommon`（AatII 在内）图谱常显；默认酶库无 color → var(--pix-accent) → 画布回退 **#ef4444 红**（此前用哈希色，错）；环状最外周红刻线+红松弛标签+引线，线性下方红刻线+红酶名（gly lane 12px）。默认勾选改 main `getInitialSelectedEnzymes` 8 项。
5. 测试 166/166（新增 EnzymeSetsTest：mapCommon 47 verbatim 含 AatII / common 8 / 47 项全可在酶库解析）。

## 1.7.14 — 图谱特征色带颜色一一对应 + OVE 有向箭头（环状+线性）

- **特征色表 verbatim**（main src/lib/genome/demo.ts:28 colorForType）：cds #ef6500、gene #684e27、promoter #31b440、terminator #f51600、rep_origin/origin #878787、misc_marker #8dceb1、protein_bind #2e2e2e、misc_binding #006fef、rbs #bdffcb、misc_feature #006fef、primer_bind #53d969、tag #e419da、signal_peptide #2fff8d、fallback #4cd6ff。demo 特征 color 全部由 type 派生（loadOveDemoPlasmid），图谱色带/特征表色块/浮动特征卡/minimap 同源同色；替换原自造 MapPalette 主题色板。
- **环状图谱 OVE 有向箭头**（main circularOve.ts:85 drawOvePiePiece verbatim 几何）：替换 2dp 方向圆点为真箭头——箭头角 = 80/(2π·r)（clamp 总角，≈12.7dp 长）、fwd 箭头在起点顺时针 / rev 箭头在终点逆时针（`forward: true = arrow at startAngle`）；主弧让位箭头长度，激活白描边仍画全弧。
- **线性图谱**：色带矩形+起端三角箭头已对齐（LinearMapCanvas.svelte:340），本轮统一色源后颜色与特征表一致。
- **parseHexColor 纯 Kotlin 化**（#RGB/#RRGGBB/#AARRGGBB），Robolectric/单测可跑。
- **测试**：FeatureColorTest 3 用例（verbatim 15 项色表/空串走 misc_feature/演示特征派生核对）；全套 163/163 通过。

## 1.7.13 — 用户反馈六修：语言/视图联动/齿轮归位/键盘遮挡/ELN 数字签署

- **多国语言设置**（main settings tabGeneral :511 + project.inlang 七语言）：设置页顶部「界面显示语言:」下拉（简体中文/English/日本語/한국어/Deutsch/Nederlands/Svenska），切换即时生效（Compose state 驱动），持久化 app_language；本轮覆盖导航/视图 tab/菜单组/设置页核心词条（渐进抽取，未收录词条回退中文）。
- **选区滚动跳转**（main DnaViewer.svelte:287 Gap 17 Scroll Alignment）：selectionStart > 1 变化时序列视图平滑滚动到选区所在行——点图谱特征/切点或特征表行，右侧序列立即定位（这是原版图谱+序列同屏的意义：联动跳转而非孤立并存）。线性化操作同步跳 Linear Map tab（main linearizeSequence :1734）。
- **齿轮归位**：删除自造的右下角浮动 FAB——main 唯一入口是「窗口与设置」菜单内「设置」项（menuWinSettings，菜单 toggle 语义，:3271）；浮窗定位改右上角（main right:10px top:60px）。
- **AI 对话框键盘遮挡**：抽屉内容列补 imePadding()，键盘弹出时输入框随输入法上移（manifest 本就 adjustResize + edge-to-edge）。
- **科研备注 ELN 大补**（main :3514-3648 + limsCollaboration.ts）：实验类型下拉 5 值 verbatim（分子克隆/PCR/细胞转化/跑胶验证/通用实验）；文献标签（逗号分隔，默认 SPICE, ELN, Auto_Sign）；🧬 插入 DNA 选区 / 🧪 插入翻译蛋白选区（main insertDnaBlock 格式：10bp/块 5 块/行 + ```dna 围栏 + 选区片段名）；**ELN 数字签署面板**（ORCID iD 输入+格式校验+解析 → 见证复核人/签署理由 → 21 CFR Part 11 防伪提示 → 确认签署并归档锁定页面：canonicalizeElnSignatureSnapshot 键序 JSON verbatim + SHA-256 lowercase hex 摘要 → 锁定后只读卡片显示 🔒/ORCID/签名摘要/签署时间）。
- **测试**：ElnSignatureTest 5 用例（键序/省略语义/trim/SHA-256 基准/长度）；全套 160/160 通过。

## 1.7.12 — 五大组件 1:1 复刻（§3.1/§3.4/§4.1/§5/§5.1/§5.2）

- **§3.1 地图工具栏完整版**（PlasmidMap.svelte:149）：头部横条 = 「Circular/Linear Plasmid Map」标题 + Select(选择)/Pan(平移)/Rotate(旋转,仅环状)/Zoom±/Reset(重置视图)/PNG；平移/旋转工具下单指拖动生效；PNG 经 GraphicsLayer 录层导出 Pictures 目录（Robolectric 软渲染自动降级直绘）。
- **§3.4 选区工具条**（SelectionToolkit.svelte:63）：深底 #0a0a0a 等宽字体横条 `start..end = N bp [GC%] | Tm=..°C` + 复制(复制序列与特征)/复制图像(高分辨率 PNG 导出)/RevComp/删除/取消；**选区属性浮卡**（DnaViewer.svelte:1401：物理区间/选区长度/GC/退火 Tm+记批注+复制，4-50nt NEB Tm）；特征卡新增 GC% 与 CDS 估算分子量（main getFeatureStats：len/3×110Da）。
- **§4.1 序列工具轨+minimap**（SequenceToolbar.svelte:142 + SequenceMinimap.svelte:122）：44dp 竖轨 8 钮（浏览/编辑/注释[accent]/切点/特征/PCR 退火杂交/翻译/记批注），激活态 #007a8a；22px minimap（特征彩条 y=3..h-3 + 选区蓝 10,132,255,0.4 + 视口白框 + 点击按 pct 跳转）；注释模式拖选（colX 反算）松手直开特征编辑器；HybridizationDialog（退火杂交配置）完整移植。
- **§5 特征表格完整版**（+page.svelte:3469-3520）：「注释特征」+ 计数 + 添加特征注释…/自动注释特征按钮；搜索框 + 类型过滤（全部类型+大写）+ 5 键排序（起始/名称/大小/类型/颜色）；表头网格 + 行（色块/名称/起始/大小/类型/✎/×）+ 空态。
- **§5.1 特征编辑器模态**（+page.svelte:3700-3760）：名称/起点/终点/类型下拉(12 内置+自定义「+」)/颜色(8 色预设)/正向 checkbox；空白名或 start>end 拒绝（toastFeatureNameRangeDesc 原文）。
- **§5.2 自动注释**（advancedTools.ts:475-660 + runAutoAnnotate :2627）：COMMON_FEATURES_DB 14 条 verbatim（T7/SP6/lac promoter、T7 terminator、pMB1 ori、AmpR/KanR/CmR、His/FLAG/HA/Myc/GST-tag、MCS）；annotateFeatures exact（正向+revcomp 子串）/kmer（seedLen 22/minMatch 24/mergeGap 6，tol=k/10 容错扩展+同名区间合并）；inferFeatureType 7 正则短路；runOnRecord 幂等（跳过 name|start|end）；Feature 新增 color 字段（显式 hex 优先，否则按 type 派生）。
- **测试**：AutoAnnotateTest 8 用例（exact 正/反、kmer 容错、幂等、inferFeatureType、库 verbatim、颜色贯通）；全套 155/155 通过。

## 1.7.11 — 用户反馈四修：AI 会话保持 + 竖屏上下分屏 + 10 碱基分块 + 特征卡 z-order

1. **AI 面板收回不再丢历史**：会话状态（entries/input/tab）上提为全局 `AiChatStore`（对齐 main `aiState.svelte.ts` 全局 store），抽屉关闭 composable 销毁后重开完整恢复对话。
2. **竖屏上下分屏**：窄幅（maxWidth<620dp）时图谱/左区视图在上（46%）、序列视图在下（54%）全宽显示，替代不可读的左右挤压；横屏/平板维持 main 桌面左右分屏。
3. **序列视图 10 碱基分块**（main gene-editor.md §4.1"10-base blocks"）：每 10 列插入块间距，碱基/互补/翻译/切痕/酶名标签同用 colX 列映射；窄幅自动 30 bp/行、宽幅 60 bp/行。
4. **Plasmid Features 特征卡 + 选区卡 z-order 修复**：两卡声明移到 `when(view)` 图谱之后，不再被图谱不透明背景（#0B0F17）盖住——此 bug 自 1.7.8 分屏改版起存在。
5. **图谱手势滚动手势冲突修复**：非选区模式下图谱只消费双指手势（缩放/平移/旋转），单指穿透给外层 verticalScroll——修复窄幅布局下概要行/酶选择行滚不到（"下方文字消失"）；main 画布单指本就无动作，语义一致。
6. **linearize 对齐 main `PlasmidMap.svelte:112`**：切割后选区重置为 1..1（原为清空）；核实 main 不平移特征坐标、不检查拓扑。
7. 测试 147/147（新增 EnzymesDigestDebugTest 任意序列切点验证、RenderSmokeTest Robolectric 真实渲染六 tab 截图）。

## 1.7.10 — docs 逐项核对：指注联动 1:1 + 克隆归属修正 + ONNX 蛋白推理 + AI 系统提示词

按 main docs/gene-editor.md 逐项核对九项缺陷（对齐源：SPICE_main/docs/ 与项目内文档）：

1. **tab 命名用 main 原文**：环状图谱/线性图谱/序列视图/特征列表/质粒属性/科研备注（project.inlang/messages/zh.json）。
2. **序列视图闪退修复**：TextMeasurer 测量由 draw 阶段（每帧 ~150 次/行）提升为 remember 行级缓存（RowCache，对齐 main rowRender 预计算），消除主线程过载；Robolectric 六 tab 渲染测试固化回归（SequenceTabCrashTest）。
3. **AI Copilot 系统提示词**：设置页新增多行编辑框（默认预填充 main aiState.svelte.ts:118 原文 21 行，verbatim 内嵌 core/AiPrompts.kt），持久化 ai_system_prompt，发送时注入 system 消息。
4. **入口去重**：标题栏 AI+设置为唯一入口，移除 ProteinScreen 浮动 AI FAB、底栏"设置"与"克隆"项（main 中 Cloning & Design 属基因页菜单，无独立底栏页）。
5. **线性图切割气泡**：酶名可点，弹"Restriction Site: 酶名 (pos bp)"气泡，剪切后 rotateTo 使切点为第 1 位 + 清选区 + 转线性（对齐 main PlasmidMap.svelte linearizePlasmid）。
6. **图谱↔序列联动**：基因页改分屏布局，六 tab 下右侧序列视图常驻，图谱与序列共享 selection（对齐 main gene-editor.md §1）。
7. **环状图谱 Plasmid Features 常驻卡**：左上角浮动特征卡，无关闭按钮，点击特征选中区间并展开 Range/Size/Strand（对齐 main PlasmidMap.svelte:241）。
8. **ONNX 蛋白推理集成**：core/SpiceOnnx.kt 全头移植 src-tauri/src/onnx.rs（tokens/env/mask 三输入、distogram 47 边几何 + 接触 bin softmax），有 spice_infer_fixed.onnx 时真推理，无模型回退启发式；设置页模型状态真实检测。
9. 测试基线 140 → 145（SpiceOnnxPreprocessTest 4 + SequenceTabCrashTest 1），全部通过。

## 1.7.9 — 基因页左区划区 1:1 复刻 + 图谱五层级与缩放

对齐 main gene/+page.svelte 的 ove-tabs 左区划区与 CircularMapCanvas/LinearMapCanvas 完整层模型：

- **左区 6 tab 划区**：新增「特征列表 / 属性 / 笔记」三个视图，对齐 main activeTab（circular/linear/sequence/features/properties/notes）；原特征 inline 卡片并入特征列表视图
- **特征列表**（main :3468）：色块 + 名称 + 起点 + 长度(|end−start|+1) + 类型 + 行内 ✎ 编辑（预填对话框）+ × 删除；行点击选中该特征区间
- **属性页**（main :3350）：GenBank 全字段（创建/修改日期、拓扑类型 4 项下拉、序列类别 4 项下拉、宿主、描述、Accession + 在 NCBI 打开、Code Number、作者、备注、参考文献）+ 编辑 DNA 区（质粒名 / 序列 Textarea / 长度·拓扑·GC 摘要，GC 越界警示）；默认值逐项复刻 main 演示默认（Oct 25, 2005 / Natural DNA / PLN / Pichia pastoris / DQ115396）
- **ELN 电子实验记录本**（main :3514）：页管理器（新建/切换/删除，页字段 title/category/date/author/locked，SharedPreferences 持久化）+ Markdown 页编辑 + [保存页] + 选区注释（创建/点击跳转选区/删除）；spiceg 导出写入 notes/notebookPages/selectionNotes 实数据
- **序列视图**：碱基配色 7 主题下拉（对齐 main rowRender.ts:691 精确 hex，默认 monochrome）+ 翻译行（对齐 rowRender.ts:843：三联体圆角框 rgba(219,39,119,.2)/.5 + AA 字母 #aaaaaa，默认开）+ 单链模式持久化
- **环状图谱 9 层**（main CircularMapCanvas）：DNA 碱基块（放大后彩色块 + 字母）/ 骨架双圈 / 选区高亮（发光+实线+径向端刻线）/ 刻度数字 / 特征堆叠分层 + 沿弧内部标签（容量省略 + 下半弧翻转 + 深浅反色）/ 引物分层 / 轴上切点刻痕 / 淡导轨圈 / 中心文字（放大时隐藏）；外圈松弛酶名指注保留
- **线性图谱 7 层**（main LinearMapCanvas）：选区绿块 / 主轴脊线 / 刻度数字 / 放大显碱基 / 特征区间堆叠 + 箭头（选区注释以 note 并入）/ 特征标签防碰撞 + 引线 / 切点竖线 + 分层酶名
- **缩放交互**：双指缩放/平移/旋转（环状）+ 横向平移缩放（线性）+ 右上角工具条（＋/－/⟲ 重置）；缩放几何逐项复刻 OVE 公式（initialRadius = w/sin(visibleAngle/2)/2、中心下移 yOffset、rangeToShow 可见窗、tickSpacing 自适应）
- **引物轨道**：SeqRecord 新增 primers 字段 + pj5 Example Primer 1（281..301 fwd）；图谱始终绘制（main showPrimersOnMap 仅持久化开关，1:1 复刻死状态）
- **视图配置持久化**：view_color_theme / view_translations / view_orfs / view_primers_map / view_single_strand / view_features / view_cutsites（SharedPreferences，对齐 main localStorage config）
- **已知差异**：main DnaViewer 序列行的特征/部件轨道与 parts 数据渲染路径未核实，本轮未实现（如实记录）；翻译行的反向链（showReverseSequence）main 默认关闭，同默认

## 1.7.8 — 默认序列 pj5_00001 + 顶栏对齐 + 切点酶名指注

- **默认演示记录换成 pj5_00001**（对齐 main 基因页默认加载的 OVE demo：`exampleSequenceData.ts` / `demo.ts`）
  - 5299 bp 环状，22 个 GenBank 特征（araC、pBAD promoter、GFPuv、CmR、pSC101**、跨原点特征 pS8c-vector_backbone 等），坐标 0-based → 1-based 转换
  - 图谱特征注释面板与限制性内切酶位点（showFeatures/showCutsites）默认打开（对齐 main 默认 $state(true)）
  - 7 套图谱配色主题补 `terminator` 类型键（main colorForType 红系），featureColor 扩展 rbs/protein_bind/misc_* 类型映射
- **顶栏对齐 main +layout.svelte top-status**：移除无参照意义的 record.name 副标题与 "${length} bp" 文本，右侧改为 AI Co-Pilot 触发按钮（打开时高亮，对齐 ai-toggle-btn/ai-active）+ 设置按钮；AI 状态提升为全局（原页内 FAB 移除）
- **环状图谱切点酶名指注**（1:1 移植 main CircularMapCanvas.relaxedLabels + geometry.ts relaxLabelAngles）
  - 切点改为轴上径向刻痕（对齐 main fillRect(-0.5,-6,1,12)）
  - 酶名标签置于外圈轨道，三段引线（轴上点→外扩点→锚点）+ 锚点圆点，四象限槽位避让，槽位用尽合并为 "+N," 前缀，酶名按 10 色环哈希着色
- **线性图谱切点酶名指注**（对齐 main LinearMapCanvas）：竖直刻痕穿过主轴 + 酶名在下方按 x 区间碰撞分层错开（calculateLabelBoxes 语义）
- 新增单测 demoPlasmidIsPj5AlignedWithMain（139/139 通过）

## 1.7.7 — 全面对比度审计修复（含浅色主题盲区）

- **审计范围**：全 app 硬编码色 + 半透明文字 + Canvas drawText 逐处复核对比度（WCAG ≥4.5:1 文字 / ≥3:1 图形）
- **新发现 3 类违规并修复**：
  1. 环状/线性图谱 Canvas 的 drawText（序列名/bp/GC/刻度/特征标签）未指定颜色 → Compose 默认近黑画在深底上；补显式亮色（#E2E8F0/#A9BACD）
  2. 科学图谱画布（环状/线性图谱、序列视图）此前跟随主题背景，浅色主题下亮色调色板（碱基色/酶色/特征色）对比度崩至 ~1.5:1；改为固定深底 #0B0F17——对齐 main 深底亮色板语义（main 源码无任何浅色覆盖，调色板只为深底设计）
  3. MD 能量折线（硬编码亮青 #7FE7D6 + 半透明灰轴线）浅色下低对比；改主题感知色（primary/outline 角色）
- 合规确认：CRT 终端（荧光绿 #39FF14 对 #04160A ≈13:1）、Sanger 通道色、相图热图、DemoScreen、图谱 7 套特征配色均为深底画布装饰色，双主题下合规

## 1.7.6 — 主题统一 + AMOLED 纯黑 + 欢迎页品牌化

- **主题统一**：欢迎页与演示页此前未铺主题背景（露出系统窗口灰黑底色，与 MD3 色板不一致）；现全部页面统一绘制 `colorScheme.background`，系统级 `windowBackground`/状态栏/导航栏同步压为纯黑，冷启动不再闪灰
- **AMOLED 纯黑主题**：新增 `SpiceAmoled` 色板（surface 全家族纯黑 #000000，OLED 像素不发光：省电、防烧屏，主色/前景不变）；设置 → 外观新增「AMOLED 纯黑」开关，默认开启，持久化 `theme_amoled`
- **欢迎页品牌化**：标题「SPICE」→「生信工具箱」（对齐 1.7.3 应用改名与桌面图标），副标题保留 SPICE 工程名；顶栏标题同步更新；版本标签随版本号滚动（此前陈旧在 1.2.0）

## 1.7.5 — 全局返回适配 + AI 侧拉抽屉 + 可拖拽浮动窗口体系

- **全局返回手势适配**：此前任何页面按返回直接退出应用；现按层级回退——打开的浮动窗口/侧拉栏 → 关闭，演示模式 → 退出演示，非基因页 → 回基因页，基因页 → 才允许退出（对齐安卓返回规范）
- **AI Co-Pilot 改右侧侧拉抽屉**（此前为底部 ModalBottomSheet，限高 320dp 不便滚动查看对话）：ModalDrawerSheet 满高抽屉，聊天列表占满可用高度可流畅上下滚动，右滑/返回手势关闭
- **可拖拽浮动窗口体系**（对齐 main `floating-panel.pix-panel` + `use:drag={'.panel-header'}` + `[X]` 语义）：
  - 新增 `FloatingPanel` 通用组件：仅标题栏可拖动整窗、`[X]` 关闭、悬浮于页面之上带阴影描边
  - 分析与多视图 10 面板、窗口与设置 5 面板、分子克隆工具 21 面板全部由底部弹层改为浮动窗口形态
  - 新增环状图谱浮动设置面板（对齐 main gene 页 menuWinSettings 浮窗：拓扑 环状/线性 切换、显示特征、显示切点勾选），图谱侧 ⚙ FAB 呼出

## 1.7.4 — 切点 OVE 式渲染对齐 + Sanger 峰图配色修复

- **序列视图切点渲染 1:1 对齐 main rowRender.ts**（此前仅红色 "|" 字符标记）：酶名标签（10 色环按酶名哈希着色、防碰撞分层堆叠、连接线）+ 上/下链各一道竖直切痕 + 两切痕之间的水平连接线（粘性末端方向与长度可视）；酶色 hash 算法与调色板逐字节对齐 main getEnzymeColor
- **Sanger 峰图配色对齐 main SangerChromatogram**：G 通道由近黑（黑底不可读，违规）改为橙色 #F39C12，A/T/C 对齐 #2ECC71/#E74C3C/#3498DB，峰图画布加 #030303 深底
- 主题审计：全 UI 扫描硬编码低亮度色，其余命中均为 MD3 规范深色容器角色（背景角色配亮色 on-*，合规）与 CRT 终端风格面板（main 同款），无其他黑底黑字

## 1.7.3 — 应用品牌化：生信工具箱

- 应用显示名称改为「生信工具箱」
- 启动图标替换为用户提供的生物危害警示标识（亮黄三角）：全密度 mipmap 位图 + Android 8+ 自适应图标（白色背景层 + 前景内容 62% 安全区）

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

