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
