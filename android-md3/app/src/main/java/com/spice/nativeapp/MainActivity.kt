package com.spice.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.core.Enzymes
import com.spice.nativeapp.core.SeqRecord
import com.spice.nativeapp.data.ProjectStore
import com.spice.nativeapp.tools.ToolsScreen
import com.spice.nativeapp.ui.AppNavigationRail
import com.spice.nativeapp.ui.CloningScreen
import com.spice.nativeapp.ui.DemoScreen
import com.spice.nativeapp.ui.GeneScreen
import com.spice.nativeapp.ui.WelcomeScreen
import com.spice.nativeapp.ui.ProjectScreen
import com.spice.nativeapp.ui.ProteinScreen
import com.spice.nativeapp.ui.SettingsScreen
import com.spice.nativeapp.ui.SpiceTheme
import com.spice.nativeapp.ui.WidthClass
import com.spice.nativeapp.ui.currentWidthClass

enum class Tab(val label: String, val icon: ImageVector) {
    GENE("基因", Icons.Filled.Biotech),
    PROTEIN("蛋白", Icons.Filled.Science),
    CLONING("克隆", Icons.Filled.Widgets),
    PROJECT("项目", Icons.Filled.Folder),
    TOOLS("工具", Icons.Filled.GridView),
    SETTINGS("设置", Icons.Filled.Settings)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SpiceApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpiceApp() {
    val ctx = LocalContext.current
    var dark by remember { mutableStateOf(true) }
    var autoRotate by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(Tab.GENE) }
    var record by remember { mutableStateOf(Bio.demoPlasmid()) }
    var selected by remember { mutableStateOf(Enzymes.common) }
    var projects by remember { mutableStateOf(ProjectStore.load(ctx)) }
    var demoMode by remember { mutableStateOf(false) }
    val prefs = remember { ctx.getSharedPreferences("spice_settings", android.content.Context.MODE_PRIVATE) }
    var amoled by remember { mutableStateOf(prefs.getBoolean("theme_amoled", true)) }
    var showWelcome by remember { mutableStateOf(!prefs.getBoolean("welcome_seen", false)) }

    fun enter(t: Tab) {
        tab = t
        showWelcome = false
        prefs.edit().putBoolean("welcome_seen", true).apply()
    }

    // ── 全局返回手势层级（对齐安卓规范，替代"一按返回直接退桌面"）：
    //    演示模式 → 退出演示；非基因 Tab → 回基因页；基因页 → 默认（退出应用）
    androidx.activity.compose.BackHandler(enabled = demoMode || tab != Tab.GENE) {
        when {
            demoMode -> demoMode = false
            else -> tab = Tab.GENE
        }
    }

    SpiceTheme(dark = dark, amoled = amoled) {
        if (showWelcome) {
            WelcomeScreen(
                versionLabel = "1.7.5 · native",
                onOpenGene = { enter(Tab.GENE) },
                onOpenProtein = { enter(Tab.PROTEIN) },
                onOpenProjects = { enter(Tab.PROJECT) },
                onOpenTools = { enter(Tab.TOOLS) },
                onOpenSettings = { enter(Tab.SETTINGS) }
            )
            return@SpiceTheme
        }
        if (demoMode) {
            DemoScreen(record = record, onExit = { demoMode = false })
            return@SpiceTheme
        }

        val widthClass = currentWidthClass()

        val screenContent: @Composable () -> Unit = {
            when (tab) {
                Tab.GENE -> GeneScreen(
                    record = record,
                    selectedEnzymes = selected,
                    onToggleEnzyme = { name ->
                        selected = if (selected.contains(name)) selected - name else selected + name
                    },
                    onRecordChange = { rec ->
                        record = rec
                        projects = projects.map { if (it.name == rec.name) it.copy(sequence = rec.sequence) else it }
                    },
                    onOpenProjects = { tab = Tab.PROJECT },
                    onOpenSettings = { tab = Tab.SETTINGS },
                    onSaveProject = {
                        val updated = projects + ProjectStore.fromRecord(record)
                        projects = updated
                        ProjectStore.save(ctx, updated)
                        tab = Tab.PROJECT
                    }
                )
                Tab.PROTEIN -> ProteinScreen()
                Tab.CLONING -> CloningScreen(record)
                Tab.PROJECT -> ProjectScreen(
                    projects = projects,
                    current = record,
                    onSaveCurrent = { name ->
                        val updated = projects + ProjectStore.fromRecord(
                            SeqRecord(
                                name = name.ifBlank { "未命名" },
                                sequence = record.sequence,
                                circular = record.circular,
                                features = record.features,
                                description = record.description
                            )
                        )
                        projects = updated
                        ProjectStore.save(ctx, updated)
                    },
                    onOpen = { p ->
                        record = ProjectStore.toRecord(p)
                        val updated = projects.map {
                            if (it.id == p.id) it.copy(history = it.history + "${System.currentTimeMillis()}|在编辑器中打开") else it
                        }
                        projects = updated
                        ProjectStore.save(ctx, updated)
                    },
                    onUpdate = { p ->
                        projects = projects.map { if (it.id == p.id) p else it }
                        ProjectStore.save(ctx, projects)
                    },
                    onDelete = { p ->
                        projects = projects.filterNot { it.id == p.id }
                        ProjectStore.save(ctx, projects)
                    }
                )
                Tab.TOOLS -> ToolsScreen(record = record, onImport = { rec -> record = rec })
                Tab.SETTINGS -> SettingsScreen(
                    darkTheme = dark,
                    onDarkThemeChange = { dark = it },
                    amoled = amoled,
                    onAmoledChange = {
                        amoled = it
                        prefs.edit().putBoolean("theme_amoled", it).apply()
                    },
                    autoRotate = autoRotate,
                    onAutoRotateChange = { autoRotate = it },
                    demoMode = demoMode,
                    onDemoModeChange = { demoMode = it }
                )
            }
        }

        val topBar: @Composable () -> Unit = {
            TopAppBar(
                title = {
                    Column {
                        Text("生信工具箱", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(record.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
                actions = {
                    Text(
                        "${record.length} bp",
                        fontSize = 11.sp,
                        modifier = Modifier.padding(end = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }

        if (widthClass == WidthClass.COMPACT) {
            // 紧凑窗口（手机竖屏）：Material 3 底部导航栏
            Scaffold(
                topBar = topBar,
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                        Tab.values().forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { Icon(t.icon, contentDescription = t.label) },
                                label = { Text(t.label, fontSize = 12.sp, maxLines = 1) },
                                alwaysShowLabel = true
                            )
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.background
            ) { pad ->
                Column(Modifier.fillMaxSize().padding(pad)) { screenContent() }
            }
        } else {
            // 中等/扩展窗口：Material 3 规范用 Navigation rail 取代底部栏
            Row(Modifier.fillMaxSize()) {
                AppNavigationRail(
                    items = Tab.values().toList(),
                    selected = tab,
                    label = { it.label },
                    icon = { it.icon },
                    onSelect = { tab = it }
                )
                Column(Modifier.fillMaxSize()) {
                    Scaffold(
                        topBar = topBar,
                        containerColor = MaterialTheme.colorScheme.background
                    ) { pad ->
                        Column(Modifier.fillMaxSize().padding(pad)) { screenContent() }
                    }
                }
            }
        }
    }
}
