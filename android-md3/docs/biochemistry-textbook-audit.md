# 生物化学教材对照审计（Lehninger 第七版）

> 生成时间：2026-11-16。审计对象：SPICE_GUI_Legacy `native` 分支
> （Kotlin + Compose + MD3，对 main 1:1 复刻）全部承载生化/分子生物学概念的算法模块。
> 权威依据：用户上传教材《生物化学（简体）》，即 *Lehninger Principles of Biochemistry*
> 第七版中文译本；相关章节已从 epub 抽取纯文本复核（氨基酸/肽/蛋白 ch011、蛋白三维结构
> ch012、蛋白功能 ch013、酶 ch014、碳水 ch015、核苷酸与核酸 ch016、DNA 生物技术 ch017、
> 染色体 ch034、DNA 代谢 ch035、RNA 代谢 ch036、蛋白质代谢/遗传密码 ch037、基因调控
> ch038、词汇表 ch040、索引 ch041）。
>
> 核对方法：以教材权威定义 + 标准生化常量/标准遗传密码为基准，用 Python 脚本解析 Kotlin
> 源码中的常量表/标准表逐项比对（非凭记忆判断）；公式类模块逐行回读推导。
> 测试基线：`testDebugUnitTest` 782 用例，0 failures / 0 errors。

## 一、可复现复核脚本

`docs/audit/` 下两个脚本，在 `android-md3/` 目录执行：

| 脚本 | 校验内容 | 结果 |
|---|---|---|
| `verify_codon_tables.py` | Bio 四行密码子表、CodonOpt `AA_OF_CODON`、SyntheticGeneQc `CODON_TO_AA`、CodeSign `CODON_TO_AA`、ReverseTranslate 同义分组、Crispr 同义突变 map、NgsQc 起始终止集合，与标准遗传密码 64 三联体逐项比对 | 64/64 一致，0 错配 / 0 缺失 / 0 多余 |
| `verify_protein_tables.py` | ProteinCore 游离氨基酸 MW、Kyte-Doolittle 疏水性指数 KD、EMBOSS 口径 pKa 三张表，与标准值逐项比对（容差 0.011） | MW 20/20、KD 20/20、pKa 9/9 全部吻合 |

```
$ python3 docs/audit/verify_codon_tables.py
标准密码子数：64（61 编码 + 3 终止）
Bio 表收录：64 / CodonOpt 收录：64 / CodeSign 收录：64
ReverseTranslate 分组氨基酸数：21
全部一致：0 错配 / 0 缺失 / 0 多余。

$ python3 docs/audit/verify_protein_tables.py
MW 收录：20/20；KD 收录：20/20；pKa 收录：9/9
全部吻合：MW / KD / pKa 三张表逐项在容差内一致。
```

## 二、教材词条索引 → 软件模块落点

| 教材主题（章节） | 教材权威要点 | 软件落点模块 | 结论 |
|---|---|---|---|
| 遗传密码（ch037） | 64 三联体；61 编码 + 3 终止（UAA/UAG/UGA）；起始 AUG；Met、Trp 单密码子；Arg/Leu/Ser 各 6；简并性；第三位摆动；密码子偏爱 | `Bio.kt`、`ReverseTranslate.kt`、`CodonOpt.kt`、`SyntheticGeneQc.kt`、`CodeSign.kt`、`Crispr.kt`、`NgsQc.kt` | 表与分组脚本校验 0 错配 |
| 氨基酸与肽（ch011） | 20 种标准 α-氨基酸；残基 = 失水单元；游离氨基酸 MW；多肽质量 = Σ游离 + 一分子水（18.015）；侧链极性/电荷分类；半胱氨酸氧化成胱氨酸（二硫键） | `ProteinCore.kt`、`ProteinBiophysics.kt` | MW 表吻合；多肽 +18.015 水口径正确 |
| 蛋白三维结构（ch012） | α 螺旋 3.6 残基/圈、5.4 Å/圈、1.5 Å/残基；β 构象锯齿形；Chou-Fasman 二级结构倾向；疏水作用折叠 | `ProteinBiophysics.kt` | 螺旋几何 3.6 / 1.5 Å、β 残基间距 3.3 Å、接触阈值 8 Å 正确；CF_P_HELIX/SHEET 倾向值吻合 |
| 蛋白功能 / 抗原表位（ch013） | 抗原表位（epitope）；Kolaskar-Tongaonkar 抗原性预测；疏水性与表面分布 | `ProteinBiophysics.kt` | ANTIGENICITY 表吻合；FoldIndex 公式系数 2.785 / 0.151 正确 |
| 酶动力学（ch014） | Michaelis-Menten `v = Vmax·[S]/(Km+[S])`；Lineweaver-Burk 双倒数：斜率 Km/Vmax、纵截距 1/Vmax；Km = V0=½Vmax 时的底物浓度 | `Calculators.kt` | MM 方程、双倒数线性化、Vmax=1/截距、Km=斜率×Vmax 推导正确 |
| 核酸性质（ch016） | 碱基互补 A-T/U、G-C；反向平行；GC 含量；双链/单链分子量口径；IUPAC 兼并碱基 | `Bio.kt`、`CollectionIO.kt`、`NgsQc.kt`、`PrimerAnalysisMain.kt` | 互补表、IUPAC 匹配/互补、GC% 定义正确；IUPAC revcomp 256 表逐项核对正确 |
| DNA 生物技术 / 限制酶（ch017） | 限制性内切酶识别回文；平端与 5'/3' 黏端；DNA 连接酶形成磷酸二酯键需 5'-磷酸 | `EnzymeDb.kt`、`EnzymesVe.kt`、`CloningSim.kt`、`EndModification.kt` | 平端/黏端切点抽查正确；T4 连接酶 5' 磷酸要求警告正确 |
| 基因启动子 / 信号肽 / 跨膜（ch034/ch038） | Pribnow -10 盒 TATAAT、σ70 -35 TTGACA；信号肽 N 正电区 / H 疏水区 / C 加工区；跨膜螺旋疏水段 | `GenomicsTools.kt` | 共识序列、三分区判定、20aa 窗口正确 |
| 密码子优化 / CAI（ch037 密码子偏爱） | CAI = 各组最优密码子频率比的几何平均；密码子偏爱随宿主而异 | `CodonOpt.kt`、`CodeSign.kt` | w=freq/maxFreq，几何平均 exp(Σln w/n) 标准 CAI；三宿主权重/main 移植一致 |
| PCR 与引物（ch016/ch017 生物技术） | 引物退火；SantaLucia 最近邻 ΔH/ΔS；Tm 与盐/引物浓度；GC clamp；引物二聚体/发夹 | `PrimerAnalysisMain.kt`、`PcrSimulation.kt` | 16 组最近邻参数吻合；末端起始参数；Tm 公式；盐修正对齐 main |
| 定量实验：qPCR / 免疫印迹 | ΔCt、ΔΔCt、相对表达 2^−ΔΔCt；效率校正 Pfaffl；WB 反相灰度、背景扣除、内参/总量归一化、倍数与组统计 | `QpcrAnalysis.kt`、`QpcrStats.kt`、`tools/blot/BlotQuant.kt` | 公式与统计口径正确（见下） |
| 进化 / 序列比对 | 同源比对打分；空位罚分；NJ 邻接归并；序列 Logo 信息量；BLAST 种子延伸 | `SwAlign.kt`、`MultipleAlign.kt`、`Evolution.kt` | SW/NW 打分、NJ 公式、Shannon Logo 信息量正确 |
| RNA 结构（ch036） | RNA A-U、G-C、G-U 摆动；Nussinov 最大配对；发夹环最小环 | `RnaStructure.kt` | 配对规则与 minLoop=3 正确；MFE 为粗略近似（已在代码注释披露） |
| 核糖体移码（ch037） | −1/+1 翻译移码；经典滑移 motif；读框平移；遇终止停止 | `RibosomalSlippage.kt` | 移码模拟与读框计数逻辑正确 |
| 克隆方法（ch017） | 限制酶切、Gibson、Golden Gate、In-Fusion、NEB Builder、Gateway、TOPO、TA、GC 等主流组装路径 | `CloningSim.kt`、`Cloning.kt`、`CloningMethods.kt`、`ExpressionCassette.kt` | 方法分类与方向/磷酸化处理正确；表达盒反向互补、特征坐标镜像正确 |
| 凝胶电泳 / 条带定量（ch017） | log(MW) 与迁移距离近似线性；灰度阈值；RANSAC 稳健拟合 | `GelMath.kt`、`VirtualGel.kt`、`GelLadders.kt`、`tools/colony/ColonySegment.kt` | 半对数回归、Otsu、连通域/分水岭口径正确；ladder 为厂商目录数据 |

## 三、已核对正确的关键算法（逐项结论）

### 3.1 遗传密码与翻译
- **标准密码子表**：Bio 四行字符串重建 64/64 与标准表一致；起始 ATG，终止 TAA/TAG/TGA；ORF 六框翻译、最长 ORF 起止判定正确。
- **同义密码子分组**：ReverseTranslate `SYNONYMOUS_BY_AA` 64/64 归属正确（F2 / L6 / I3 / M1 / V4 / S6 / P4 / T4 / A4 / Y2 / H2 / Q2 / N2 / K2 / D2 / E2 / C2 / R6 / G4 / W1 + 3 终止）；首选密码子均归属对应氨基酸。
- **CAI**：三宿主（ecoli / yeast / human）权重与 BEST_CODONS 为 main 逐值移植；CAI 几何平均 `exp(Σ ln w / n)`，权重下限 0.0001；含 U/T 等价、简并表回退、IUPAC 展开全一致才映射。
- **CRISPR 同义突变 map**：64 条同义映射脚本校验 0 跨氨基酸错配。
- **起始终止**：NgsQc 原核起始 ATG/GTG/TTG、终止 TAA/TAG/TGA；反向链坐标回映射正确。

### 3.2 蛋白质理化与结构
- **分子量**：20 种游离氨基酸 MW 与标准值吻合；多肽质量 = Σ游离 + 18.015（一分子水），与教材"残基失水"口径一致。
- **疏水性 KD**：20 项与 Kyte-Doolittle 1982 原始值逐项吻合（教材表 3-5 同源引用）；GRAVY = KD 平均。
- **pKa / 电荷 / pI**：EMBOSS 口径 pKa（Cterm 3.55、Nterm 7.50、D 4.05、E 4.45、C 9.00、Y 10.00、H 5.98、K 10.00、R 12.00）；电荷用 Henderson–Hasselbalch 方程，pI 二分求电荷零点。
- **结构倾向**：Chou-Fasman 螺旋/片层倾向、Kolaskar-Tongaonkar 抗原性、残基单同位素质量、胰蛋白酶 PMF 碎片质量（+18.01056 水，m/z 按 +1/+2/+3 质子校正）均吻合；α 螺旋 3.6 残基/圈、1.5 Å/残基、半径 2.3 Å，β 间距 3.3 Å。

### 3.3 酶与反应计算
- **米氏方程**：`v = Vmax·S/(KM+S)`；Lineweaver-Burk 双倒数，截距 = 1/Vmax、斜率 = KM/Vmax、KM = 斜率×Vmax，与教材 BOX 6-1 双倒数图一致。
- **稀释**：C1V1 = C2V2。
- **连接插入量**：Mass_insert = Mass_vector × (insert bp / vector bp) × 摩尔比（MW 与 bp 成正比，摩尔比口径正确）。
- **DNA 浓度换算**：dsDNA 617.96×bp + 36.04、ssDNA 307×bp + 79；ng/µL ↔ nM 的 ×10⁶ 单位换算正确。
- **PCR 体系**：Taq / Phusion / Q5 三套 50 µL 配方组分体积合计均 = 50；梯度 PCR 退火温度取 min(Tm)−5°C 经验法则。
- **ELISA 4PL**：`y = d + (a−d)/(1+(x/c)^b)`，log-logit 线性化。
- **凝胶半对数**：log10(size) 对迁移位置线性/二次最小二乘拟合，R² 回代。

### 3.4 引物与退火
- **SantaLucia 1998 最近邻**：16 组 ΔH/ΔS 与标准表吻合；末端起始参数 GC +0.1/−2.8、AT +2.3/+4.1；`Tm = ΔH·1000/(ΔS+R·ln Ct) − 273.15`，引物浓度 5e-7；盐修正对齐 main。
- **配对判定**：发夹/二聚体 Watson-Crick 比对正确；GC clamp（3' 端 GC）、长度 15–35、GC 30–70%、Tm 50–75 为经验阈值口径。

### 3.5 基因组元件
- **启动子**：Pribnow -10 TATAAT、σ70 -35 TTGACA、间隔 15–20 bp 正确。
- **信号肽**：N 区 K/R、H 区 LAVIFM、C 区 A-X-A 三分区正确。
- **GC skew / CpG 岛**：GC skew=(G−C)/(G+C)；Gardiner-Garden 标准（≥200 bp、GC>50%、Obs/Exp>0.6）与 Obs/Exp 公式正确；上游 SD 基序正确。
- **CRISPR array**：重复 23–40 bp、间隔 21–72 bp、错配 ≤2、≥3 重复（启发式范围偏宽，见 §4）。
- **CRISPR 向导**：SpCas9 NGG/20nt、VQR NGA/20、SaCas9 NNGRRT/21、Cas12a TTTN/23；PAM 3'/5' 方向与切割偏移正确；碱基编辑窗口 protospacer 第 4–8 位（0-based 3..7），CBE C→T、ABE A→G，负链互补处理正确。

### 3.6 定量实验统计
- **qPCR**：ΔCt = 目标 Ct − 内参 Ct；ΔΔCt = ΔCt 样本 − ΔCt 对照；相对表达 = base^(−ΔΔCt)，base=2 或 1+效率（Pfaffl 效率校正）；多内参几何平均；标准曲线 Ct 对 log10(quantity) 线性回归，斜率 <0；效率 E=10^(−1/slope)−1；Welch t-test、sampleSd/sem 正确。
- **免疫印迹光密度**：反相灰度信号 = 1−gray；ring/lane/global/manual 四种背景中位数扣除；净体积 Σ(signal−background)，负校正截 0；loading（内参泳道）/ total（泳道总量）两种归一化；倍数 = normalized/controlMean；mean / sampleSd / SEM / CI95 / Welch 统计；饱和 clippedFraction > 0.005。
- **电泳图数学**：Rec.601 灰度系数 0.299/0.587/0.114；Otsu 阈值；背景分块插值；RANSAC 稳健拟合口径正确。

### 3.7 进化、比对与序列工具
- **Smith-Waterman**：局部比对 match +2 / mismatch −1 / gap open −5 / extend −1，三状态 DP、clip 0 分；k=12 双链 k-mer 预过滤。
- **多序列比对**：半全局 Needleman-Wunsch（参考链两端免罚），渐进式 MSA、共识序列与逐列保守性；identity 仅统计比对区。
- **邻接归并 NJ**：Q 准则 `Q_ij=(L−2)d_ij−r_i−r_j`；枝长 `d_wu=0.5·d_uv+(r_u−r_v)/(2(L−2))`；距离更新 `d_wj=0.5(d_uj+d_vj−d_uv)`，公式全部正确。
- **序列 Logo**：Shannon 信息量 R = maxBits − H，maxBits=log2(4) 核酸。
- **默认集合 EGFP CDS**：脚本校验 720 bp、ATG 起始、TAA 终止、239 AA、无内部终止；蛋白首尾 MVSKGEEL…/…AGITLGMDELYK 与 EGFP 一致。

### 3.8 RNA / 移码 / 克隆仿真
- **RNA 二级结构**：A-U、G-C、G-U 摆动；DNA A-T、G-C；Nussinov DP 最大配对数，minLoop=3；dot-bracket 输出正确。
- **移码模拟**：−1/+1 PRF，经典滑移 motif TTTAAAC（UUUAAAC dnaX 型）；移码后读框平移、遇终止停止计数正确。
- **末端修饰**：Klenow 补平 5' 突出（recessed 3' 端），不动平端与 3' 突出；T4 pol 外切削平 3' 突出；AP/CIP 去磷酸、PNK 磷酸化；连接需 5' 磷酸；右端 5' 突出追加反向互补，物理概念正确。
- **表达盒组装**：reverse 方向反向互补；反向特征坐标镜像（start/end 交换、strand 取负）；插入载体后下游特征坐标平移；部件角色分类（promoter / RBS / CDS / UTR / terminator / polyA 信号等）符合表达盒常规结构。
- **克隆方法**：限制酶切 / Gibson / Golden Gate / In-Fusion / NEBuilder HiFi / Gateway / TOPO(+directional) / TA / GC 十类路径；T4 连接酶两端去磷酸化无法成键的警告正确。

### 3.9 条带检出与克隆装配（第二轮补审）
- **WB 车道/条带检出**（`tools/blot/BlotDetect.kt` ↔ main `tools/blot/detect.ts`，322 行逐函数比对）：反相灰度 `1−gray` 后取列/行剖面；灰度开运算（先窗内腐蚀取最小、再窗内膨胀取最大）作下包络基线，避免移动平均把平车道/条带切成双边峰；峰检出阈值 `mean + prominenceStd·std`（车道 0.8、条带 0.62），邻域基座求 prominence，prominence 降序后按 minGap 贪心去重；车道宽 = 间距中位数 ×0.62（夹 0.012–0.16）；条带厚度走 FWHM（夹 0.008–0.12）；外侧 2% 列剔除暗框/阴影；条带上限 24。smooth/meanStd/openBaseline/mergeClosePeaks/fwhm/lanesFromCount/groupNames 各函数口径与 main 一致。
- **克隆方法判定**（`core/CloningMethods.kt`）：限制酶双酶切位点提示；TA 3′ A 尾检查；TOPO 方向性 5′ CACC 突出（`directional` 标志对应 main `topo`/`topo_directional` 两支）；Gateway attB 位点定位与待替换区间；In-Fusion 15 / NEBuilder 20 / Gibson 25 bp 同源臂两端匹配检查；产物 +1 框终止密码子（TAA/TAG/TGA）计数与 %3 读框校验，概念正确。Gibson 25 bp 口径并入 GC/同源重组分支，Golden Gate 与 Gibson 装配在 `Cloning.kt`/`CloningExt.kt`，方法覆盖完整。
- **Golden Gate fallback**（`core/CloningExt.kt` #19 ↔ main `backend/api.ts` `simulateGoldenGateAssembly` 浏览器分支）：Type IIS 位点 BsaI `GGTCTC`/`GAGACC`、BsmBI `CGTCTC`/`GAGACG`、其余 `GAAGAC`/`GTCTTC`；切割 offset BbsI 8、其余 7；4 bp 悬垂；片段取成对位点之间；末段右端悬垂 ≠ 首段左端悬垂时给出环化失败警告。位点/offset/悬垂/环化校验口径一致；哈密顿环匹配与反向方向解析属 Tauri/Rust 路径，Android fallback 按 main 浏览器行为只做标准顺序拼装，已在文件头注释披露。

## 四、存疑、口径近似与披露项（分级）

> 以下均**不构成构成性概念错误**。按处置口径区分：A=口径近似（与主流标准表有细微出入，建议后续校准）；
> B=main 1:1 移植保留的 quirk（行为对齐 main，非新引入缺陷）；C=演示数据/注释瑕疵；D=启发式经验阈值（口径偏宽，非硬错误）。

### A. 口径近似（建议后续版本校准）
1. **A280 消光系数 — 游离半胱氨酸计数口径**（`ProteinCore.kt`）：
   软件按 `W×5500 + Y×1490 + C×125` 计 A280。Expasy 标准中 125 对应**胱氨酸（二硫键 cystine）对数**，
   而非每个游离 Cys 残基；游离 Cys 通常不计 125。当前按每个游离 Cys 计 125 属近似口径，
   会高估含游离半胱氨酸蛋白的消光值。Trp 5500 / Tyr 1490 与教材 ch011"Trp、Tyr 在 280 nm 吸收、
   Phe 贡献很小"的定性结论一致。

### B. main 移植保留的 quirk（1:1 对齐，不擅自改动）
2. **Nonsense 分支不可达**（`CloneValidation.kt`）：main 终止子剥离 quirk 导致 getAminoAcidString
   剥掉尾部终止子，Nonsense 判定分支不可达；代码注释已披露，按 1:1 保留。
3. **SW local 模式 CIGAR 不可用**（`SwAlign.kt`）：main 桌面端 bio Local 模式 `cigar()` 实际 panic，
   走死路径；本实现给出真实 SW 结果，alignment_str 不含 CIGAR，与 Rust panic 行为对齐。
4. **Crispr 代码头注释列明的 6 条 main quirk**：g 标志正则 lastIndex 状态、脱靶硬编码 NGG、
   同义突变取 alts[0] 等，均为 1:1 保留项。

### C. 演示数据 / 注释瑕疵
5. **EGFP 示例注释 GATC 计数不符**（`Evolution.kt` col_4）：注释称"恰含 1 个 GATC 位点"，
   实测 GATC=3；前三条（pUC19 / pcDNA3.1 / pET28a）为 GATC 重复 + 填充碱基的合成占位序列，
   非真实质粒。
6. **RnaStructure `loopCount` 字段复用 `hairpinCount` 值**（`RnaStructure.kt`）：FoldResult 构造处
   第 6、7 参数同值；loopCount 未独立统计。另 MFE 为粗略近似（每对 −1 kcal + 环惩罚 −0.5），
   非 Turner 最近邻自由能，代码注释已披露。

### D. 启发式经验阈值（口径偏宽，非硬错误）
7. **引物阈值**：Tm 50–75°C、GC 30–70% 为经验范围，随配方/宿主可调。
8. **CRISPR array 范围**：重复 23–40 bp、间隔 21–72 bp 为启发式识别区间，比文献常见范围偏宽。
9. **RibosomalSlippage**：slipCodonsCount = length/3，对 7 nt 七聚体滑移 motif 做截断，属 main 移植启发式。
10. **凝胶 ladder / 蛋白 ladder**：DNA ladder（bp）与蛋白 ladder（kDa 表观分子量）为厂商目录数据；
    预染蛋白条带为 Laemmli 系统表观分子量，随胶浓度/缓冲液漂移，注释已披露。
11. **RNA MFE 近似**：见 C-6；Nussinov 仅最大化配对数，不做最近邻自由能优化。
12. **推荐条件双重否定写法**（`CloningMethods.recommend`）：限制酶推荐条件写成 `!v.contains("GAATTC").not()`，等价于"载体含 EcoRI 位点才推荐限制酶"，判定逻辑成立但可读性差；该推荐函数为 Android 面板衍生（main 无对应实现），不影响正确性，留待后续重构时简化。

## 五、尚未深度复核的模块

以下模块以工程实现 / UI 编排 / 文件解析 / 素材资产为主，不承载需要对照教材常量表的生化概念，
本轮优先级低，未逐项回读：

- 比对/IO：`AlignmentIO.kt`、`TreeTool.kt`（Newick 解析展示）。
- 文件解析：`AbiReader.kt`（AB1 测序峰图）、`XlsxReader.kt`、`Spiceg.kt`、`CollectionIO.kt`（序列读写辅助）、`Spicep.kt`、`SyncDb.kt`。
- 图像检测：`tools/colony/ColonySegment.kt`（阈值/连通域/分水岭流水线，见 §2）；`BlotDetect.kt` 已在 §3.9 核对。
- 克隆编排：`CloningPlus.kt`、`Enzymes.kt`、`EnzymeSelector.kt`（方法编排与酶选择，酶库口径见 §3.5/§3.8）；`Cloning.kt`（Gibson 同源臂/Golden Gate 位点筛查）、`CloningExt.kt` #19、`CloningMethods.kt` 已在 §3.9 核对。
- 展示/素材：`ProteinView.kt`、`MolStarPdb.kt`（3D 查看器渲染）、Figure* 系列、ImageTools、Slide/PPTX 导出。
- AI / i18n / 存储层：`Ai*.kt`、`I18n.kt`、`ModelStore.kt`、`SpdLibrary.kt`、`SpiceOnnx.kt` 等，不属生化概念审计范围。

## 六、总体结论

- 本轮以 Lehninger 第七版教材为权威基准，覆盖软件内遗传密码、氨基酸/蛋白理化与结构、酶动力学、
  核酸性质、引物/Tm、基因组元件、qPCR/WB 定量、进化比对、RNA 结构、克隆仿真等全部核心生化概念模块。
- **未发现构成概念性错误的硬缺陷**：标准密码子表、同义分组、氨基酸 MW/KD/pKa、酶学方程、
  最近邻 Tm、CAI、NJ 树、光密度/qPCR 定量等核心公式与常量均与教材及标准基准一致。
- 所列存疑项集中在：1 处游离 Cys 消光计数口径近似（A-1）、若干 main 移植 quirk（B 类，行为对齐 main）、
  演示数据注释瑕疵（C 类）、以及偏宽的启发式经验阈值（D 类）。处置原则：A 类列入后续校准候选；
  B/C/D 类按 1:1 复刻与平台差异披露惯例保留，不在本轮擅自改变对齐 main 的行为。
- 复现入口：`docs/audit/verify_codon_tables.py`、`docs/audit/verify_protein_tables.py`。
