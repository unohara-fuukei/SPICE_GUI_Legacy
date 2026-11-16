# 实测巡检日志（native 分支）

> 每轮定时巡检按入口清单驱动：有设备则装 debug APK 逐项进入并抓 logcat；无设备/模拟器不可用时降级为全量单测 + Compose 入口静态走查。结论只落本仓库，不生成云盘/飞书文档。

## 2026-11-16 · v1.7.58 巡检轮

### 环境
- 构建主机无 `/dev/kvm`、CPU 无 vmx/svm；模拟器为 TCG 软件模拟（`-accel off -gpu swiftangle`），单核，首帧 JIT 组合慢，切页易触发输入派发 ANR（非逻辑死锁）。
- 软件模拟画面 readback 为黑屏（`screencap` 全黑），但 `uiautomator dump` 可读控件层级；像素级目视核对在此环境不可用，视觉回归依赖 Robolectric NATIVE 栅格化截图。
- 发布包仅打包 `arm64-v8a / armeabi-v7a`；x86_64 模拟器验证需 `-PtestAbis=x86_64` 临时追加。

### 本轮修复（均已补回归测试，全量单测 783 绿 / 0 failures / 0 errors）

1. **#111 蛋白「质谱/免疫」advanced 子 tab 闪退**（`AnalysisPanels.kt` `ProteinView`）
   - 根因：二硫键配对概率 `DisulfideBond.pairingProbability` 为 `Int`，渲染却用 `"%.2f".format(...)` 浮点格式化，触发 `java.util.IllegalFormatConversionException: f != java.lang.Integer`，组合期崩溃。
   - 对照 main `proteinAnalysis.ts` `DisulfideBondPrediction.score`（number）→ `bond.score.toFixed(2)`；修复为先 `.toDouble()` 再 `%.2f`。`score` 与 `pairingProbability` 在 main 中同为 `finalScore`，等价。
   - 回归测试：`ProteinAdvancedTabTest` 直接渲染 `AnalysisPanelSheet(PROTEIN)` 并点击「质谱/免疫」chip，捕获组合期异常。
   - 已排查同文件其余 `%.2f/%.1f/%.1e/%.3f` 格式化点：`identity/coverage/identityPercent/efficiency/skew/avg/chargeAtPh7/pI` 均为 `Double`，无同类隐患。

2. **#112 环状图谱跨编号 1 选取**（`Maps.kt` `CircularMap`）
   - 独立双值状态 `selRawStart/selRawEnd` 允许 start>end 表示 wrap；对外回传归一化 `IntRange`。高亮弧 `sweep = ((eAng−sAng)%360+360)%360` 跨零点顺时针环绕，端径向刻线用原始双值；对齐 main `CircularMapCanvas.svelte:439-461 / 533-568`。

3. **#113 选区属性浮卡定位**（`GeneScreen.kt` `SequenceView`）
   - 根层改 overlay `Box`，`SelectionPropertiesCard` 作为 Box 子项 `align(BottomCenter)` 叠加，不再占列高；对齐 main `DnaViewer.svelte:1395` absolute 底部居中 `z-index 1000`。
   - 已知次要差异：main 虚拟键盘弹出时浮台上抬至 145px；native 键盘位于内容列内，浮卡高度未随键盘联动（待后续适配）。

4. **#114 序列视图标注行高/分层**（`GeneScreen.kt` `SequenceRowCanvas`）
   - 轨道高 18 / 体高 11 / 标签高 12 由裸 px 改为按 `LocalDensity` 折算局部 px，与 sp 字号同比例放大；高度缓存、命中区间、标签 y、轨道 itemY、形状高度、标签底色框高全部改用局部值。常量口径对齐 main `rowRender.ts:69-74`。

### 入口覆盖状态
- 基因页：环状/线性图谱、序列视图、特征列表、属性、备注 —— 模拟器可进入，无 FATAL；序列行 Canvas 文本不在 uiautomator 可读范围。
- 蛋白页：主视图（翻译、理化性质、环境参数、SPD 面板）可进入；advanced（质谱/免疫）tab 本轮复现并修复。其余蛋白子 tab（物化属性/折叠结构/功能域/双向追溯）经 JVM/单测与静态走查覆盖，未在模拟器逐项目视。
- RNA / Sanger / 基因组学 / 合成生物 / BLAST / 比对 / GC 窗口 / 各工具页 / 项目集合 / 设置页：本轮未逐项进入，留待后续轮次在可加速环境或真机覆盖。

### 待办
- 高密度真机目视验收：#112 环绕弧、#113 浮卡不挤序列区、#114 标签可读（Robolectric density=1 下 dp 折算与裸 px 数值相同，截图无差异属预期）。
- #113 浮卡随虚拟键盘上抬联动。
- 欢迎页版本标签仍硬编码 `1.7.10 · native`（未接线项，非本轮改动）。

## 2026-11-16 · 第二轮（无设备降级走查，未发版）

### 环境
- `adb devices` 无设备；`/tmp/android-avd` 下 AVD 已丢失，主机仍无 `/dev/kvm`、CPU 无 vmx/svm，无法拉起加速模拟器。降级为全量单测 + Robolectric NATIVE 栅格化 + 静态走查。

### 本轮覆盖（新增 3 个走查测试，全量 787 绿 / 0 failures / 0 errors）
- **分析面板 9 个**（`PanelRenderWalkTest`，经 `AnalysisPanelSheet` 公共入口栅格化）：RNA、Sanger、Validator、Genomics、Synbio、Blast、Alignment、GC、ImportAlign 初始态均渲染通过，无组合期 NPE/越界。
- **顶层页**（`TopLevelWalkTest`）：`ProjectScreen`（空项目态）、`SettingsScreen` 渲染通过。
- **工具中枢**（`ToolsWalkTest`，直接调用各子屏 Composable，避开网格导航 i18n 标题匹配）：网格页 + Gel/Qpcr/Blot/Plate/Tree/Viewer3D/Photo/Report/File/AI/Figure/Annotate/Colony 共 13 子屏初始态渲染通过。

### 静态兜底核对
- 除法分母：`CloneValidation`（size==0 兜底）、`CodeSign`（count==0）、`GenomicsTools.skew`（denom>0）、`ProteinBiophysics`（count>0 / epitope len≥6）、`CloningExt`（len≥14 才除）、`RnaStructure.nussinovFold`（n<4 早返回，DP/回溯有 `idx in 0 until n` 边界判断）均无除零/越界。

### 仍未覆盖（留待后续）
- 各面板/工具的**交互后路径**：BLAST 点运行、MSA 载入、Sanger 载入 query、文件导入、图像识别/标注/计数等触发 core 算法与状态变更的分支，本轮仅验证初始态。
- 蛋白其余 4 个子 tab 的交互态、各克隆面板、设置页各子 tab 切换、AI 对话链。
- main 最新提交 `6306ef1`（基因页序列栏 46vh、竖屏上下堆叠、44px 触控热区）竖屏适配差异，尚未在 native 侧逐项核对。

### 发版决策
- 本轮无确认修复的 Bug，未改用户可见行为（仅新增测试与本文档），按闭环规则不 bump 版本、不打 release；测试加固随下一次修复版本一并打包。

## 2026-11-16 · 第三轮（无设备降级走查，未发版）

### 环境
- `adb devices` 仍无设备；无 `/dev/kvm`；`/tmp/android-avd` AVD 列表为空。继续降级全量单测 + Robolectric NATIVE 栅格化 + 静态走查。main 顶部仍为 `6306ef1`，无新增提交。

### 本轮覆盖（新增 1 个走查测试，全量 788 绿 / 0 failures / 0 errors）
- **克隆设计舱 26 个面板**（`CloningWalkTest`，遍历 `CloningPanel.values()` 经公共入口 `CloningPanelSheet` 逐一栅格化）：DESIGN/ENZYMES/CODON/PRIMERS/PCR/GEL/SIMULATOR/CASSETTE/VECTOR/CRISPR/CALC/LIMS/QC/NGS/EXTENSION/CODESIGN/ANNEAL/SILENT/REVTRANS/PRIMERMOD/ENDMOD/FUSION/GCWINDOW/SLIPPAGE/BATCH/CODESIGN 初始态全部渲染通过，无组合期 NPE/越界。

### main 竖屏适配提交核对（`313d649` + `6306ef1`）
- **窄屏上下堆叠**：基因页 `narrowScreen = maxWidth < 620.dp` 时左/右栏改 `Column` 权重堆叠（0.46/0.54），对齐 main `@media max-width:900px` 分栏转 column；蛋白页 native 本就是单栏 `Column` 竖排 + 整页滚动，等价满足窄屏堆叠。
- **序列栏 46vh 保底**：main `6306ef1` 修回右栏 `min-height:46vh !important` 并隐藏竖屏分隔条；native 堆叠分支用 `weight(0.54f)` + 矮屏固定 560dp，未按 vh 设序列栏保底高度，分隔条在 Compose 布局中本无独立 resizer。属布局细节差异，非崩溃，留待真机竖屏目视复核。
- **44px 触控热区**：main 要求图标按钮 min 44px；native 工具栏按钮多为 48dp，仍有 32/36/40dp 偏小项（`heightIn` 统计：32dp×9、36dp×13、40dp×15）。属可访问性/热区待办，非崩溃，未在本轮改动。

### 仍未覆盖（留待后续）
- 各面板/工具/克隆舱的**交互后路径**：点运行、载入 MSA/query/文件、图像识别计数、CRISPR/密码子/引物计算触发的 core 算法分支，本轮仅验证初始态组合渲染。
- 蛋白其余 4 个子 tab 交互态、AI 对话链、设置页各 Section 的写入与模型下载分支。
- 序列栏 vh 保底、偏小热区按钮的真机目视与可达性复核。

### 发版决策
- 本轮无确认修复的 Bug，未改用户可见行为（仅新增测试与本文档），不 bump 版本、不打 release；测试加固随下一次修复版本一并打包。

## 2026-11-16 · 第四轮（无设备降级走查，未发版）

### 环境
- `adb devices` 仍无设备；无 `/dev/kvm`；AVD 列表为空。继续降级全量单测 + Robolectric NATIVE 栅格化 + 静态走查。main 顶部仍 `6306ef1`，无新增提交。

### 本轮覆盖（新增 1 个走查测试，全量 789 绿 / 0 failures / 0 errors）
- **蛋白翻译与三维视图五子 tab**（`ProteinSubtabWalkTest`，经 `AnalysisPanelSheet(PROTEIN)` 逐一点击切换）：物化属性 / 折叠结构 / 功能域 / 双向追溯 / 质谱-免疫，切换栅格化全部通过。质谱-免疫 tab 崩溃已由 `ProteinAdvancedTabTest` 回归（#111）。
- **core 算法覆盖复核**：本地 BLAST `runLocalBlast`、Newick 解析/序列化（`EvolutionTest.kt`、`TreeToolTest.kt`）已有单测；蛋白性质、CRISPR、密码子、酶切、NGS QC、PCR、CodeSign 等均有 parity 测试在位。

### 静态兜底复核
- 扫描未兜底强解包与裸索引：蛋白 3D 载入空原子列表 `p.atoms.isEmpty()` 已分支兜底，非空才取 `first()/last()`；孔板网格行由构造侧保证非空；其余 `[0]`/`[1]` 取值均在定长拆分（PDB 坐标、引物结果列、修饰碱基）路径内。
- 边缘待核（非组合期崩溃，无设备难复现）：SPICEP `referenceCoords` `chunked(3)` 末块若不足 3 坐标可能越界（写入侧应保证 3 倍数）；文件导入/图像识别/批量结果等交互后分支仍未在栅格化覆盖。

### 仍未覆盖（留待后续）
- 蛋白页菜单栏下拉（文件/建模与仿真/突变与设计/窗口）展开态、文件选择器回调、ONNX 模型下载/推理分支。
- 各面板点运行后的 core 算法状态变更路径、AI 对话链、设置页模型下载与写入分支。
- 序列栏 vh 保底、偏小热区按钮的真机目视与可达性复核（沿第三轮待办）。

### 发版决策
- 本轮无确认修复的 Bug，未改用户可见行为（仅新增测试与本文档），不 bump 版本、不打 release；测试加固随下一次修复版本一并打包。

## 2026-11-16 · 第五轮（无设备降级走查，未发版）

### 环境
- `adb devices` 仍无设备；无 `/dev/kvm`；AVD 列表为空。继续降级全量单测 + Robolectric NATIVE 栅格化 + 静态走查。main 顶部仍 `6306ef1`，无新增提交。

### 本轮覆盖（新增 1 个走查测试，全量 790 绿 / 0 failures / 0 errors）
- **基因页六内视图 tab**（`GeneInnerTabWalkTest`，经公共入口 `GeneScreen` 逐一点击切换）：环状图谱 / 线性图谱 / 序列视图 / 特征列表 / 质粒属性 / 科研备注，切换栅格化全部通过，无 pref 读取、空特征/备注列表取值越界、Canvas 组合期异常。

### 形态差异记录（非崩溃，待页面内挂载态/真机复核）
- **项目集合工作空间**：main `CollectionsBatchPanel.svelte` 是 `position:absolute` 可拖拽浮动窗（width 565px、top 120px、right 310px、z-index 60，`use:drag`）；native 为 `GeneScreen` 内 `if (showCollections)` 条件内嵌面板，根 Column 仅 `fillMaxWidth + verticalScroll`，未挂浮动窗容器。
  - 独立 Robolectric 栅格化该内嵌面板时，测试容器给不出有界父级高度，`verticalScroll` 命中「infinity maximum height constraints」校验抛 `IllegalStateException`；该面板在真实页面（`GeneScreen` 根 `fillMaxSize` Column 有界）内挂载时约束合法，故判定为**测试夹具约束限制，非产品运行期崩溃**。未为迁就夹具改动生产布局；该面板的空集合态、搜索/标签过滤、批量结果分支留待页面内挂载态或真机验证。
  - 待核：内嵌形态相对 main 浮动窗的层级/拖拽/遮挡行为差异；嵌套滚动（页面根滚动 + 面板滚动）在窄屏的 UX。

### 仍未覆盖（留待后续）
- 蛋白页菜单栏下拉（文件/建模与仿真/突变与设计/窗口）展开态、文件选择器回调、ONNX 模型下载/推理分支。
- 项目集合面板页面内挂载态、各面板点运行后的 core 算法状态变更路径、AI 对话链、设置页模型下载与写入分支。
- 序列栏 vh 保底、偏小热区按钮的真机目视与可达性复核（沿第三轮待办）。

### 发版决策
- 本轮无确认修复的 Bug，未改用户可见行为（仅新增测试与本文档），不 bump 版本、不打 release；测试加固随下一次修复版本一并打包。

## 2026-11-16 · 第六轮（无设备降级走查，未发版）

### 环境
- `adb devices` 仍无设备；无 `/dev/kvm`；AVD 列表为空。继续降级全量单测 + Robolectric NATIVE 栅格化 + 静态走查。`git fetch origin` 后 main 顶部仍 `6306ef1`，无新增提交。

### 本轮覆盖（新增 1 个走查测试，全量 794 绿 / 0 failures / 0 errors）
- **四个顶层主屏初始态**（`TopLevelScreenWalkTest`）：克隆舱主屏 `CloningScreen(demoPlasmid)`、独立蛋白页 `ProteinScreen()`（内置默认序列）、欢迎页 `WelcomeScreen`、演示页 `DemoScreen(demoPlasmid)`，栅格化全部通过；remember 计算、core 取用、Canvas 组合期无异常，回调置空。

### 静态兜底复核
- 蛋白页 `ProteinScreen.kt` 强解包复核：`bifurcation!!` 在 `solvePhaseBifurcation` 成功赋值后同表达式读取（失败抛异常被 catch 置 null）；`fold/md/scan/pockets/spdFoldDetail/scanSummary` 等 `!!` 均位于异步结果赋值后的交互分支，初始态为 null 不进入；`p.atoms.first()/last()` 在 `p.atoms.isEmpty()` 判空之后才取用。
- 边缘待核（文件导入路径，非初始态崩溃，无样本难复现）：SPICEP `referenceCoords` `chunked(3)` 末块若坐标总数非 3 的倍数，`c[2]` 越界；写入侧 `flat(foldCoords)` 由折叠输出 FloatArray 自保证 3N，仅外部损坏/手工改写的 `.spicep` 可能触发，沿第三轮记录待真机/样本复核。

### 仍未覆盖（留待后续）
- 蛋白页菜单栏下拉展开态、文件选择器回调、ONNX 模型下载/推理分支、SPICEP 导入路径。
- 各 Dialog/Sheet（SpdLibrarySheet、AiCopilotSheet、SequenceSearchDialog、AddFeatureDialog、SeqImportDialog、FigureCheck/ImportDialog、SpdInspectorDialog）独立栅格化；项目集合面板页面内挂载态；点运行后的算法状态变更路径、AI 对话链、设置页模型下载与写入分支。
- 序列栏 vh 保底、偏小热区按钮的真机目视与可达性复核（沿第三轮待办）。

### 发版决策
- 本轮无确认修复的 Bug，未改用户可见行为（仅新增测试与本文档），不 bump 版本、不打 release；测试加固随下一次修复版本一并打包。

## 2026-11-16 · 第七轮（无设备降级走查，未发版）

### 环境
- `adb devices` 仍无设备；无 `/dev/kvm`；AVD 列表为空。继续降级全量单测 + Robolectric NATIVE 栅格化 + 静态走查。main 顶部仍 `6306ef1`，无新增提交。

### 本轮覆盖（新增 1 个走查测试，全量 795 绿 / 0 failures / 0 errors）
- **投稿检查对话框**（`DialogWalkTest.figureCheckDialog_emptyCounts`）：`FigureCheckDialog(open=true, issues=空, counts=null, busy=false)` 初始态栅格化通过；空问题列表、counts null 兜底分支渲染正常。

### 夹具限制记录（非产品崩溃）
- 四个含 `OutlinedTextField` 的对话框——`SeqImportDialog`、`AddFeatureDialog`、`SequenceSearchDialog`、`FigureImportDialog`——独立 `setContent` 到 Robolectric 时，光标闪烁/输入框动画在独立 Dialog 窗口内使 Compose idling 永不收敛，60s 后抛 `AppNotIdleException`（26 万次尝试）。对照：同批无输入框的 `FigureCheckDialog` 通过；静态扫描这四个 dialog 无组合期写回状态、无无限重组循环。判定为**测试夹具对 Dialog 内输入框动画的 idling 兼容限制，非运行期崩溃**，未纳入独立栅格化基线，留待页面内挂载态或真机验证。

### 静态兜底复核
- `AddFeatureDialog.trySave()`：起点/终点 `toIntOrNull()` 判空，`name 空 || s==null || e==null || s>e` 拦截并置 `err`；写入区间 `s..minOf(e, record.length)` 兜底不越界；`color` 可空与 `onAdd` 签名一致。
- `FigureCheckDialog`：`busy` / `counts==null||total==0` / 有问题三态分支判空到位；`issues.forEach` 空列表安全。

### 仍未覆盖（留待后续）
- 含输入框的四个 Dialog 页面内挂载态、SpdLibrarySheet / AiCopilotSheet / SpdInspectorDialog / WindowPanelSheet 栅格化。
- 蛋白页菜单下拉展开态、文件选择器回调、ONNX 下载/推理、SPICEP 导入路径；集合面板页面内挂载态；点运行后的算法状态变更、AI 对话链、设置页模型下载与写入分支。
- 序列栏 vh 保底、偏小热区按钮真机复核（沿第三轮待办）。

### 发版决策
- 本轮无确认修复的 Bug，未改用户可见行为（仅新增测试与本文档），不 bump 版本、不打 release；测试加固随下一次修复版本一并打包。

## 2026-11-16 · 第八轮（无设备降级走查，未发版）

### 环境
- `adb devices` 仍无设备；无 `/dev/kvm`；AVD 列表为空。继续降级全量单测 + Robolectric NATIVE 栅格化 + 静态走查。main 顶部仍 `6306ef1`，无新增提交。

### 本轮覆盖（新增 1 个走查测试，全量 800 绿 / 0 failures / 0 errors）
- **设置页散面板**（`SettingsAndProteinPanelsWalkTest`）：`DatabaseSyncPanel()` 空缓存初始态、`KeymapPanel()` 快捷键初始态，栅格化通过；SharedPreferences/Keymap 读取无异常，初始态不触发网络同步。
- **蛋白空态子面板**：`PocketPanel(emptyList())` 未检测到口袋空态、`MutationCartPanel(emptyList(), demo 序列, 空回调)` 空购物车空态、`PhasePanel(emptyList(), null)` 空扫描点相图，栅格化全部通过；空列表分支取值与 Canvas 空集判空到位。

### 静态兜底复核
- `DatabaseSyncPanel`：三个计数初值来自 `prefs.getInt(..., 0)`，`syncing*` 初值 false；网络同步只在点击按钮后的 `scope.launch{ withContext(IO){...} }` 内触发，初始组合期不联网。
- `PocketPanel` / `MutationCartPanel`：`pockets.isEmpty()` / `cart.isEmpty()` 先判空返回占位 Text，不进入 `forEach` 取值。
- `PhasePanel`：`temps.isEmpty() || phs.isEmpty()` 在 Canvas 绘制循环前 `return@Canvas`，空点集不除零、不越界。

### 未纳入本轮（依赖完整算法输出，留待算法链路挂载态走查）
- `FoldPanel(fold: FoldOutput, sequence)`：依赖 `length / coords 3N / secondary / distExp N×N / contactMap N×N / conf / runMs`，内嵌 `MolStarViewer` 与距离矩阵热图；构造空对象易触发 viewer 边界问题，留待真实运行折叠后在页面内挂载态验证。
- `MdPanel(md: MdStep)`：依赖 `uHist / metrics: MdMetrics / crashed` 完整 MD 步进对象，同留待 MD 运行后挂载态。

### 仍未覆盖（留待后续）
- 含输入框的四个 Dialog 页面内挂载态、SpdLibrarySheet / AiCopilotSheet / SpdInspectorDialog / WindowPanelSheet 栅格化、PresenterView 空演示态。
- 蛋白页菜单下拉展开态、文件选择器回调、ONNX 下载/推理、SPICEP 导入路径；集合面板页面内挂载态；点运行后的算法状态变更、AI 对话链、设置页模型下载与写入分支。
- 序列栏 vh 保底、偏小热区按钮真机复核（沿第三轮待办）。

### 发版决策
- 本轮无确认修复的 Bug，未改用户可见行为（仅新增测试与本文档），不 bump 版本、不打 release；测试加固随下一次修复版本一并打包。

## 2026-11-16 · 第九轮（无设备降级走查，未发版）

### 环境
- `adb devices` 仍无设备；无 `/dev/kvm`；AVD 列表为空。继续降级全量单测 + Robolectric NATIVE 栅格化 + 静态走查。main 顶部仍 `6306ef1`，无新增提交。

### 本轮覆盖（新增 1 个走查测试，全量 804 绿 / 0 failures / 0 errors）
- **浮动窗口面板四枚举**（`WindowPanelWalkTest`）：`HISTORY` 空撤销栈历史树、`LOGGER` 空事件日志 CRT 终端、`SETTINGS` 设置入口、`RESET` 重置入口，经公共入口 `WindowPanelSheet` 逐一栅格化通过；空栈/空日志分支不进入 `forEach`，`FloatingPanel` offset/sizeIn 组合期无 NPE。

### 静态兜底复核
- `WindowPanelSheet`：`undoStack.isEmpty()` / `log.isEmpty()` 先判空返回占位 Text，非空才进 `LazyColumn.items`；`LazyColumn` 均带 `heightIn(max=…)`，不向父级索求无穷高度。
- `AiCopilotSheet`：`endpoint/model/apiKey/temperature/maxTokens/systemPrompt` 均由 `prefs.getString(…, 默认) ?: 默认` 兜底，初始态不联网；`AiChatStore.ensureLoaded(ctx)` 同步读本地会话。

### 未纳入本轮（留待页面内挂载态或真机）
- `SpdLibrarySheet`：`ModalBottomSheet` 内含查询输入框，独立 sheet 窗口内光标动画易使 idling 不收敛（同第七轮输入框夹具限制）；`refresh()` 仅在切库/搜索按钮点击后触发，初始态不联网。
- `AiCopilotSheet`：需构造 `AiWorkspace`，内部聊天 `LazyListState` + 多个输入框，独立栅格化受 idling 与对象构造双重限制。
- `SpdInspectorDialog`：初始 `loading=true` 异步拉取 SPD 记录，`record/related/geneRegions` 均可空，留待挂载态覆盖。
- `PresenterView`：`LaunchedEffect(Unit){ while(true){ delay(1000); elapsed++ } }` 秒级循环在虚拟时钟下不易 idle；空 markdown 空演示态分支已静态核对。
- `FoldPanel` / `MdPanel`：沿第八轮，依赖完整 `FoldOutput` / `MdStep`，留待算法运行后挂载态。

### 仍未覆盖（留待后续）
- 含输入框的四个 Dialog 与上述 Sheet/Dialog 页面内挂载态；集合面板页面内挂载态。
- 蛋白页菜单下拉展开态、文件选择器回调、ONNX 下载/推理、SPICEP 导入路径；点运行后的算法状态变更、AI 对话链、设置页模型下载与写入分支。
- 序列栏 vh 保底、偏小热区按钮真机复核（沿第三轮待办）。

### 发版决策
- 本轮无确认修复的 Bug，未改用户可见行为（仅新增测试与本文档），不 bump 版本、不打 release；测试加固随下一次修复版本一并打包。



