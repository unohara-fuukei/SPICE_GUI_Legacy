# SPICE GUI — 原生移植路线图（native 分支）

本分支的目标：将 SPICE GUI 从 **WebView/Tauri 混合架构** 迁移为**纯原生应用**（零 WebView 依赖），并最终实现与 `main` 分支（WebView 版）功能的**一比一复刻**。

## 技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| 语言 | Rust | 直接复用既有 `spice_engine` Rust 后端（序列/酶切/克隆逻辑零重写） |
| UI | egui / eframe 0.29 | 纯 CPU/GPU 即时模式渲染，无浏览器引擎；跨 Windows/macOS/Linux/Android |
| 字体 | Noto Sans SC 子集（GB2312 全集） | 内嵌，无系统字体依赖 |
| 文件对话框 | rfd 0.13（optional feature `file-dialog`） | 默认构建不启用，保证最小依赖可在无 GTK/portal 环境编译；未启用时用「从文本导入序列」窗口替代 |

构建：

```bash
cd native
cargo run --release                 # 桌面运行（内置 pUC19 演示质粒）
cargo build --features file-dialog  # 启用系统文件对话框
```

> 说明：rfd 的 xdg-portal 后端在部分工具链下存在 zbus 5.19 兼容性问题，gtk3 后端需要系统开发库，因此默认关闭。路线图 P5（Android 打包）阶段会替换为平台原生 chooser。

## 当前进度（基线已可用）

- ✅ 应用外壳：五大菜单（文件 / 编辑 / 分子克隆工具 / 分析与多视图 / 窗口与设置）、标签页、状态栏、深色主题、中文字体
- ✅ 序列引擎：FASTA / GenBank 解析、反向互补、GC 含量、标准密码子表翻译、ORF 查找
- ✅ 酶切引擎：35 常用限制酶目录、IUPAC 模糊匹配、环形质粒跨起点搜索
- ✅ 视图：
  - **环状图谱**：双圈骨架、250bp 刻度 / 500bp 数字、特征彩色弧、酶切位点刻度（交替半径防重叠）、中心质粒信息
  - **线性图谱**：酶 tick 两行交错、特征块 + 链方向箭头、500bp 刻度尺（501/1001… 风格，与 WebView 版一致）、选中区域高亮
  - **序列视图**：60bp/行、每 10bp 分隔、碱基着色、酶切位点高亮背景、拖选、可选 +1 框翻译
  - **酶切面板**：过滤、全选常用、切点计数
  - **注释信息**：特征表、反向互补预览
- ✅ P1 分子克隆全套：PCR 引物设计、双酶切向导、Gibson 装配、Golden Gate 筛查、琼脂糖电泳模拟（详见下方路线图）
- ✅ 演示数据：内置 pUC19 (2686 bp, NCBI L09137.2) 及 lacZα / MCS / bla / ori 特征

## 路线图

### P1 — 分子克隆全套 ✅ 已完成（2026-11）
- [x] PCR 引物设计（Tm 计算：Wallace/GC 双公式、GC clamp、自/交叉二聚体检查、产物长度；可用选中区域一键填充）
- [x] Gibson 装配（同源臂 15–40 bp 可调、四引物设计含载体同源尾、装配子长度与接头预览）
- [x] Golden Gate 装配（BsaI/BsmBI/BbsI 位点筛查、5' 突出接头、共享接头融合对、AT 含量质量评估）
- [x] 琼脂糖电泳模拟（新「电泳模拟」标签页：1 kb-plus ladder、每酶泳道 + 全酶切泳道、对数刻度条带、亮度 ∝ 质量）
- [x] 双酶切方案向导（1–2 切点酶两两组合、缓冲液兼容建议）
- 单元测试：`cargo test`（10 个用例，覆盖 Tm/引物/Gibson/电泳片段/双酶切）
- 对应 WebView 版菜单：分子克隆工具

### P2 — 蛋白页
- [ ] 原生 3D 结构查看器（Mol* → wgpu 生态替代）
- [ ] ONNX 推理复用（结构预测模型直连）
- [ ] 序列→蛋白多视图联动

### P3 — 项目中心
- [ ] 文档编辑器（富文本 → 纯原生编辑组件）
- [ ] 实验记录、日志页、附件管理
- [ ] 演示模式

### P4 — AI copilot
- [ ] 内嵌推理 / 远端 API 调用（引物建议、方案审查）

### P5 — Android 打包
- [ ] cargo-ndk 交叉编译 arm64-v8a
- [ ] Android `targetSdk 29` 兼容验证（与 WebView 版对齐）
- [ ] 触屏手势（捏合缩放图谱、长按选择）
- [ ] 原生文件选择器（Android Intent chooser）

## 与 main 分支的关系

- `main`：WebView 版（Tauri + 前端），Android 10 / WebView 75 已完整适配，APK 见 Release v0.0.9-webview75
- `native`：本分支，长期演进，目标为同一功能集的纯原生实现
