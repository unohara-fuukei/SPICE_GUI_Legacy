package com.spice.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.GridView
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
import com.spice.nativeapp.ui.CloningScreen
import com.spice.nativeapp.ui.GeneScreen
import com.spice.nativeapp.ui.ProjectScreen
import com.spice.nativeapp.ui.ProteinScreen
import com.spice.nativeapp.ui.SettingsScreen
import com.spice.nativeapp.ui.SpiceTheme
import com.spice.nativeapp.tools.ToolsScreen

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

    SpiceTheme(dark = dark) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("SPICE", fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontSize = 18.sp)
                            Text(record.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                    Tab.values().forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label, fontSize = 12.sp) }
                        )
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                when (tab) {
                    Tab.GENE -> GeneScreen(
                        record = record,
                        selectedEnzymes = selected,
                        onToggleEnzyme = { name ->
                            selected = if (selected.contains(name)) selected - name else selected + name
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
                    Tab.TOOLS -> ToolsScreen(
                        record = record,
                        onImport = { rec -> record = rec }
                    )
                    Tab.SETTINGS -> SettingsScreen(
                        darkTheme = dark,
                        onDarkThemeChange = { dark = it },
                        autoRotate = autoRotate,
                        onAutoRotateChange = { autoRotate = it }
                    )
                }
            }
        }
    }
}
