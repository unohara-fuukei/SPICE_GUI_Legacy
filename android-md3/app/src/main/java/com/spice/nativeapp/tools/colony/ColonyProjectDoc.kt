package com.spice.nativeapp.tools.colony

/*
 * 菌落计数器的项目文档助手，1:1 移植 main 的 src/lib/tools/colony/
 * projectDoc.ts。SPICE_PROJECT 文档保存与独立 JSON 导出相同的可移植
 * ColonyProject 对象。
 */
import org.json.JSONObject

const val COLONY_DOC_KIND = "colony"

/** 校验并规范化项目文档载荷；无效时返回 null。 */
fun normalizeColonyPayload(payload: Any?): ColonyProject? {
    if (payload !is JSONObject) return null
    if (!isColonyProject(payload)) return null
    return projectFromJson(payload)
}

fun colonyProjectName(project: ColonyProject, fallback: String): String {
    val name = project.imageName.replace(Regex("\\.[^.]+$"), "").trim()
    return name.ifEmpty { fallback }
}

data class ColonyProjectCounts(
    val included: Int,
    val excluded: Int,
    val blue: Int,
    val white: Int,
)

fun colonyProjectCounts(project: ColonyProject): ColonyProjectCounts {
    val included = project.markers.filter { it.included }
    return ColonyProjectCounts(
        included = included.size,
        excluded = project.markers.size - included.size,
        blue = included.count { it.colonyClass == CLASS_BLUE },
        white = included.count { it.colonyClass == CLASS_WHITE },
    )
}
