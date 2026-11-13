package com.spice.nativeapp.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 多语言支持（对齐 main project.inlang：zh/en/ja/ko/de/nl/sv 七语言，sourceLanguageTag=zh）。
 * 本轮覆盖核心 UI 词条（导航/视图 tab/菜单组/设置页），全部词条逐步抽取中。
 * 切换即时生效（Compose state 驱动重组），选择持久化于 spice_settings/app_language。
 */
object I18n {
    /** main 语言标签 verbatim（zh.json langZh…langSv） */
    val LANGUAGES: List<Pair<String, String>> = listOf(
        "zh" to "简体中文", "en" to "English", "ja" to "日本語", "ko" to "한국어",
        "de" to "Deutsch", "nl" to "Nederlands", "sv" to "Svenska"
    )

    var lang by mutableStateOf("zh")

    /** 以中文原文为 key 查译；未收录词条原样返回（渐进抽取） */
    fun t(zh: String): String = DICT[lang]?.get(zh) ?: zh

    private val DICT: Map<String, Map<String, String>> = mapOf(
        "en" to mapOf(
            "基因" to "Gene", "蛋白" to "Protein", "项目" to "Project", "工具" to "Tools", "设置" to "Settings",
            "环状图谱" to "Circular Map", "线性图谱" to "Linear Map", "序列视图" to "Sequence",
            "特征列表" to "Features", "质粒属性" to "Properties", "科研备注" to "Notes",
            "文件" to "File", "编辑" to "Edit", "分子克隆工具" to "Molecular Cloning Tools",
            "分析与多视图" to "Analysis & Multi-View", "窗口与设置" to "Window & Settings",
            "选择" to "Select", "外观" to "Appearance", "关于" to "About",
            "界面显示语言:" to "Interface Language:",
            "生信工具箱" to "SPICE Bio Toolbox"
        ),
        "ja" to mapOf(
            "基因" to "遺伝子", "蛋白" to "タンパク質", "项目" to "プロジェクト", "工具" to "ツール", "设置" to "設定",
            "环状图谱" to "環状マップ", "线性图谱" to "線状マップ", "序列视图" to "シーケンス",
            "特征列表" to "フィーチャー", "质粒属性" to "プロパティ", "科研备注" to "ノート",
            "文件" to "ファイル", "编辑" to "編集", "分子克隆工具" to "分子クローニングツール",
            "分析与多视图" to "解析・マルチビュー", "窗口与设置" to "ウィンドウと設定",
            "选择" to "選択", "外观" to "外観", "关于" to "このアプリについて",
            "界面显示语言:" to "表示言語:",
            "生信工具箱" to "SPICE バイオツールボックス"
        ),
        "ko" to mapOf(
            "基因" to "유전자", "蛋白" to "단백질", "项目" to "프로젝트", "工具" to "도구", "设置" to "설정",
            "环状图谱" to "환형 지도", "线性图谱" to "선형 지도", "序列视图" to "시퀀스",
            "特征列表" to "피처", "质粒属性" to "속성", "科研备注" to "노트",
            "文件" to "파일", "编辑" to "편집", "分子克隆工具" to "분자 클로닝 도구",
            "分析与多视图" to "분석 · 멀티뷰", "窗口与设置" to "창 및 설정",
            "选择" to "선택", "外观" to "화면", "关于" to "정보",
            "界面显示语言:" to "인터페이스 언어:",
            "生信工具箱" to "SPICE 바이오 툴박스"
        ),
        "de" to mapOf(
            "基因" to "Gen", "蛋白" to "Protein", "项目" to "Projekt", "工具" to "Werkzeuge", "设置" to "Einstellungen",
            "环状图谱" to "Kreiskarte", "线性图谱" to "Linearkarte", "序列视图" to "Sequenz",
            "特征列表" to "Features", "质粒属性" to "Eigenschaften", "科研备注" to "Notizen",
            "文件" to "Datei", "编辑" to "Bearbeiten", "分子克隆工具" to "Molekulare Klonierung",
            "分析与多视图" to "Analyse & Multi-Ansicht", "窗口与设置" to "Fenster & Einstellungen",
            "选择" to "Auswählen", "外观" to "Erscheinungsbild", "关于" to "Über",
            "界面显示语言:" to "Sprache der Oberfläche:",
            "生信工具箱" to "SPICE Bio-Werkzeugkasten"
        ),
        "nl" to mapOf(
            "基因" to "Gen", "蛋白" to "Eiwit", "项目" to "Project", "工具" to "Tools", "设置" to "Instellingen",
            "环状图谱" to "Cirkelkaart", "线性图谱" to "Lineaire kaart", "序列视图" to "Sequentie",
            "特征列表" to "Features", "质粒属性" to "Eigenschappen", "科研备注" to "Notities",
            "文件" to "Bestand", "编辑" to "Bewerken", "分子克隆工具" to "Moleculair kloneren",
            "分析与多视图" to "Analyse & multi-weergave", "窗口与设置" to "Venster & instellingen",
            "选择" to "Selecteren", "外观" to "Weergave", "关于" to "Over",
            "界面显示语言:" to "Interfacetaal:",
            "生信工具箱" to "SPICE Bio-toolbox"
        ),
        "sv" to mapOf(
            "基因" to "Gen", "蛋白" to "Protein", "项目" to "Projekt", "工具" to "Verktyg", "设置" to "Inställningar",
            "环状图谱" to "Cirkulär karta", "线性图谱" to "Linjär karta", "序列视图" to "Sekvens",
            "特征列表" to "Funktioner", "质粒属性" to "Egenskaper", "科研备注" to "Anteckningar",
            "文件" to "Arkiv", "编辑" to "Redigera", "分子克隆工具" to "Molekylär kloning",
            "分析与多视图" to "Analys & multi-vy", "窗口与设置" to "Fönster & inställningar",
            "选择" to "Välj", "外观" to "Utseende", "关于" to "Om",
            "界面显示语言:" to "Gränssnittsspråk:",
            "生信工具箱" to "SPICE Biolverktygslåda"
        )
    )
}
