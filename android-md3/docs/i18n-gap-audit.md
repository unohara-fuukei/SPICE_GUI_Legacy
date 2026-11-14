# i18n 词典缺口审计（main 文案 vs android UI 文案）

> 生成时间：2026-11-14。方法：以 main 分支代码**实际调用**的中文词条
> （paraglide `m.<id>`、工具目录 `tm.<id>`、Rust 后端 `i18n:<key>` 令牌，共 3449 条去重文本）
> 与 android 全部 Kotlin 源的中文字面量 + `I18n.t()/tf()` 包装键做全文匹配。
> 标点/空格/省略号变体已归一化（精确缺失 3067 → 归一化后 3055 → 其中静态串 2551、模板串 504）。
> **注意口径**：词条未出现 ≠ 功能缺失。android 移植中存在自拟替身文案的已实现功能（如菌落页），
> 以及本来就未移植的整块功能面。本表定位的是"main 词典覆盖 = 功能覆盖"这个强信号的缺口。

## 一、总量

| 指标 | 数值 |
|---|---|
| main 词典总词条（7 语 × 3917） | 3917 |
| main 代码实际调用（文本去重） | 3449 |
| 安卓源中出现（精确） | 382 |
| 安卓未出现（归一化后） | 3055 |
| — 其中无占位符静态串 | 2551 |
| — 其中含 `{n}`/`{v1}`/`%f` 模板串 | 504 |

## 二、按 main 文件的功能面缺口

按"该文件使用的中文词条中，安卓缺失数量"排序。缺失率 = 缺/总。

### A. 工具页（tools 路由）
| main 文件 | 用词 | 安卓有 | 缺 | 判断 |
|---|---:|---:|---:|---|
| tools/gel/GelEditor.svelte | 232 | 15 | 217 | 安卓 GelScreen 为"迁移模拟"替身，非 main 完整凝胶编辑器 |
| tools/blot/BlotTool.svelte | 146 | 15 | 131 | BlotScreen 简化，缺定量/背景模式/归一化/图版导出 |
| tools/qpcr/QpcrTool.svelte | 139 | 9 | 130 | QpcrScreen 简化，缺标准曲线详情/样本分配/批量 |
| tools/plate/PlateTool.svelte | 137 | 14 | 23 | PlateScreen 简化，缺规则校验器/映射导入 |
| tools/annotate/AnnotateTool.svelte | 137 | 19 | 118 | AnnotateScreen 为排版预览替身，缺标注交互 |
| tools/colony/ColonyTool.svelte | 98 | 7 | 91 | ColonyScreen 为 CFU 换算替身，缺图像计数/ONNX 检测/导出 |

### B. 基因页 panels（src/lib/gene）
| main 文件 | 用词 | 缺 | 判断 |
|---|---:|---|---|
| CloningSimulator.svelte | 70 | 70 | 克隆模拟器（4 种方法 + 连接产物）未移植 |
| ReactionCalculators.svelte | 76 | 76 | 反应计算器（Ligation/MasterMix/稀释/退火）未移植 |
| CloningExtensionPanel.svelte | 65 | 64 | 克隆扩展面板：多片段组装/验证/产物 |
| CollectionsBatchPanel.svelte | 66 | 63 | 集合批量面板未移植 |
| CrisprDesigner.svelte | 58 | 57 | CRISPR 设计器未移植 |
| ProteinViewPanel.svelte | 52 | 52 | 蛋白视图面板（物化/折叠/功能域）未移植 |
| SyntheticBiologyCadPanel.svelte | 55 | 52 | 合成生物 CAD 未移植 |
| SyntheticGeneQc.svelte | 49 | 48 | 合成基因 QC 未移植 |
| PcrSimulationPanel.svelte | 43 |  42 | PCR 模拟面板未移植 |
| EnzymeSelector.svelte | 42 | 38 | 酶选择器（自定义酶组管理）不全 |
| AdvancedGenomicsPanel.svelte | 37 | 37 | 高级基因组学面板未移植 |
| CloningValidator.svelte | 36 | 36 | 克隆验证器未移植 |
| synBio.ts（元件库） | 36 | 36 | 合成生物元件库数据未移植 |
| VectorTemplatePanel.svelte | 34 | 28 | Vector 模板发布面板不全 |
| PrimerLimsPanel.svelte | 28 | 27 | 引物 LIMS 面板未移植 |
| NgsGenomicsPanel.svelte | 27 | 26 | NGS 基因组学面板不全 |
| CodesignParetoPanel.svelte | 25 | 24 | 协同设计 Pareto 面板未移植 |
| RibosomalSlippagePanel.svelte | 25 | 24 | 核糖体移码面板未移植 |
| ReverseTranslationPanel.svelte | 24 | 21 | 逆翻译面板未移植 |
| VirtualGel.svelte | 10 | 10 | 虚拟凝胶未移植 |
| CodonOptimizer.svelte | 8 | 8 | 密码子优化器未移植 |
| ExpressionCassetteBuilder.svelte | 19 | 12 | 表达盒构建器不全 |
| HistoryTree.svelte | 11 | 10 | 历史树不全 |
| DnaViewer.svelte | 20 | 17 | DNA 查看器 strings 不全 |

### C. 顶级路由页
| main 文件 | 用词 | 缺 | 判断 |
|---|---:|---:|---|
| routes/gene/+page.svelte | 596 | 482 | 基因页顶层:大量状态/会话/通知串 |
| routes/protein/+page.svelte | 288 | 243 | 蛋白页顶层大量文案 |
| routes/settings/+page.svelte | 161 | 136 | 设置页（快捷键录制/存储诊断等） |
| routes/project/+page.svelte | 71 | 58 | 项目中心 |
| src/lib/ui/AiCopilot.svelte | 163 | 139 | AI 协作界面文案（工作区不匹配等） |
| src/lib/ui/aiState.svelte.ts | 49 | 47 | AI 工具链消息文案 |
| MolStarViewer.svelte | 33 | 31 | Mol* 3D 查看器（表示/导出 PNG）未移植 |
| backend/api.ts + preprocess.ts | 27 | 27 | Rust 后端调用层文案 |
| spice.rs / cloning.rs（Rust） | 68 | 67 | Rust 后端核心层文案 |

## 三、结论与建议

1. **强信号**：main 3449 条在用词条中安卓仅命中 382 条（11%）。其中相当比例（菌落/电泳/印迹/qPCR/孔板/标注 6 个工具页）是"自拟替身文案"——功能存在但文案漂移，需按 main 原文替换。
2. **未移植功能面清单**（以词典缺口倒推）：克隆模拟器、反应计算器、CRISPR 设计器、合成生物 CAD、合成基因 QC、蛋白视图面板、引物 LIMS、高级基因组学、协同设计 Pareto、核糖体移码、虚拟凝胶、密码子优化器、Mol* 查看器、PCR 模拟面板、克隆验证器、集合批量面板等。
3. **后续任务建议**：将本表作为 #54 后续批次的工作底稿：每个功能面按 main 源文件逐个 1:1 移植，词典先行（词条已在 core 词典中，零翻译成本），UI 跟上。
