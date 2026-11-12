package com.spice.nativeapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Material 3 自适应断点：紧凑（手机竖屏）<600dp，中等 600–839dp，扩展 ≥840dp */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

@Composable
fun currentWidthClass(): WidthClass {
    val w = LocalConfiguration.current.screenWidthDp
    return when {
        w >= 840 -> WidthClass.EXPANDED
        w >= 600 -> WidthClass.MEDIUM
        else -> WidthClass.COMPACT
    }
}

/** 宽屏下的左侧导航栏（Material 3 Navigation rail，替代底部栏） */
@Composable
fun <T> AppNavigationRail(
    items: List<T>,
    selected: T,
    label: (T) -> String,
    icon: (T) -> ImageVector,
    onSelect: (T) -> Unit,
) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight().width(80.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        items.forEach { item ->
            NavigationRailItem(
                selected = item == selected,
                onClick = { onSelect(item) },
                icon = { Icon(icon(item), contentDescription = label(item)) },
                label = { Text(label(item), fontSize = 11.sp, maxLines = 1) },
                alwaysShowLabel = true
            )
        }
    }
}

/** 宽屏双栏：主列表 + 详情；紧凑屏退化为单栏 */
@Composable
fun AdaptiveSplit(
    widthClass: WidthClass,
    master: @Composable () -> Unit,
    detail: @Composable () -> Unit,
) {
    if (widthClass == WidthClass.EXPANDED) {
        Row(Modifier.fillMaxHeight()) {
            Row(Modifier.width(380.dp).fillMaxHeight()) { master() }
            VerticalDivider()
            Row(Modifier.fillMaxHeight().weight(1f)) { detail() }
        }
    } else {
        master()
    }
}
