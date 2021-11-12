package com.sonarsource.jb.sqpermissionsupdater

import kotlinx.serialization.Serializable

@Serializable
data class ProjectsSearch(val paging: PagingInfo, val components: List<SqProject>)
@Serializable
data class PagingInfo(val pageIndex: Int, val pageSize: Int, val total: Int)
@Serializable
data class SqProject(
    val key: String,
    val name: String,
    val qualifier: String,
    val visibility: String,
    val lastAnalysisDate: String? = null,
    val revision: String? = null,
)

@Serializable
data class PermissionTemplateSearch(val permissionTemplates: List<PermissionTemplate>, val defaultTemplates: List<PermissionTemplate>)
@Serializable
data class PermissionTemplate(val templateId: String)
