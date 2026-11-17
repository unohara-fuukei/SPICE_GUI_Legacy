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

## 2026-11-16 · 第十轮（无设备降级走查，未发版）

### 环境
- `adb devices` 仍无设备；无 `/dev/kvm`；AVD 列表为空。继续降级全量单测 + Robolectric NATIVE 栅格化 + 静态走查。main 顶部仍 `6306ef1`，无新增提交。

### 本轮覆盖（新增 1 个走查测试，全量 807 绿 / 0 failures / 0 errors）
- **酶库信息卡**（`PanelInfoWalkTest.enzymeLibraryInfo`）：`EnzymeLibraryInfo()` 纯展示 Card + REBASE 计数文案，栅格化通过。
- **批量设计筛选空面板**（`batchPanel_emptyResults`）：`BatchPanel(emptyList())` 空突变库占位分支，栅格化通过。
- **ONNX 模型状态小节**（`modelSettingsSection_initialState`）：`ModelSettingsSection()` 初始态同步读取 `ModelStore.status(ctx)` 文件状态（不联网），下载/删除仅在按钮点击后的 IO 协程内触发，栅格化通过。

### 静态兜底复核（#111 同类格式化隐患排查）
- `BatchPanel`：`MutationAnalysis` 字段 `pIDelta/gravy/chargeAtPh7/disorderScore` 为 `Double`、`helixPercent` 为 `Int`；格式化串 `%+.2f / %.3f / %+.1f / %d%% / %.3f` 与字段类型一一匹配，无 Int 走浮点转换隐患；`thermostabilityScore` 为 Int，仅文本插值未走格式化。
- `ModelSettingsSection`：`sizeBytes: Long`，`sizeMb = "%.1f".format(sizeBytes/1024.0/1024.0)` 经 Double 除法后再格式化；`status.error: String?` 空时兜底 `"—"`。
- `ModelStatus(ok, sizeBytes, shaMatch, error)` 四字段在状态文案分支均有判空/兜底。

### 未纳入本轮（含输入框或强异步，留待挂载态）
- `SpdSettingsSection` / `InferModelSettingsSection` / `PrimerThermoSettingsSection`：含多个 `OutlinedTextField`（凭据/路径/物理参数），独立栅格化受输入框光标动画 idling 限制；初始 pref 读取均有 `?: 默认` 兜底。
- `HybridizationDialog`：含两个数字输入框的 `AlertDialog`，同第七轮输入框夹具限制。
- 其余沿第九轮清单：`SpdLibrarySheet` / `AiCopilotSheet` / `SpdInspectorDialog` / `PresenterView` / `FoldPanel` / `MdPanel`，集合页面内挂载态。

### 仍未覆盖（留待后续）
- 含输入框的 Dialog/Sheet 页面内挂载态；集合面板页面内挂载态。
- 蛋白页菜单下拉展开态、文件选择器回调、ONNX 下载/推理、SPICEP 导入路径；点运行后的算法状态变更、AI 对话链、设置页模型下载与写入分支。
- 序列栏 vh 保底、偏小热区按钮真机复核（沿第三轮待办）。

### 发版决策
- 本轮无确认修复的 Bug，未改用户可见行为（仅新增测试与本文档），不 bump 版本、不打 release；测试加固随下一次修复版本一并打包。

## 2026-11-16 · 第十一轮（无设备降级走查，未发版）

### 环境
- `adb devices` 仍无设备；无 `/dev/kvm`；AVD 列表为空。继续降级全量单测 + Robolectric NATIVE 栅格化 + 静态走查。main 顶部仍 `6306ef1`，无新增提交。

### 本轮覆盖（新增 1 个走查测试，全量 810 绿 / 0 failures / 0 errors）
- **选区工具条**（`SelectionExtrasWalkTest.selectionToolkitBar`）：`SelectionToolkitBar(1..10, demo 序列, 空回调)`，GC/Tm 摘要与复制/复制图像/RevComp/删除/取消钮初始态栅格化通过。
- **竖工具轨**（`sequenceToolRail`）：`SequenceToolRail("browse", …, 三开关 false, 空回调)`，浏览/编辑/注释/酶切/特征/翻译/杂交/批注图标钮初始态栅格化通过。
- **选区属性浮卡**（`selectionPropertiesCard`）：`SelectionPropertiesCard(1..10, demo 序列, 空回调)`，物理区间/长度/GC/Tm 行与记批注/复制钮初始态栅格化通过。

### 静态兜底复核
- 两组件 `seg = dna.substring((sel.first-1).coerceIn(0, dna.length), sel.last.coerceAtMost(dna.length))`，越界选区先收拢再取子串，不抛 `IndexOutOfBounds`。
- GC 含量 `gc: Double` 走 `%.1f`；`nebTm(seg)` 返回可空 `Double?`，Tm 行经 `listOfNotNull` / `tm?.let` 过滤，空值不进展示行。
- 工具轨根 `width(44.dp).fillMaxHeight()` 在有界父级内取值，非 `verticalScroll` 索求无穷高度；无输入框、无组合期异步。

### 仍未覆盖（留待后续）
- 图谱/序列/特征/属性/备注五个基因内视图的独立 Canvas 组件（`CircularMap` / `LinearMap` / `FeaturesView` / `NotesView` / `PropertiesView`）与 `SequenceMinimap`：随 `GeneScreen` 初始态已覆盖，独立挂载态待后续。
- 含输入框的 Dialog/Sheet 页面内挂载态；集合面板页面内挂载态。
- 蛋白页菜单下拉展开态、文件选择器回调、ONNX 下载/推理、SPICEP 导入路径；点运行后的算法状态变更、AI 对话链、设置页模型下载与写入分支。
- 序列栏 vh 保底、偏小热区按钮真机复核（沿第三轮待办）。

### 发版决策
- 本轮无确认修复的 Bug，未改用户可见行为（仅新增测试与本文档），不 bump 版本、不打 release；测试加固随下一次修复版本一并打包。

## 2026-11-16 · 第十二轮（无设备降级走查，未发版）

### 环境
- `adb devices` 仍无设备；无 `/dev/kvm`；AVD 列表为空。继续降级全量单测 + Robolectric NATIVE 栅格化 + 静态走查。main 顶部仍 `6306ef1`，无新增提交。

### 本轮覆盖（新增 1 个走查测试，全量 813 绿 / 0 failures / 0 errors）
- **崩溃报告覆盖层**（`MapsAndOverlayWalkTest.crashReportOverlay`）：`CrashReportOverlay("demo report", {})` 红底诊断浮层初始态栅格化通过；`heightIn(max=屏高 0.45)` 有界，纯文本不联网。
- **环形图谱**（`circularMap_emptyEnzymes`）：`CircularMap(demo 质粒, emptySet())` 空酶名集合初始态栅格化通过。
- **线性质粒图谱**（`linearMap_emptyEnzymes`）：`LinearMap(demo 质粒, emptySet())` 空酶名集合初始态栅格化通过。

### 静态兜底复核
- 两图谱 `hits = remember(...) { Enzymes.digest(sequence, enzymeNames, circular) }`，空酶名集合返回空命中；`record.length == 0` 在 `bpAt` 内先判空返回 null；空 hits/features/标签目标列表在 Canvas 绘制循环前已可空遍历，不裸取 `[0]`。
- 缩放/平移/旋转状态均为 `remember` 初值；`pngBusy` 初值 false，PNG 导出仅在工具栏 PNG 按钮点击后的 `scope.launch` IO 协程内触发，初始组合期不写盘、不访问外部存储。
- `LaunchedEffect(selection)` 初始 `selection == null` 直接清空 raw 端点并收敛，不进入越界分支。

### 未纳入本轮
- `FeaturesView`（搜索 `OutlinedTextField` + 过滤/排序状态）、`PropertiesView`（`fillMaxSize.verticalScroll` + 多个 `OutlinedTextField`）、`NotesView`（需构造 `ElnPage`/`SelNote` 列表与批注回调）：受输入框夹具 idling 限制或参数构造依赖，留待页面内挂载态。
- `SequenceMinimap` 依赖 `LazyListState`（初始 `totalItemsCount=0`），可在后续序列视图挂载态一并覆盖。
- `MolStarViewer` 依赖完整 `FoldOutput`，沿第八/十轮留待算法运行后挂载态。

### 仍未覆盖（留待后续）
- 特征/属性/备注三个基因内视图独立挂载态；`SequenceMinimap` 独立挂载态。
- 含输入框的 Dialog/Sheet 页面内挂载态；集合面板页面内挂载态。
- 蛋白页菜单下拉展开态、文件选择器回调、ONNX 下载/推理、SPICEP 导入路径；点运行后的算法状态变更、AI 对话链、设置页模型下载与写入分支。
- 序列栏 vh 保底、偏小热区按钮真机复核（沿第三轮待办）。

### 发版决策
- 本轮无确认修复的 Bug，未改用户可见行为（仅新增测试与本文档），不 bump 版本、不打 release；测试加固随下一次修复版本一并打包。

## 2026-11-16 · 第十三轮（无设备降级走查，未发版）

### 环境
- `adb devices` 仍无设备；无 `/dev/kvm`；AVD 列表为空。继续降级全量单测 + Robolectric NATIVE 栅格化 + 静态走查。main 顶部仍 `6306ef1`，无新增提交。

### 本轮覆盖（新增 1 个走查测试，全量 816 绿 / 0 failures / 0 errors）
- **序列缩略图**（`LayoutAndMinimapWalkTest.sequenceMinimap_emptyState`）：`SequenceMinimap(demo 序列长度, emptyList(), null, rememberLazyListState(), {})` 空特征/空选区/初始列表状态下 Canvas 初始态栅格化通过。
- **自适应分栏 EXPANDED 分支**（`adaptiveSplit_expanded`）：`AdaptiveSplit(EXPANDED, {}, {})` 左右分栏 Row + 垂直分隔条初始态栅格化通过。
- **自适应分栏 COMPACT 分支**（`adaptiveSplit_compact`）：`AdaptiveSplit(COMPACT, {}, {})` 仅渲染 master 分支初始态栅格化通过。

### 静态兜底复核
- Minimap：`dnaLen <= 0 || w <= 0` 在绘制前 `return@Canvas`；特征彩条 `forEach` 空列表安全；`visible.firstOrNull()` 判空，`totalItems > visible.size && totalItems > 0` 不满足时走整宽视口框兜底分支；选区为 null 或全序列（`first==1 && last==1`）时不画选区块；坐标 `coerceIn/coerceAtLeast/coerceAtMost` 收拢到画布内。
- `AdaptiveSplit`：`EXPANDED` 才走分栏 Row（master 380dp + divider + detail weight 1），其余宽度类直接 `master()`；容器在有界父级内取 `fillMaxHeight`，无索求无穷高度。
- Minimap `pointerInput` 仅在点击手势触发 `onJump`，初始组合期不读取列表滚动偏移以外的状态。

### 未纳入本轮
- `FeaturesView` / `PropertiesView` / `NotesView`：含搜索/属性/签署 `OutlinedTextField`，且 `PropertiesView`/`NotesView` 根为 `verticalScroll`，独立栅格化受输入框光标动画 idling 与无穷高度夹具限制；`NotesView` 已静态核对 `pages.getOrNull(currentIdx) ?: firstOrNull ?: ElnPage()` 判空兜底、选区子串 `coerceIn` 收拢。
- `MolStarViewer` 依赖完整 `FoldOutput`，沿前几轮留待算法运行后挂载态。

### 仍未覆盖（留待后续）
- 特征/属性/备注三个基因内视图页面内挂载态。
- 含输入框的 Dialog/Sheet 页面内挂载态；集合面板页面内挂载态。
- 蛋白页菜单下拉展开态、文件选择器回调、ONNX 下载/推理、SPICEP 导入路径；点运行后的算法状态变更、AI 对话链、设置页模型下载与写入分支。
- 序列栏 vh 保底、偏小热区按钮真机复核（沿第三轮待办）。

### 发版决策
- 本轮无确认修复的 Bug，未改用户可见行为（仅新增测试与本文档），不 bump 版本、不打 release；测试加固随下一次修复版本一并打包。

## 2026-11-17 · v1.7.59 发版轮（测试加固集中打包）

### 背景
- 第二至十三轮巡检均为纯测试加固 + docs，无确认修复的源码行为改动；本轮按指令统一发版，把累计走查测试（816 绿）随包打包。
- 版本：`versionCode 92 → 93`，`versionName 1.7.58 → 1.7.59`；README Latest 链接指向 v1.7.59 debug APK。
- 全量 **816** 单测 / 0 failures / 0 errors，`assembleDebug` 通过；发布包仅 `arm64-v8a / armeabi-v7a`。

### 本包内含的测试加固（相对 v1.7.58 增量）
- 选择工具条 / 工具轨 / 选区属性浮卡空态栅格化（`SelectionExtrasWalkTest`）。
- 崩溃报告覆盖层 + 环形/线性图谱空酶名初始态栅格化（`MapsAndOverlayWalkTest`）。
- 序列缩略图空态 + 自适应分栏 EXPANDED/COMPACT 分支栅格化（`LayoutAndMinimapWalkTest`）。
- 前序轮次：分析/克隆/工具/顶层屏/对话框/设置蛋白面板/窗口面板/面板信息/蛋白子 tab/基因内视图/欢迎演示页等走查测试随包一并打包。

### 未改动项（沿待办）
- 欢迎页版本标签仍硬编码 `1.7.10 · native`（未接线项，本轮未动）。
- 含输入框的 Dialog/Sheet、集合面板、蛋白 Fold/Md 输出态、AI 对话链、ONNX 下载/推理、SPICEP 导入等交互后路径仍待真机或页面内挂载态覆盖。




## 2026-11-17 · v1.7.61 巡检轮（MSA 网格短行越界修复）

### 环境
- `adb devices` 列表为空；无 `/dev/kvm`、CPU 无 vmx/svm、无运行中模拟器，仅 `system-images/android-30` 落盘。无法真机 logcat 抓崩，按约束降级为全量单测 + Compose 入口静态走查。
- main 对照：`git fetch origin` 后 `origin/main` 最新提交 `6306ef1`，无新增迭代可对照。

### 首要 Bug 复核（#121 质谱/免疫子 tab）
- 路径：蛋白翻译与三维视图 → 第五子 tab「质谱/免疫」，组合期同步调用 `peptideMassFingerprint` / `disulfideBonds` / `antigenicity` / `chargeDistribution`。
- 空/短序列兜底逐项核对：空串在 `n==0` 提前返回空结果；Cys<2 返回空二硫键列表；PMF 空串循环不迭代；电荷分布 `len<window` 返回单点；`clean()` 为 `uppercase().filter { it in 'A'..'Z' }`，等价 main `proteinAnalysis.ts` 的 `replace(/[^A-Z]/g,"")`。
- 上一轮 #111（`"%.2f".format(Int pairingProbability)` 抛 `IllegalFormatConversionException`）修复未回退（入参已是 `.toDouble()`）；全量扫描其余浮点格式化入参均为 Double。`Crispr` 内两个 Int efficiency 不在该 UI 格式化路径，属虚惊。
- 新增边界走查：7 条 record（空串 / 1bp / 2bp / 4bp / 非法字符 `XYZ123` / 纯氨基酸串 / 终止密码子 `ATGTAA`），逐一切换五子 tab 栅格化，全部通过，未复现组合期 NPE/越界/除零。真机崩溃栈仍待有设备时 logcat 复核。

### 本轮确认修复（#122）
- 现象：比对视图 `AlignmentView` 网格按 60 列分块渲染，行切片 `row.alignedSeq.substring(from, to)`。算法层（对齐 main `alignment.ts` 270-330）新行不补齐缺口，短行 `alignedSeq` 可能短于共识宽度；JS `slice` 越界自动截断，Kotlin `substring` 越界抛 `IndexOutOfBoundsException`。
- 修复：切片终点钳制为 `minOf(to, row.alignedSeq.length)`，对齐 main `slice` 截断语义；共识行长度恒等于 `width`，无需钳制。`GcView` 滑窗已有短序列与除零兜底。
- 回归：`AlignmentIOTest` 补不等长 / 含空串 / 单条 / 两条含空四组输入，断言 `consensus.length == width` 且每行 `alignedSeq.length <= width`。

### 测试与构建
- 全量 **821** 单测 / 0 failures / 0 errors；`assembleDebug` BUILD SUCCESSFUL。
- 版本：`versionCode 94 → 95`，`versionName 1.7.60 → 1.7.61`。

### 静态走查结论（本轮已核）
- `RnaView`：`best==null` 提前 return；dot-bracket `n==0` 返回；`dx=size.width/(n+1)` 不除零。
- `SangerView`：`trace==null` 引导导入；峰图仅在 `channels.isNotEmpty()`（`hasPeaks`）时进入，`maxOf` 非空；采样索引 `getOrNull` 兜底。
- `GcView`：短序列 `<window` 返回空列表；步长 `maxOf(1, window/2)`；`stepX` 除零 `coerceAtLeast(1)`。
- `ValidatorView`：`result!!` 位于 `result != null` 判空块内，逻辑非空。

### 仍未覆盖（留待后续）
- `BlastView` 四 tab（BLAST / 系统树 / Logo / DotPlot）运行后算法输出态；`ValidatorView` / `GenomicsView` / `SynbioView` 边界输入；`ImportAlignView` 文件解析后挂载态。
- 各 bench 工具（凝胶/blot/qPCR/孔板/菌落/标注/系统树/figure）页面内挂载态；设置页各 tab；项目集合页面内挂载态。
- 真机遗留：质谱/免疫 tab 真机崩溃栈；#118 圆形/线性图谱反向拖拽圆弧方向与跨度；AI 对话链、ONNX 下载/推理、SPICEP 导入、含输入框 Dialog/Sheet 挂载态、CollectionsBatchPanel 内嵌挂载态、FoldPanel/MdPanel 完整算法输出态、蛋白菜单下拉展开态。

## 2026-11-17 · 反向拖拽过程态复核（#123，未发版）

### 反馈
- 实测反馈：反向（从后向前）拖拽最终选区结果正确，但拖拽过程高亮仍按顺时针逻辑，松手后呈现为补集大弧，不符合直觉。对应 #118 遗留的「运行期反向拖拽圆弧方向与跨度待真机复核」。

### 对照结论（静态，无设备）
- main **线性图谱** `LinearMapCanvas.svelte:197-203`：拖拽即规整 `selectionStart=min(startBp,bp)`、`selectionEnd=max(...)`，高亮 `fillRect` 画短区间；native 线性 `Maps.kt:1015` 已对齐（`if (a<=b) a..b else b..a`）。
- main **环状图谱** `CircularMapCanvas.svelte:460-461`：拖拽**不规整**，`selectionStart=startBp`、`selectionEnd=currentBp`；弧 `ctx.arc(a0,a1,anticlockwise=false)`（546/552）固定顺时针，`end<start` 时浏览器沿顺时针绕过 2π，画补集大弧。顶层 `gene/+page.svelte` 仅在编辑/注释执行时 `Math.min/Math.max` 规整（1116-1117、1157-1158），拖拽期间不改写端点。
- native 环状 `Maps.kt:587-588` 保留 `selRawStart=anchor / selRawEnd=current` 原始方向、对外回传归一化区间；弧 `700` `sweep=((eAng-sAng)%360+360)%360` 顺时针取正角，与 main `arc(...,false)` 逐行等价。角度基准、端点刻线、跨编号 1 环绕口径一致。

### 判定
- 环状反向拖拽的顺时针补集大弧是 **main 原版既有交互**，native 当前为 1:1 复刻，未发现移植偏差；线性视图方向正确。
- 「拖拽过程/落定改为 min..max 短弧」属有意偏离 main 环状行为，受 1:1 硬约束与「交互改动须披露」约束，待 owner 拍板后再实施；本轮不改代码、不 bump 版本、不打 release。

### 待决策选项
- A 保持 1:1，维持 main 环状顺时针大弧。
- B 环状拖拽过程 + 落定都改 min..max 短弧（对齐线性视图；跨编号 1 环绕选区保留 main 语义），偏离项写入 README/changelog。
- C 仅松手落定后规整为短弧，过程预览保留 main 顺时针大弧。

## 2026-11-17 · 分析面板 / 工具边界兜底静态走查（#124，未发版）

### 环境与基线
- 无可用设备/模拟器：`adb devices` 空列表、无 `/dev/kvm`、`/proc/cpuinfo` 无 vmx/svm；按约束降级为全量单测 + Compose 入口静态走查。
- `git fetch origin` 后 main HEAD 仍为 `6306ef1`，无新迭代。
- 全量单测 **821 tests / 0 failures / 0 errors / 0 skipped**；`assembleDebug` BUILD SUCCESSFUL。工作区干净，本轮无源码改动、未 bump 版本、未打 tag/release。

### 本轮逐条复核的入口与兜底结论
- **ValidatorView**（`AnalysisPanels.kt:653-863`）：空/短序列提前 return；`result!!` 在 `result != null` 判空块内；OD/浓度 `toDoubleOrNull() ?: 99.0`；报告段 `warnings.isNotEmpty()` / `variants.isEmpty()` / `ifEmpty` / `?:` 守卫；步长 `maxOf(1, qs.size/60)` 防除零；质量分 `coerceIn(0.0,1.0)`。算法在 onClick 内，组合期不跑。安全。
- **GenomicsView**（`866-1084`）+ `core/GenomicsTools.kt`：RBS 强度 `substring(atgStartIndex.coerceAtMost(seq.length))`；启动子/信号肽/跨膜螺旋/倾斜曲线/CpG/串联重复均在短序列长度阈值处提前返回或不进 `while`；倾斜曲线 `denom>0`、CpG `c>0&&g>0` 防除零；渲染段均 `isNotEmpty()` 守卫，滑窗步长 `maxOf(1, sk.size)`。安全。
- **SynbioView**（`1087` 起）+ `core/SynBio.kt`：遗传回路 `0 until sorted.size-1` 空/单元素不迭代；MoClo 纯字符串比对；转化效率浮点除且指数 `maxOf(0.0, sizeKb-3)`；拷贝数空 features 返回默认 fallback；生物安全/CardAMR 清洗空串后 findAll 不迭代。安全。
- **BlastView 四 tab**（`1447` 起）：
  - EvoBlastTab：`blastHits.isEmpty()` 空态分支，非空才 `forEach`；`runLocalBlast`（`Evolution.kt:25`）subjects 空不迭代，短序列 `while (i <= length-k)` 条件为负不进入、不触发 `substring`。安全。
  - EvoTreeTab：`treeNames.size < 2` 提前 return；`buildPhylogeneticTree`（`Evolution.kt:133`）`n==0` 抛异常已被 UI 守卫拦住；NJ 循环在 `activeNodes.size>2` 内，`len-2>0` 不除零。默认集合实测 **4 条**（col_1..col_4，`Evolution.kt:361/372/383/394`；此前 grep -cE 把 348 行 `data class EvoCollection` 定义行计入，误读为 5 条），与 main `gene/+page.svelte:1793-1812` 四条及 4×4 `treeMatrix` 匹配，索引 0..3 不越界。
  - EvoLogoTab：固定 5 条引物；`bitsScaled/2.0` 浮点除；字符高 `maxOf(6, charH)`。安全。
  - EvoDotPlotTab：窗长/阈值 `toIntOrNull()?.coerceIn(5,50)/(3,50)`；`n,m=maxOf(1, …)` 防除零；`calculateDotPlot`（`Evolution.kt:306`）短序列同 while 负条件不迭代。安全。
- **ImportAlignView**（`1844-1892`）+ `MsaGrid`（`1745`）：`runCatching` 读文件；blank / `rows.isEmpty()` 守卫；过滤空序列行；`multipleAlign` 空输入返回空对象；`msa?.let` 守卫；网格 `blocks.coerceAtLeast(1)`，width=0 时块区间为空不迭代，`substring` 上界 `minOf(to, row.alignedSeq.length)`；空 rows `forEach` 不迭代。安全。
- **ELISA 4PL**（`Calculators.kt:180` `fitElisaStandardCurve`）：空 standards / `n<2` 兜底分支均 `unknownOds.map { 0.0 }`，正常分支 `unknownOds.map`；`fittedConcentrations` 长度恒等于未知样本数（UI 侧恒 1 个），`first()` 不触发空集合崩溃。UI `2500` 点安全。
- **蛋白 3D 点集**（`ProteinPanels.kt:287/304/315`）：`n==0` 提前 `return@Canvas`；`pts=(0 until n).map`，n>0 非空，`minOf/maxOf` 安全。
- **设置页**（`Screens.kt:322` `SettingsScreen` + `SettingsPanels.kt` 四个 Section）：组合期 remember 仅读 SharedPreferences 并 `?:` 兜底，不跑重算法；下载/推理在 onClick 内。语言下拉 `LANGUAGES.first { it.first == appLang }`（`Screens.kt:354`）受 `I18n.init→setLang` 钳制保护（`I18n.kt:46` 非法 tag 回落 zh），`lang` 恒在列表内，不会 `NoSuchElementException`。安全。
- **项目集合面板**（`CollectionsPanel.kt:70` `CollectionsBatchPanel`）：批量操作前 `selectedFiles.isEmpty()` 守卫；`filteredCollections` / `colAllTags` / 结果列表均 `forEach` 空不迭代；引物结果行恒为五元素 `listOf`，`r[0..4]` 索引安全；空集合导出 ZIP 提前 toast 拦截。安全。
- **AI 突变导入**（`AiCopilotSheet.kt:579-585`）：`validated.valid.isNotEmpty()` 守卫才挂导入按钮；`AiBlocks.validateMutations`（`AiBlocks.kt:75`）进入 valid 的行 `from.length==1 && to.length==1` 且在氨基酸表内，`first()` 不崩；`errors.first()` 在 `errors.isNotEmpty()` 守卫内。安全。
- **core 层 first/maxOf/除零扫尾**：`CollectionIO.kt:237/244` 空 positions 提前 return；`ExpressionCassette.kt:196-202` 空 insertionSites 走 `firstOrNull()?.let`，空集合不触达 `first()`，`site==null` 兜底返回；`CloningPlus.kt:42` 取静态密码子表首项（表非空）；`CloningMethods.kt:62` 空串 `c1.first()-1` 有 `isNotEmpty()` 守卫；`CloningPanels.kt:4105` `points.maxOf` 在 `points.isNotEmpty()` 内；`MARKER_SETS["DL2000"]!!` / `Enzymes.byName("BsaI")!!` / `baseColors[base]!!` / `parseHexColor(hex)!!` 均为固定字典键或固定格式输入。安全。

### 判定
- 本轮静态走查未发现确认崩溃（NPE / 越界 / 空集合 maxOf-first / 除零 / 未接线占位）。已覆盖入口与 main 口径一致，无需对齐修复；按惯例本轮不发版。

### 低风险遗留（非崩溃，未改动）
- `GenomicsTools.detectCpGIslands` 合并分支 `mc*mg` 在极端为 0 时可能得 Infinity，公式与 main 一致，未改。
- `GenomicsView:1078` 存在双「✓」文案前缀，纯文案瑕疵，非崩溃。

### 仍未覆盖（留待后续 / 真机）
- bench 工具页面内挂载态：凝胶 / blot / qPCR / 孔板 / 菌落 / 标注 / 系统树 / figure。
- `FoldPanel` / `MdPanel` 完整算法输出态；EvoBlastTab 大量命中的长列表滚动态；蛋白菜单下拉展开态。
- AI 对话链、ONNX 模型下载/推理、SPICEP 导入、含输入框 Dialog/Sheet 页面内挂载态、CollectionsBatchPanel 内嵌挂载态。
- 真机遗留：质谱/免疫 tab 崩溃栈；#118/#123 反向拖拽圆弧方向与跨度（#123 三选项仍待 owner 拍板）。

## 2026-11-17 · 3D 视图空序列空坐标聚合崩溃修复（#125，发版 v1.7.62）

### 环境与基线
- 无设备/模拟器：`adb devices` 空、无 `/dev/kvm`、CPU 无 vmx/svm；降级全量单测 + 静态走查。main HEAD 仍 `6306ef1`，无新迭代。
- 修复前基线 821 绿；本轮新增 3 例回归测试后 **824 tests / 0 failures / 0 errors / 0 skipped**，`assembleDebug` BUILD SUCCESSFUL。

### 确认崩溃（#125）
- **入口**：工具 → 3D 结构视图（`tools/Viewer3DScreen.kt`）。
- **复现链**：氨基酸序列输入框清空 → `ProteinCore.clean("")` 得空串 → `seq` 空 → `synthetic = seq.indices.map{…}` 为空 list；未导入结构时 `parsed == null`，Canvas 走 else 分支 `raw = synthetic.map{…}` 仍为空。归一化半径 `raw.maxOf { sqrt(…) }`（原 156/164 行）对空集抛 `NoSuchElementException: Collection is empty`；既有的 `if (raw.isEmpty()) return@Canvas` 守卫在聚合之后，来不及拦截。
- **对照 main**：`src/lib/viewer/MolStarViewer.svelte` 在 `coordsData`/`pdbText` 为 null 时不加载结构，引擎自身不聚合空坐标；native 无 Mol*，改用启发式螺旋占位合成坐标，空序列兜底是 native 占位实现新增的缺口，非 main 引擎行为。
- **修复**：`core/Structure.kt` 新增 `maxRadius(points)`，空集返回 `1f`（`maxOfOrNull { … } ?: 1f`），口径与同域 `Parsed` 的 `maxOfOrNull` 兜底一致；`Viewer3DScreen` 两处半径聚合改用 `Structure.maxRadius(raw)`，移除不再使用的 `kotlin.math.sqrt` import。空 `raw` 仍由既有 `isEmpty()` 守卫提前 return，不进入点映射/绘制。
- **回归测试**：`core/StructureRadiusTest.kt` 三例——空集返回 1f 不抛、单点半径为斜边、多点取最大半径。

### bench 工具静态走查结论（本轮覆盖，未发现其余确认崩溃）
- **Gel**（`GelScreen.kt` + `gel/`）：`imageEl!!` 在 `imageEl == null` 判空/`exp != null && imageEl != null` 块内；`size!!` 在 `size != null` filter / `all { it.size != null }` 守卫内；`selectedLane!!`/`bandRef!!`/`recentEdit!!`/`bandHit!!` 均在对应 `!= null` 或 `?.first == id` 条件分支内；`sorted.first/last` 在 `size < 2 return` / `size > 2` 守卫内。`GelFigure.wrapLabel` 返回 `listOf(…)` 恒非空。
- **Blot**（`BlotScreen.kt` + `blot/`）：`selectedBand!!`/`selectedLane!!` 在 `!= null` 判空块内；`data.maxOf` 在 `data.isNotEmpty()` 守卫内。
- **Qpcr**（`QpcrScreen.kt` + `qpcr/`）：`points.minOf/maxOf` 在 `points.isNullOrEmpty() continue` 之后，非空。
- **Colony**（`ColonyScreen.kt` + `colony/`）：`best!!` 仅在 `keepManualClass`（`best?.classManual == true`，即 best 非空）时取用；`workMeta!!`/`workInfo!!` 在 `workMeta != null` / `workInfo != null` 守卫内；`classified.first()` 输入单 marker、`classifyMarkers` 输出长度与输入一致；`files.first()` 在 `files.isEmpty() return` 守卫内。
- **Plate / Annotate / Tree / Figure / Photo / Report / File / Ai**：聚合均为 `maxOfOrNull ?: 兜底` 或空集合 forEach 不迭代；`TreeScreen.support.bins` 由 `emptySupportSummary(10)` 保证 10 桶非空。
- **core 层补扫**：`Calculators` 凝胶/ELISA/酶动力学回归 `n<2` 兜底，`den/totalSS != 0` 守卫；`PhaseBifurcation` `pts.size<6` 抛受控异常；`ProteinBiophysics`/`ProteinView`/`RnaStructure` 各 `n==0`/`n<4` 提前返回；`MultipleAlign` `cols/width>0` 守卫；`ImageTools` `n.coerceAtLeast(1)`；`CodeSign`/`CloneValidation` `count/size==0` 守卫；其余 `!!` 为固定字典键（PKA/末端基团/密码子表/固定酶名/固定格式颜色）。

### 发版
- versionCode 95→96，versionName 1.7.61→**1.7.62**；Changelog 与根 README Latest 链接已更新；打 tag `v1.7.62` 并从 release 上传 debug APK（仅 arm64-v8a / armeabi-v7a）。

### 仍未覆盖（留待后续 / 真机）
- `FoldPanel` / `MdPanel` 完整算法输出态；EvoBlastTab 大量命中长列表滚动态；蛋白菜单下拉展开态。
- AI 对话链、ONNX 模型下载/推理、SPICEP 导入、含输入框 Dialog/Sheet 页面内挂载态、CollectionsBatchPanel 内嵌挂载态。
- 真机遗留：质谱/免疫 tab 崩溃栈；#118/#123 反向拖拽圆弧方向与跨度（#123 三选项仍待 owner 拍板）；本轮 3D 空序列崩溃为静态走查定位，空输入组合期路径仍待真机复测。

## 2026-11-17 · 双向追溯终止密码子残基 ΔΔG 空串崩溃修复（#126，发版 v1.7.63）

### 环境与基线
- 无设备/模拟器：`adb devices` 空、无 `/dev/kvm`、CPU 无 vmx/svm；降级全量单测 + Compose 入口静态走查。`git fetch` 后 main HEAD 仍 `6306ef1`，无新迭代；native 起始 `15d5f88`（#125 v1.7.62），工作区干净。
- 修复前基线 824 绿；本轮新增 2 例回归测试后 **826 tests / 0 failures / 0 errors / 0 skipped**，`assembleDebug` BUILD SUCCESSFUL。

### 确认崩溃（#126）
- **入口**：基因/蛋白 → 分析面板「双向追溯」子 tab（`ui/AnalysisPanels.kt:152`）。
- **复现链**：DNA CDS 末尾（或中间）含终止密码子 TAA/TAG/TGA → `buildLifecyleTraceMap` 为每个完整密码子生成一条追溯条目；单密码子调用 `getAminoAcidStringFromSequenceString` 时 `*` 被 main 1:1 quirk 剥成空串（见 `validateStopGainQuirk` 既有测试）→ 该条目 `aminoAcid = ""`。面板计算 ΔΔG 时 wt 端写 `ct.aminoAcid.first()`，对空串抛 `NoSuchElementException`；只要选中该追溯残基（`currentTrace` 非空即计算）即触发。
- **对照 main**：`src/lib/genome/biophysics/lifecycle.ts:31-41` 同构生成条目；`proteinAnalysis.ts:246` `predictMutationalStabilityDdG(wtRes: string, …)` 收整串，内部 `toUpperCase()` 后表查 `AA_VOLUME[wt] || 100`，空/未知查不到走默认值，不抛；面板 `ProteinViewPanel.svelte:225` 直接传整串 `currentTrace.aminoAcid`。native 函数签名为 `Char`，调用点额外取了首字符，是相对 main 的边界缺口。
- **修复**：`AnalysisPanels.kt:152` wt 端改 `aminoAcid.firstOrNull() ?: 'X'`（未知残基兜底，与 `getAaOfTriplet` 未知→`'X'` 同口径）；`backMutationAa` 为下拉单字符，保持 `.first()`。core `mutationalStabilityDdg` 签名与其余 5 处调用点不动。
- **回归测试**：`GeneAnalysisTest` 两例——`buildLifecyleTraceMap("ATGTAA",0,5)` 第二条终止子 `aminoAcid` 为空串；空串经 `firstOrNull() ?: 'X'` 兜底后 `mutationalStabilityDdg` 不抛且 `ddG` 有限。

### 静态走查结论（本轮覆盖，未发现其余可达确认崩溃）
- **AI 链**：`AiCopilotSheet` 历史 `optJSONObject ?: continue`、toolNotes `optJSONArray` 长度安全；`AiTools`/`AiContext` `errors.first()` 在 `isNotEmpty()` 或 `firstOrNull()?.role` 守卫内；`AiStream` mergeToolCalls `slot!!` 为刚赋值后的编译器窄化补偿。
- **ONNX**：`SpiceOnnx.fold` 输出名固定，`ProteinScreen` 以 `runCatching` 全包裹回退启发式折叠，`fold==null` 提前 return；异常不崩溃。
- **集合 / EvoBlast**：`CollectionIO` `positions.first/last` 在 `isEmpty() return` 后、`currentFeat!!` 在 `!= null` 块内；`EvoBlastTab` `blastHits.isEmpty()` 空态守卫。
- **core 算法补扫**：`Evolution` k-mer 循环 `i <= length-k`、短序列不进循环；NJ 树 n=1 越界与 main 浏览器 fallback 同口径，唯一调用点 `EvoTreeTab` 有 `size<2 return` 拦截；Logo 空列表返回、dotPlot `maxOf(1,…)`；`Calculators` 除零在 `n<2`/`isEmpty`/`den!=0` 守卫后；`ProteinBiophysics` antigenicity/FoldIndex `n==0 return`、count≥1、Tanimoto `union==0` 守卫；`AlignmentIO` `rows[name]!!` 在 `containsKey`+`put` 同块内；`CloningMethods`/`CloningPlus` `first()` 在 `isNotEmpty`/`?: return@forEach` 守卫内；`ExpressionCassette` `insertionSites.first()` 由 `firstOrNull()?.let` + `site==null return` 兜底；`FigureCharts` `series.last()` 在 `isEmpty() continue` 后；`FigureCompliance` 三元组 `!= null` 守卫、Severity 字典三值全覆盖；`FigureImportVector` `first/last` 在 `size>1` 或 `isNotEmpty` 守卫内；`EnzymesVe` 上下切点成对赋值/成对置 null；`SwAlign` `m==0||n==0 return`、`n<k` 空列表；`MultipleAlign` 双空守卫 + `firstOrNull()?.getOrNull`；`RnaStructure` `n<4 return`；`SangerValidate` 双空守卫；`SettingsPanels` 无 `!!`/聚合/索引命中。
- **其余 ddG 调用点**：`ProteinScreen` 序列扫描 `from` 为 `seq` 字符（`seq.isEmpty()` 守卫）；位点突变 `from=getOrNull`/`to=firstOrNull` 在 `!= null` 复合守卫内；`ProteinPanels` 购物车元素由 `Triple(Int,Char,Char)` 类型约束且加入处经同样守卫；`AiOfflineTools`/`AiTools` 传入合法单字母。

### 低风险遗留（损坏/极端输入，正常路径不可达，未改动）
- **SPICEP 残缺坐标**：`ProteinScreen.kt:128-129` `referenceCoords.chunked(3)` 末块若非 3 倍数，`c[2]` 越界；`Spicep.doubles()` 不校验 3 倍数。仅手工构造/损坏 .spicep 可触发；main 越界索引取 `undefined`（NaN）不抛。本 app 自身 `build()` 产出恒 3N。下游 `ProteinPanels.kt:293-307` 按 `fold.length` 迭代取 `coords[i*3+2]`，同类边角。
- **Golden Gate 空列表**：`CloningExt.kt:322/331` 内部索引首/末碎片；main `api.ts:930/940` 同结构空列表下 JS 亦抛 TypeError（main 未守卫）。唯一 UI 调用点 `CloningPanels.kt:382` 已 `frags.size < 2` toast+return 拦截，core 不可达空输入。
- `GenomicsTools.detectCpGIslands` 合并分支 `mc*mg` 极端为 0 得 Infinity（与 main 同公式）；`GenomicsView:1078` 双「✓」文案前缀（非崩溃）。

### 发版
- versionCode 96→97，versionName 1.7.62→**1.7.63**；`android-md3/README.md` Changelog 头部条目、根 README Latest 链接已更新；打 tag `v1.7.63` 并从 release 上传 debug APK（仅 arm64-v8a / armeabi-v7a）。

### 仍未覆盖（留待后续 / 真机）
- 含输入框 Dialog/Sheet 页面内挂载态（SpdLibrarySheet、FigureImportDialog/CheckDialog 主体仅扫到 `getOrNull` 一层）；AI 对话链真机挂载态；ONNX 模型下载/推理真机路径；CollectionsBatchPanel 内嵌挂载态；EvoBlastTab 长列表滚动态；蛋白菜单下拉展开态；Sanger/基因组学/合成生物/比对/GC 窗口完整算法输出态。
- 真机遗留：质谱/免疫 tab 崩溃栈；#118/#123 反向拖拽圆弧方向与跨度（#123 三选项仍待 owner 拍板）；空输入组合期路径仍待真机复测。

---

## #127 巡检记录（无确认崩溃，纯 docs，未发版）

### 环境与基线
- 无设备/模拟器（无 adb、无 /dev/kvm、无 virt flag），降级全量单测 + Compose 入口静态走查。`git fetch` 后 main HEAD 仍 `6306ef1` 无新迭代；native 起始 `81ad7bb`（#126 v1.7.63），工作区干净；版本号 97 / 1.7.63 未改动。
- 基线沿用 **826 tests / 0 failures / 0 errors / 0 skipped**；本轮未改代码、未新增测试，未跑新构建（无确认崩溃，按规则不发版）。

### 本轮新增覆盖与守卫结论（均判定安全，无可达崩溃）
- **CloningPanels.kt 遗留命中逐条复核**：
  - `1784 validation!!.valid`：`1769` `validation = validateVectorAssembly(res)` 赋值后至 1784 无提前 return，非空；`1861` 另用 `validation?.let` 空安全。
  - `2454 est.first.first()`：`estimateGelBandSizes` 返回列表长度 = 入参 unknownDistances.size，UI 恒传 `listOf(unk)` 单元素，列表恒非空。
  - `2500 res.fittedConcentrations.first()`：`fitElisaStandardCurve` 各早退分支均返回 `unknownOds.map{0.0}`，长度 = 入参；UI 恒传单元素未知 OD，非空。
  - `2843 rowCells[0]`：`generate2DGrid` 每行由 `columns.map` 生成，96-well 12 列 / freezer 10 列，行恒非空。
  - `4106/4107 points.maxOf/minOf`：`if (points.isNotEmpty())` 守卫内；`487 bufferSearchResults.first()` 在 `isNotEmpty()` 内；`3379 parts[1]` 在 `parts.size>1` 内；`4622/4811` `split(" ").first()` 对空串返回空列表首元素前 `ifEmpty`/直取安全；`1336/1398 MARKER_SETS["DL2000"]!!` 为固定兜底键（首条目即 DL2000）。
- **ProteinPanels.kt**：`315 pts.minOf/maxOf{it.z}` 在 `287 if (n==0) return@Canvas` 后，`pts=(0 until n).map` 非空；`437` 背景字典键与 `listOf("dark","white","transparent")` 同集全覆盖；`610 bifurcation!!` 在 `609 if (bifurcation != null)` 内；`629 coefficients[0..5]` 长度 6 与 `PhaseBifurcation` 最小二乘 6 系数、`cx` 5 符号 + w[0] 对应，长度匹配。
- **core 除零/索引补扫**：`AiRetrieval:70 sqrt(maxOf(1.0,…))`、`43 maxOf(0,…)`；`CodonOpt:278 totalCodons>0`、`279 clean.isNotEmpty()`；`NgsQc:93 totalReads==0 throw`、`70` 空读 continue 故 totalBases≥totalReads>0、`98 qCount>0` 守卫 99；`PrimerAnalysisMain:75 bps.isEmpty()` 守卫；`RnaStructure:47 n<4 return` 守卫 103 `/n`；`SangerValidate:121 total==0` 守卫；`SeqIO:55 qualCount>0`；`SynBio:144 /1000.0` 常量；`GenomicsTools:176 w=20` 常量、`179` 短序列不进循环；`Crispr:360` Double 除零得 Infinity 不抛（与 main 同口径）；`EnzymeSelector:212 hash.toInt()` 为 Long 截断非字符串解析、`219 abs(hash%10)` 0..9 配色表 10 项；`FigureTiff:188-192` rgba 索引入参恒 4N 像素（损坏文件边角，低风险）；`PhaseBifurcation` w 为 6 系数解。
- **tools/ 全目录静态走查**（主屏 + annotate/blot/colony/gel/plate/qpcr 子包）：
  - `AiScreen:58 runs.maxOf` 在 `isNotEmpty()` 守卫内；`BlotScreen:1274/1275 selectedBand/Lane!!` 在双 `!= null` 守卫内。
  - `ColonyScreen`：`437-442 best!!` 由 `keepManualClass = best?.classManual==true` 蕴含非空；`448/558/641/1329 workMeta!!` 均在 `workMeta != null` 复合守卫内；`644 classified.first()` 输入单元素；`943/960 files.first()` 在 `933 isEmpty() return` 后；`1431 workInfo!!` 在 `1419 hasImage && workInfo != null` 块内。
  - `GelScreen`：`255/665 imageEl!!` 在 null 检查/复合守卫内；`542/576` `size != null` 过滤后 `size!!`；`1007/1008` `sorted.size<2 return` 后；`2131 selectedLane!!`、`2142 bandRef!!`、`2353 recentEdit?.first==id` 蕴含非空、`2472 bandHit!=null`、`2526 sorted.size>2`；`GelMath:152 current.last()` 在 `148 current==null` 分支后非空且组内 ≥1 元素；`GelFigure:303 size!=null` 过滤后 315 `size!!`、`331 mark!=null` 守卫 332/335、`584 fitted.lines` `wrapLabel` 恒返回 ≥1 行；`GelFigure:455` source "box" Image 构造时恒带 FigBox，`GelRender:104/248`、`GelPptxWriter:52` else 分支 `box!!` 安全；`GelProjectDoc:298` JSONArray 校验后 300 `!!`、`545 fit!=null` 守卫 546；`GelUi:527 result!!` 在 `result!=null` 内。
  - `PlateScreen:953 seedReport!!` 在 `!= null` 块内；`1257 selectedWell!!` 由 `selectedAssignment = selectedWell?.let{…}` 蕴含非空；`PlateTypes:133` replicates null 守卫、`322` JSONArray 校验后 323 `!!`。
  - `TreeScreen:305 support.bins.maxOf` `emptySupportSummary` 与 `supportSummary` 均 `max(1,…)` 长度；`516 comparison!!` 紧接 514 赋值；`889 parseError!=null` 守卫 900/901；`1006/1101 doc!=null` 守卫；`1133 THEME_COLORS[theme]!!` 键集覆盖 TreeTheme 两值；`1193 autosaveAt!=null` 守卫。
  - `QpcrAnalysis` ct/efficiency `!!` 均在 `!== null` filter 后 map；`buckets[key]!!` 的 key 全部来自 `buckets.keys` 遍历或其 filter 结果，键必在表内。
  - `ColonyTypes:309-312` `optJSONObject/Array!!` 在 `isColonyProject` 已逐条校验类型之后。

### 低风险遗留（损坏/极端输入，正常路径不可达，沿用 #126 清单，未改动）
- SPICEP 残缺坐标非 3 倍数越界（手工/损坏文件触发，main 取 undefined→NaN 不抛）；Golden Gate 空列表 core 索引（main 同抛，唯一 UI 调用点已 `size<2` 拦截）；`detectCpGIslands` 极端 0 得 Infinity；ONNX 异常模型张量边角；`FigureImportVector:1290` chart 防御分支不可达。

### 发版
- 本轮 **无确认崩溃，未改版本号、未打 tag、未推送 release**（versionCode 97 / 1.7.63 维持）。

### 仍未覆盖（留待后续 / 真机）
- 含输入框 Dialog/Sheet 页面内挂载态；AI 对话链真机挂载态；ONNX 下载/推理真机路径；CollectionsBatchPanel 内嵌挂载态；EvoBlastTab 长列表滚动态；蛋白菜单下拉展开态；SettingsPanels 各 tab 真机态；Sanger/基因组学/合成生物/比对/GC 窗口完整算法输出态。
- core 未通读正文：AiAudit/AiMemory/AiPrompts/EnzymeDb/XlsxReader 已确认无 first/last/!!/maxOf 命中；AutoAnnotate/PcrSimulation/SwAlign/SynBioRegistry/VirtualGel/EndModification 仅扫命中行；VirtualGel:47 `ln(agarosePercent)` 定义域、SynBioRegistry:147/151 正则分组待下一轮通读。

---

## #128 巡检记录（确认 1 处崩溃边界修复，发版 v1.7.64）

### 环境与基线
- 无设备/模拟器（无 adb、无 /dev/kvm、无 virt flag），降级全量单测 + Compose 入口静态走查。`git fetch` 后 main HEAD 仍 `6306ef1` 无新迭代；native 起始 `02cd9ef`（#127 docs）。
- 修复前基线 826 绿；本轮新增 1 例回归测试后 **827 tests / 0 failures / 0 errors / 0 skipped**，`assembleDebug` BUILD SUCCESSFUL。

### 确认崩溃（#128）
- **入口**：Co-Pilot 计算工具箱 → 突变自由能（`ui/AiToolsPanel.kt:221`）。
- **复现链**：先点「运行 SPICE 物理计算预测」成功，面板保留旧 `mutResult`，导入按钮随之显示；随后把单字符 From 框（`mutState.fromRes`，输入框允许清空成长度 0）清空，**不重新预测**，直接点「一键导入此单个突变至工作台」。原代码 `Triple(mutState.pos, mutState.fromRes.first(), mutState.toRes.first())` 对空串取首字符，抛 `NoSuchElementException`。
- **对照 main**：`src/lib/ui/AiCopilot.svelte:887` `importMutations` 先调 `validateMutations(muts, sequence)`；`checked.errors.length` 非空 → `pushToast('error', …, errors[0])` 并 return；`!checked.valid.length` → return；只有合法行才进 `onImportMutations`。native 在 UI 层绕过了该校验直接构造 `Triple(Char,…)`，是相对 main 的边界缺口。
- **修复**：`AiToolsPanel.kt` 导入回调先 `AiBlocks.validateMutations(listOf(Mutation(pos, fromRes, toRes)), sequence)`：errors 非空 → 状态行提示错误（标题复用「突变估算失败」+ `errors.first()`，对齐 main 错误 toast 结构）；valid 非空 → map 合法行构造 `Triple` 进购物车；空 valid 不动作。合法行的 `from`/`to` 已由 `validateMutations` 保证长度 1 且在 20 标准氨基酸内，`first()` 安全。
- **回归测试**：`core/AiBlocksTest.kt` 新增 `empty wildtype or mutant residue is rejected before cart import` —— 空 From、空 To、非标准字母三行全部进 `errors`、`valid` 为空（0 valid / 3 errors）。

### 静态走查结论（本轮覆盖，未发现其余可达确认崩溃）
- **core 遗留文件通读完毕**：
  - `AiAudit`：load 全 `runCatching` + `?: continue` + 枚举 `valueOf().getOrDefault`；redact 无索引/聚合。
  - `AiMemory`：load `runCatching`；CJK bigram `0 until length-1`，短串不进循环；`searchMemory` 空 queryTokens 早退；`limit.coerceAtLeast(1)`。
  - `AiPrompts`：纯常量字符串，无逻辑。
  - `EnzymeDb`：静态 `catalog`，仅 `size`/`sumOf`，无索引取键。
  - `XlsxReader`：DOM `0 until length` 索引，`while values.size < col` 补空单元格，无越界。
  - `AutoAnnotate.mergeIntervals`：`isEmpty() return` 后 `sorted[0]`；`removeAt` 在首元素已入表后；`FEATURE_COLORS["misc"]` 固定键存在。
  - `PcrSimulation`：`fragments.size < 2` 早退后才取 `first/last`。
  - `SwAlign`：`m==0||n==0` 早退；矩阵 `n+1 × m+1`，`i≤m`/`j≤n` 边界正确。
  - `SynBioRegistry`：正则带捕获组，`find` 命中时 `groupValues[1]` 必存在（`+` 量词非空）。
  - `VirtualGel.getMigrationY`：公式 verbatim 于 main `VirtualGel.svelte:80-86`；clamped bp ∈ [50,20000] 对数域安全；`ln(agarosePercent)` 在 0/负值下 JS/Kotlin 均出 Infinity/NaN 不抛，与 main 同口径（损坏输入边角，UI 默认 1.2）。
  - `EndModification.chewBack`：`substring(0, maxOf(0, len-n))` 钳制，同 main `endModification.ts:74 Math.max(0,…)`。
- **ui 残留命中复核**：`AnalysisPanels` 回写按钮在 `currentTrace?.let` 内（蕴含 `selectedAaIndex` 非空）；校验报告 `result!!` 在 `result != null` 守卫内；`GeneScreen` `byName("BsaI")` 固定内置酶（主名/别名均可命中）；`baseColors` 四键覆盖 A/T/C/G；`Maps` 特征色 `parseHexColor` 字面量合法、`panAnchorX!!` 在 `!= null` 分支内；`ProteinScreen` atoms 首末在非空三元分支、`md/pockets` 赋值后同块、`spdFoldDetail!!` 在 `!= null` 块内、SPD 提交 `fold!!` 在 `fold == null` 发布守卫之后；`GeneMenuExtras` 8 色调色板字面量合法。

### 低风险遗留（损坏/极端输入，正常路径不可达，沿用清单未改动）
- SPICEP 残缺坐标非 3 倍数；Golden Gate 空列表（UI 已拦截）；`detectCpGIslands` 极端 0 Infinity；ONNX 异常模型张量形状；`FigureImportVector` chart 防御分支不可达；`agarosePercent` 0/负值得 Infinity/NaN（与 main 同）。

### 发版
- versionCode 97→98，versionName 1.7.63→**1.7.64**；`android-md3/README.md` Changelog 头部条目、根 README Latest 链接已更新；打 tag `v1.7.64` 并从 release 上传 debug APK（仅 arm64-v8a / armeabi-v7a）。

### 仍未覆盖（留待后续 / 真机）
- 含输入框 Dialog/Sheet 页面内挂载态；AI 对话链真机挂载态；ONNX 下载/推理真机路径；CollectionsBatchPanel 内嵌挂载态；EvoBlastTab 长列表滚动态；蛋白菜单下拉展开态；SettingsPanels 各 tab 真机态；Sanger/基因组学/合成生物/比对/GC 窗口完整算法输出态。
- 真机遗留：质谱/免疫 tab 崩溃栈；#118/#123 反向拖拽圆弧方向与跨度（三选项待 owner 拍板）；空输入组合期路径仍待真机复测。

---

## #129 巡检记录（无确认崩溃，纯 docs，未发版）

### 环境与基线
- 无设备/模拟器（无 adb、无 /dev/kvm、无 virt flag），降级全量单测 + Compose 入口静态走查。`git fetch` 后 main HEAD 仍 `6306ef1` 无新迭代；native 起始 `ad43250`（#128 v1.7.64），工作区干净；版本号 98 / 1.7.64 未改动。
- 基线 **827 tests / 0 failures / 0 errors / 0 skipped**；`testDebugUnitTest` BUILD SUCCESSFUL（缓存命中后复核 XML 计数 827/0/0/0），`assembleDebug` BUILD SUCCESSFUL。本轮未改代码、未新增测试。

### 蛋白 5 子 tab 走查（`AnalysisPanels.kt`，tab 0 物化 / 1 折叠 / 2 功能域 / 3 双向追溯 / 4 质谱-免疫）
- 子 tab 选择器 `listOf(...) forEachIndexed` 切换 `tab` 状态；tab 4（379-434 `else ->`）四个 remember 计算 `peptideMassFingerprint / disulfideBonds / antigenicity(aaSeq,7) / chargeDistribution(aaSeq,30)`；二硫键、免疫表位有空集合守卫；PMF、电荷分布直接 `forEach`（空集合 `forEach` 不抛）。
- tab 3 追溯：`ddg` 在 `currentTrace?.let` 内；wt 残基 `ct.aminoAcid.firstOrNull() ?: 'X'`（#126 已修）；`selectedAaIndex!!` 在 `currentTrace?.let` 块内蕴含非空；回写按钮同块内取值，安全。

### `core/ProteinBiophysics.kt` 通读（守卫齐全，对齐 main `biophysics/proteinAnalysis.ts`）
- chouFasman n<6/n<5 while 不进、空串 mapIndexed 安全；helixPercent / kyteDoolittleProfile / gravy / disorderFoldIndex 空早退；proteinDomains 正则 findAll 空串无匹配；solubilityAndAggregation isEmpty 早退、w=6 while 守卫；mutationalStabilityDdg 纯查表无索引；peptideMassFingerprint 空串循环不进、`frag.isNotEmpty()` 守卫；comparePmf 空列表 forEach 安全；disulfideBonds cys.size<2 早退、索引 `n in 1..seq.length` 守卫；antigenicity n==0 早退、count≥1、len≥6 守卫；chargeAtPh/pI 无除零；chargeDistribution len<windowSize 返回单点、step=`maxOf(1,…)`；parseMutantsCsv `pos in arr.indices` 守卫、正则命中 groupValues 必有；evaluateMutantsBatch 空候选返回空；fold n=0 循环不进；scanCancellable 空列表返回空；detectPockets n==0 早退、median 空守卫；pocketDelta union==0 守卫；applyMutations 索引守卫。

### core 风险命中逐条复核（均判定安全，无可达崩溃）
- **first()/last()** 全部守卫齐全：CloningPlus 常量表非空 / 命中常量列表 / sp 恒长 20；PrimerAnalysis length<2 早退；CollectionIO isEmpty 早退；ReverseTranslate pool.isEmpty 早退；ProteinView isNotEmpty 守卫内；FigureCharts series.isEmpty 守卫；ExpressionCassette firstOrNull?.let 内；CloningMethods c1.isNotEmpty 内；CloningExt 低风险遗留 #2（UI 调用点已 size<2 拦截）；CloneValidation revComp 单字符非空；AiTools errors.isNotEmpty 内；AiContext firstOrNull 蕴含非空；SyntheticGeneQc merged.isEmpty 分支先入。
- **除零** 全部有守卫或得 Infinity 不抛：AiTools/Bio/Calculators/CloneValidation/CodeSign/Crispr（Double/0 得 Infinity）/GelGenie（下限 1e-6）/PhaseBifurcation（size<6 抛业务异常，UI try/catch）/PrimerAnalysis/ProteinCore/ProteinView n==0 早退/RnaStructure n<4 早退/GenomicsTools 极端 0 Infinity（遗留 #3）/SyntheticGeneQc 空循环不进/AnalysisPanels、ProteinPanels 步长 maxOf 或 coerceAtLeast/ColonyScreen counts.isNotEmpty 守卫。
- **正则 groupValues**：可选组未参与返回空串不越界；FigureImportVector 颜色正则 3 必选 + 2 可选、守卫；ProteinBiophysics MUT_RE 3 组命中取值安全。
- **core `!!`**：TreeTool 枚举键全覆盖 / getOrPut 建表后命中 / 先置 0 后取 / `?: 默认!!` 默认键必在；ModelStore conn!! 同迭代先赋值（真机下载路径，ONNX 待真机复核）；CollectionIO currentFeat!! 均 `!= null` 守卫内；AiStream slot!! null 分支补建后非空；ProteinCore PKA 键全在表内、Nterm/Cterm 固定键；AlignmentIO rows[name]!! containsKey 否则 put；FigureCompliance 三值 `!= null` 守卫、Severity 三值表全覆盖；ProteinView 355 codes=IUPAC.keys 遍历故 361 `IUPAC[a]!!` 必命中；476 bestCodon('*') 三宿主 map 均含 '*' 键必非空；SyntheticGeneQc 键在表内守卫；GelGenie session!=null 内；BatchEngine name!=null 内；Slides 行首零宽正则必命中、`mu ?: mo!!` mo 非空。

### ui 层 `!!` 复核（守卫齐全）
- `Screens.kt` 91/190/210 `detail!!`：85 `val proj = detail ?: return`；142 `val d = detail`、143 `if (d != null)` 块内；块进入时 detail 非空，`detail!!` 为冗余但当前渲染态非空，安全。
- `GeneScreen.kt` 516 `selection!!.first/last` 在 `selection == null` 三元非空分支；952 `byName("BsaI")!!` 固定内置酶；1992 `baseColors[base]!!` 四键覆盖 A/T/C/G。
- `CloningPanels.kt` 1336/1398 `MARKER_SETS["DL2000"]!!` 首条目即固定键；1784 `validation!!` 在 1769 同回调非空赋值之后。
- `SpdInspectorDialog.kt` 117 `record!!` 在 `record != null` 分支内；`GeneMenuExtras.kt` 259 `parseHexColor(hex)!!` 8 调色板字面量合法。

### tools 层 `!!` 复核（本轮逐文件扫完，守卫齐全）
- `QpcrAnalysis.kt`：ct/efficiency `!!` 均在 `!== null` filter 后 map；`buckets[key]!!` 键全部来自 `buckets.keys` 遍历或其 filter；入 `targetWells` 的 well 过 `usableWell`（`ct === null` 即 false），故 395/417/445/452/478 ct!! 安全；530 dCt[key]!! 在 `!== null` filter 后。
- `TreeScreen.kt`：227 `doc != null` 分支内；516 comparison 紧接赋值；900/901 parseError!=null 守卫；1008/1103 doc!=null 守卫；1133 THEME_COLORS[theme]!! 键集覆盖 TreeTheme 两值；1194 autosaveAt!=null 守卫；1397 `selectedLayoutNode != null && selectedTreeNode != null` 块内；1416 group!=null 守卫 1433、1441 comment!=null 守卫 1442；1454 selectedId 由节点点击选中后非空；1493 compareError!=null 守卫。
- `GelScreen.kt`：255/665 imageEl!! 在 null 检查/复合守卫内；542/576 `size != null` 过滤后 size!!；2131 selectedLane!!、2142 bandRef!! 分别在 `!= null` 守卫内；2353 `recentEdit?.first == recent.id` 蕴含非空；2472 bandHit!=null 守卫。
- `ColonyScreen.kt`：437-442 best!! 由 `keepManualClass = best?.classManual == true` 蕴含非空；448/558/641/1329 workMeta!! 均在 `workMeta != null` 复合守卫内；1431 workInfo!! 在 1419 `hasImage && workInfo != null` 块内。
- JSON 反序列化 `!!` 均有前置类型校验：`ColonyTypes` 309-312 在 `isColonyProject` 逐条校验之后；`GelProjectDoc` 298 JSONArray 校验后 300；`PlateTypes` 322 校验后 323。
- 其余：`Viewer3DScreen` parsed!=null 守卫；`GelBatch` 302/314/316 fit!=null 守卫；`GelProjectDoc` 545 fit!=null 守卫 546；`QpcrParse` 144/154 COLUMN_SYNONYMS 键集与 AUTO_MAP_ORDER 九键全覆盖；`PlateScreen` 953 seedReport!=null 守卫、1257 selectedWell!! 在 1232 `else`（非 null）分支内；`GelRender` 104/248、`GelPptxWriter` 52 else 分支 box!!——`FigItem.Image` source=="gel" 时 box=null、source=="box" 构造恒带 FigBox（GelFigure:256 / 455），互斥；`GelFigure` 303 size!=null 过滤后 315 size!!、331 mark!=null 守卫 332/335、203/637 三元内 size!!；`BlotScreen` 1273 双 `!= null` 守卫；`QpcrExample` 85 series 三键与 TARGETS 全覆盖；`GelUi` 527 result!=null 守卫；`AnnotateLayout` 372 `best == null || d < best!!.second` 走 `||` 短路，best 非空时才取 `!!`。

### 本轮记录项（非确认崩溃，字段对齐缺口，当前不可达）
- **`core/EnzymesVe.kt` Type IIS 双切酶字段未接线**：`VeEnzyme` 23-32 虽有 `cutType/usForward/usReverse` 字段，但全代码库仅有两处构造点——`getEnzymeByName`(158-165) 未传 cutType/usForward/usReverse（默认 0/null）、`enzymeFromSite`(174) 显式 cutType=0；grep 无第三处赋 cutType=1。故 260-276 cutType==1 上游分支在 native 不可达；主切点 281 界内时 289/290 已赋非空切点，321/322 `topSnipPosition!!/bottomSnipPosition!!` 当前不抛。
- 对照 main `src/lib/genome/enzymes.ts:183-197`（上游 snip 分支，同构）与 239/240/256/257 TS `topSnipPosition!/bottomSnipPosition!` 断言：main 在 cutType=1 上游分支置 `fitsWithinSequence=true` 而主切点越界时，snipPosition 可存 null，TS 不抛、下游算出 NaN；native `!!` 在此边界会抛 NPE，但当前无 cutType=1 酶到达该路径。main 酶库 Type IIS 样例见 `data/aliasedEnzymesByName.ts`（topSnipOffset 25/24/13 等）。性质：酶库映射未携带 Type IIS 双切酶的 cutType/usForward/usReverse，是对齐缺口；待真机/后续补酶库字段接线时一并补守卫。

### FigureImportVector 可达分支复核
- 1279 text 分支 `fontSize!!/padding!!`：text 节点经 `createTextNode`(819-833) 自带 fontSize=12.0 / padding=4.0，SVG 导入 938 再经 createTextNode 构造、945/950 覆盖，必非空。
- 1283 path/line/arrow 分支 `headSize!!`：经 `createPathNode`(844-855) 自带 headSize=12.0，1049 再构造、1061 覆盖，必非空。
- 1290 chart 分支 `fontSize!!`：全文件唯一 `type=="chart"` 判断（grep 仅此一处），无构造 chart 节点的路径，防御性不可达（低风险遗留 #5）。

### 低风险遗留（损坏/极端输入，正常路径不可达，沿用清单未改动）
1. SPICEP 残缺坐标非 3 倍数越界（`ProteinScreen.kt:127-129`、`Spicep.kt doubles()`、`ProteinPanels.kt:293-295/305-307`；main 取 undefined→NaN 不抛，仅手工/损坏文件触发）。
2. Golden Gate 空列表 `CloningExt.kt:322/331`（main `api.ts:930/940` 同结构 JS 亦抛 TypeError；唯一 UI 调用点 `CloningPanels.kt:382` 已 `frags.size<2` 拦截）。
3. `GenomicsTools.detectCpGIslands` 合并分支 `mc*mg` 极端 0 得 Infinity（与 main 同公式）；`GenomicsView:1078` 双「✓」文案前缀（非崩溃）。
4. ONNX 边角：`SpiceOnnx.conf` 形状固定 [2]，`ProteinScreen.kt:507 conf[0]` 异常模型张量下越界；`GelGenie.kt:70/71` inputNames/outputNames.first() 损坏模型边角；真机/异常模型路径静态不可达。
5. `FigureImportVector.kt:1290` chart 防御分支不可达。
6. `VirtualGel ln(agarosePercent)` 输入 0/负值得 Infinity/NaN，与 main `Math.log` 同口径；UI 默认 1.2。
7. **#129** `EnzymesVe.kt` cutType=1 Type IIS 双切酶字段未接线，上游切口分支当前不可达；`topSnip/bottomSnip!!` 对应 main enzymes.ts:256/257 TS `!` 断言，main 运行时该边界存 null 不抛、native `!!` 抛，但当前路径不可达。

### 发版
- 本轮 **无确认崩溃，未改版本号、未打 tag、未推送 release**（versionCode 98 / 1.7.64 维持）。

### 仍未覆盖（留待后续 / 真机）
- 含输入框 Dialog/Sheet 页面内挂载态；AI 对话链真机挂载态；ONNX 模型下载/推理真机路径；CollectionsBatchPanel 内嵌挂载态；EvoBlastTab 长列表滚动态；蛋白菜单下拉展开态；SettingsPanels 各 tab 真机态；Sanger/基因组学/合成生物/比对/GC 窗口完整算法输出态。
- 真机遗留：质谱/免疫 tab 真机崩溃栈；#118/#123 反向拖拽圆弧方向与跨度（三选项待 owner 拍板）；AI 对话链、ONNX 下载/推理、SPICEP 导入、含输入框 Dialog/Sheet 挂载态、CollectionsBatchPanel 内嵌挂载态、蛋白菜单下拉展开态；空输入组合期路径仍待真机复测。
- 待办（非阻塞）：补 Type IIS 双切酶 cutType/usForward/usReverse 酶库映射接线，并对上游分支 snipPosition null 边界补空安全，对齐 main 运行时不抛口径。

---

## #130 巡检记录（无确认崩溃，纯 docs，未发版）

### 环境与基线
- 无设备/模拟器（无 adb、无 /dev/kvm、无 virt flag），降级全量单测 + Compose 入口静态走查。`git fetch` 后 main HEAD 仍 `6306ef1` 无新迭代；native 起始 `7da5247`（#129 docs），工作区干净；版本号 98 / 1.7.64 未改动。
- 基线 **827 tests / 0 failures / 0 errors / 0 skipped**；`testDebugUnitTest` BUILD SUCCESSFUL（缓存命中后复核 XML 计数 827/0/0/0），`assembleDebug` BUILD SUCCESSFUL。本轮未改代码、未新增测试。

### 本轮新增通读 core（守卫核对，均判定安全）
- **Evolution.kt**：BLAST `matchLen >= k+4` 才进一致性计算，除零分母非空；NJ 树 `buildPhylogeneticTree` n==0 抛 `EvolutionException`；`activeNodes[0]/[1]` 仅在 UI `treeNames.size < 2` 拦截后到达（`AnalysisPanels.kt:1546`），n≥2；`len-2` 除数在 `while activeNodes.size>2` 内 ≥1，无除零；Shannon Logo `alignedSeqs.isEmpty()` 早退后取 `[0]`，`totalValid==0` `continue` 后才除；dotPlot 步长 `maxOf(1,…)`。对照 main `sequenceAnalysis.ts:132-252` 同构；main 面板（`SequenceEvolutionPanel.svelte:128-136`）未做 `<2` 守卫直接调用，native 1546 守卫更保守，不构成崩溃。固定 4×4 距离矩阵对 >4 条集合为 main/native 同构输入假设（非 native 引入缺口）。
- **ImageTools.kt**：`analyzeGel/countColonies` 入参 gray 均由 `toGray(bmp)` 生成，调用点 `PhotoScreen.kt:114 if (bmp != null)` 守卫内，Bitmap 尺寸 ≥1，灰度行列非空；`analyzeBlot` 无 native 调用点（预留），`laneCount <= 0` 早退、`n.coerceAtLeast(1)` 守卫；列投影 `col[average]`、连通域 `p[0]/p[1]` 均在坐标边界内。
- **MultipleAlign.kt**：`sequences.isEmpty()` 早退；`size==1` 分支内取 `[0]`；一致性 `cols>0 / total>0 / width>0` 守卫后除零；两处 UI 调用（`AnalysisPanels.kt:1727` 参考序列恒在列、`1869` 文件解析过滤后空列表由 core 早退兜底），`refIdx` 均用默认 0。
- **NgsQc.kt**：`totalReads==0` 抛业务异常；位点质量均值 `qCountAtPos[pos] > 0` 守卫后才除（99/235 两处）；`totalReads/totalBases` 除零均有 `>0` 守卫；adapter 截断 `minOf(adapter.length-1, 15)` 钳制。
- **RibosomalSlippage.kt**：命中位点后 `slipCodonsCount=floor(motifLen/3)` 次密码子循环，每次 `currIdx+3` 落在已命中的 motif 区间内（`3k ≤ L`），不越界；可视化切片 `maxOf(0,…)/minOf(seq.length,…)` 钳制，对照 main `advancedTools.ts:971-1018` 同构。观察项：UI `CloningPanels.kt:4235` 仅 catch `IllegalArgumentException`，main `RibosomalSlippagePanel.svelte:39` `catch (e:any)`；core 当前不抛越界/其他异常（唯一抛出来源是位点找不到的 `IllegalArgumentException`），类型覆盖差异记录为次要观察项，非可达崩溃。
- **SpdLibrary.kt**：`firstOrNull` 空安全；`round` 除数 `f=10^places`（places 2/3）恒 >0；网络/JSON 解析均 `runCatching`/空值兜底。
- **Structure.kt**：`cols.size <= maxOf(xIdx,yIdx,zIdx)` `continue` 守卫后才取列；**MolStarPdb.kt** 长度 `minOf(cleanSeq.length, coords.size/3)`、切片 `minOf(s+17, clean.length)` 钳制；**SyncDb.kt** 正则 3 捕获组 `find` 命中后取 `groupValues[1..3]`，`caretIdx>=0` 分支内 `removeRange` 位置合法；**FigureMermaidImport.kt** `split(...)[0]` 空行也返回非空列表；**CodonOpt.kt** `totalCodons>0` / `clean.isNotEmpty()` 守卫，`ln(maxOf(w,0.0001))` 防 ln(0)；**FigureTemplates.kt** `FIGURE_TEMPLATES` 静态 8 条非空，`firstOrNull ?: [0]` 兜底安全。

### 本轮新增走查 ui / tools（接线与兜底核对）
- **SettingsPanels.kt**：无 `!!`/first/last/索引命中；`SettingsScreen`（`Screens.kt:322-390`）依次挂载 SpdSettings / PrimerThermo / InferModel / Model / EnzymeLibraryInfo / DatabaseSync / Keymap 各 Section；语言下拉 `LANGUAGES.first{ it.first==appLang }`：`lang` 仅由 `setLang` 写入（`I18n.kt:46` 校验回退 zh），键集 7 语言全覆盖，必命中；413 PCR/引物算法未接线为 README 已披露持久化占位，非崩溃。
- **CollectionsPanel.kt**：批量引物结果行 `listOf(name, fwdSeq, fwdTm, revSeq, revTm)` 恒 5 元素（162 行），`it[0..4]`/`r[0..4]` 索引安全；DpnI 结果为 Triple 取 `.first/.second/.third`。
- **WindowPanels.kt**（窗口菜单 + 7 套特征配色）：HISTORY/LOGGER 均 `isEmpty()` 守卫后才遍历；`MapPalette.colorFor` 每个 theme 的 `when` 均有 `else` 兜底；GC 窗口算法在 core GenomicsTools（前轮已核）。
- **PresenterView.kt**：进度色标 `PROG_STOPS` 静态 5 个 RGB 三元组，插值 `i` 经 `minOf(size-2,…)` 钳制、`a/b` 恒 3 元素；`b.spans[0]` 在 `spans.size==1` 守卫内。
- **AiCopilotSheet.kt**：`validated.valid.isNotEmpty()` 守卫内 `from.first()/to.first()`（`validateMutations` 已保证长度 1）；`errors.first()` 在 `errors.isNotEmpty()` 分支内。
- **AnnotateScreen.kt:566** `decoded[0].id` 在 `decoded.isNotEmpty()` 守卫内；其余 tools 顶层屏（Report/Tools/File/Figure/CheckDialog/ImportDialog）无 `!!`/first/last/索引命中。

### 低风险遗留（损坏/极端输入，正常路径不可达，沿用清单未改动）
1. SPICEP 残缺坐标非 3 倍数越界；2. Golden Gate 空列表（UI 已拦截）；3. `detectCpGIslands` 极端 0 Infinity；4. ONNX 异常模型张量形状；5. `FigureImportVector` chart 防御分支不可达；6. `agarosePercent` 0/负值得 Infinity/NaN（与 main 同）；7. EnzymesVe Type IIS 字段未接线，上游分支不可达。
- 新增观察项（非崩溃）：滑移面板 UI 异常捕获类型窄于 main（`IllegalArgumentException` vs `catch any`）；core 不抛其他异常，待真机/后续统一放宽为 `catch Exception` 对齐 main。

### 发版
- 本轮 **无确认崩溃，未改版本号、未打 tag、未推送 release**（versionCode 98 / 1.7.64 维持）。

### 仍未覆盖（留待后续 / 真机）
- 含输入框 Dialog/Sheet 页面内挂载态；AI 对话链真机挂载态；ONNX 模型下载/推理真机路径；CollectionsBatchPanel 内嵌挂载态；EvoBlastTab 长列表滚动态；蛋白菜单下拉展开态；SettingsPanels 各 tab 真机态；Sanger/基因组学/合成生物/比对/GC 窗口完整算法输出态。
- 真机遗留：质谱/免疫 tab 真机崩溃栈；#118/#123 反向拖拽圆弧方向与跨度（三选项待 owner 拍板）；AI 对话链、ONNX 下载/推理、SPICEP 导入、含输入框 Dialog/Sheet 挂载态、CollectionsBatchPanel 内嵌挂载态、蛋白菜单下拉展开态；空输入组合期路径仍待真机复测。
