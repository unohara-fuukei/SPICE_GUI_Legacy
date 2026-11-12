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

## 0.9.0 — 补齐原版「文件 / 编辑」菜单 + 基因区域选区与浮动窗口

- **文件 / 编辑 菜单（对应原版 retro menu bar）**：基因页顶部新增 `文件`「打开序列文件…」「导出当前序列 (GenBank)…」，`编辑`「反向互补序列」「切换拓扑（环状/线性）」「清除选择区」「复制选区序列」；均为 Material 3 `DropdownMenu`，菜单项 48dp。
- **基因区域可选**：视图切换行新增 `选择` 开关，开启后在图谱上直接拖拽即可拉出区间（环状按角度、线性按横坐标换算 bp），关闭则恢复拖动旋转；环状图谱选中区间以加粗弧带高亮，线性图谱以半透明色块 + 边界线高亮。
- **选区浮动窗口**：选中后地图底部浮出 `ElevatedCard`（`zIndex` 置顶，不透明背景），显示 `起始..结束 · 长度 bp`、`GC% · Tm`（≤14 bp 用 Wallace 规则，否则 GC 比例公式），并提供 `复制 / RevComp / 删除 / 取消` 四个 48dp 按钮；RevComp 与删除直接改写序列并回写编辑器状态。
- **窄屏 chip 行改横滑**：视图切换行加 `horizontalScroll` + `maxLines = 1`，修复 320dp 宽下「序列」被压成两行的问题。

