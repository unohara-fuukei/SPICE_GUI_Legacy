# SPICE 原生 Android 版（Kotlin + Jetpack Compose + Material 3）

纯原生渲染、零 WebView，作为 `native` 分支上的 Kotlin 实现（同分支另有 Rust + egui 版本）。

## 构建

```bash
export JAVA_HOME=<jdk17>
export ANDROID_HOME=<android-sdk>
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

- `applicationId com.spice.nativeapp`，`minSdk 24`，`targetSdk 29`
- 签名经 `SPICE_KS / SPICE_KSPASS / SPICE_ALIAS` 注入
- 处于 TLS 中间人代理网络时需给 Gradle JVM 指定信任库（`-Djavax.net.ssl.trustStore=...`）
- AGP 9 需 `android.builtInKotlin=false` 才能使用经典 Kotlin 插件

## 功能覆盖（对照 WebView 版）

| WebView 原版 | 原生实现 | 状态 |
|---|---|---|
| 底部导航 / 顶栏 / 状态 | MD3 Scaffold + NavigationBar 六 Tab | 一致 |
| 环状质粒图谱（缩放/平移/点选） | Canvas 骨架 + 特征弧 + 自适应刻度 + 酶切刻度 + 拖动旋转 + 点选 | 基本一致（无滚轮缩放，改为拖动旋转） |
| 线性图谱 | 特征分道 + 刻度尺 + 酶切竖线 | 一致 |
| 序列视图（60bp/行、着色、切点） | LazyColumn 单行 AnnotatedString | 一致 |
| 酶切分析（800+ 酶） | 46 种常用酶 + IUPAC + 环状跨起点 | 酶库子集 |
| 双酶切推荐 | 缓冲液兼容配对 | 一致 |
| PCR 引物设计 | 最近邻 Tm + GC clamp + 二聚体粗筛 + 产物长度 | 一致 |
| Gibson / Golden Gate | 同源臂 + BsaI 位点筛查 | 一致 |
| 蛋白理化性质 | MW / pI / pH7 电荷 / GRAVY / A280 | 一致 |
| 蛋白 3D 结构 | 螺旋+β 启发式骨架，可拖动旋转 | **非 ONNX 预测** |
| ONNX 折叠推理 | — | 未实现 |
| AI Copilot | 本地规则建议（GC/同聚物/单酶切/ORF/下一步） | **无云端模型** |
| 凝胶工具 | 酶切片段 log10 迁移模拟 + 3 套 ladder + 片段表 | 模拟版（无照片条带检测/拟合） |
| qPCR 工具 | ΔΔCt 相对定量 + 效率自检 | 计算版（无板式 Ct 表导入/统计绘图） |
| Western blot | 泳道强度编辑 + 内参归一化 + Canvas 渲染 | 示意版（无照片分析） |
| 96 孔板 | 8×12 网格布板 + 四类计数 | 一致（无导出模板） |
| 系统树 | 多序列 FASTA + 4-mer Jaccard 距离 + UPGMA 树图 | 简化版（无多序列比对/MSA 算法） |
| 菌落计数 | 96 格手工点选 + CFU/mL 换算 + CSV | 手工版（无照片自动识别） |
| 图形工作室 | 盒/标签/箭头示意图拼版 + 描述导出 | 示意版（无位图导入/PDF） |
| 图像标注 | 1/2/4/6 面板排版 + 比例尺 + 图注 | 排版版（无位图导入） |
| 文件导入导出 | SAF 打开 FASTA/GenBank + 剪贴板 + 分享 | 一致 |
| 项目中心 | filesDir JSON：保存/打开/删除 | 一致（无多文档编辑/附件/历史树） |

## 已知缺口（下一步）

1. **ONNX 结构预测**：需集成 onnxruntime-android + 模型权重（约 100 MB），并做 NNAPI/CPU 回退。
2. **AI Copilot 联网**：需 API Key 管理与流式对话 UI。
3. **图像类工具的照片识别**：凝胶条带检测/拟合、菌落自动计数、blot 定量，需要图像处理管线（OpenCV 或自研阈值+连通域）。
4. **导出**：PDF/PPT 版式输出、图形工作室位图渲染。
5. **项目中心进阶**：多文档编辑器、操作历史树、附件管理与演示模式。
