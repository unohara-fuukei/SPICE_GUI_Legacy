# SPICE 原生 Android 版（Kotlin + Jetpack Compose + Material 3）

纯原生渲染，**零 WebView 依赖**，作为 `native` 分支的 Kotlin 实现对照（同分支另有一份 Rust + egui 的桌面/Android 移植）。

## 构建

```bash
export JAVA_HOME=<jdk17>
export ANDROID_HOME=<android-sdk>
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

- `applicationId = com.spice.nativeapp`，`minSdk 24`，`targetSdk 29`（Android 10 可直接安装）
- 签名通过环境变量 `SPICE_KS` / `SPICE_KSPASS` / `SPICE_ALIAS` 注入，缺省读取本机 release keystore

> 若处于 TLS 中间人代理网络，需要给 Gradle JVM 指定信任库：
> `-Djavax.net.ssl.trustStore=... -Djavax.net.ssl.trustStorePassword=...`

## 已实现

| 模块 | 内容 |
|---|---|
| 外壳 | MD3 Scaffold + NavigationBar（基因/蛋白/克隆/项目/设置）、TopAppBar、深/浅主题、edge-to-edge |
| 序列引擎 | FASTA/GenBank 文本清洗、反向互补、GC 含量、标准密码子表翻译、六框翻译、最长 ORF |
| 酶切引擎 | 46 种常用限制酶目录（IUPAC 模糊匹配、环状跨起点），切点/片段/缓冲液兼容双酶切 |
| 环状图谱 | Canvas 绘制骨架 + 特征弧（按类型着色）+ 250/500/2000 bp 刻度 + 交替半径酶切刻度 + 拖动旋转 + 点选特征 |
| 线性图谱 | 特征分道色块 + 刻度尺 + 酶切竖线 |
| 序列视图 | 60 bp/行、10 bp 分隔、碱基着色、酶切位点标记 |
| 蛋白 | 分子量 / pI（EMBOSS pKa 二分）/ pH7 净电荷 / GRAVY / A280、KD 疏水窗口 Top5、ORF 摘要 |
| 克隆 | PCR 引物设计（最近邻 Tm + Wallace 回退、GC clamp、二聚体粗筛）、产物长度、Gibson 同源臂、Golden Gate 位点筛查、ng→fmol |
| 项目 | 本地 JSON 持久化（filesDir），保存/打开/删除，环状/线性标记与时间戳 |

## 尚未实现（与 WebView 版差异）

- ONNX 蛋白结构预测与 3D 折叠（WebView 版依赖 onnxruntime + 模型文件，原生版未接）
- AI Copilot 对话
- 图像类工具：图形工作室 / 图像标注 / 凝胶成像 / 斑点杂交 / qPCR / 菌落计数 / 96 孔板 / 系统树
- 文件导入导出（FASTA/GenBank 文件选择器、PDF/PPT 导出）

以上均属后续可增量移植项；当前版本聚焦「看图谱 + 查序列 + 算性质 + 存项目」的核心闭环。
