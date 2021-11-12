package com.sonarsource.jb.sqpermissionsupdater

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.validate
import com.github.kittinunf.fuel.core.Request
import com.github.kittinunf.fuel.core.extensions.authentication
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.fuel.httpPost
import com.github.kittinunf.fuel.serialization.responseObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

private const val SQ_TOKEN_ENV_VARIABLE = "SONARQUBE_TOKEN"

private val json = Json {
    ignoreUnknownKeys = true
}

fun main(vararg args: String) {
    val authToken: String = System.getenv(SQ_TOKEN_ENV_VARIABLE)
        ?: exitWithError("Please set a valid SonarQube auth token in the environment variable $SQ_TOKEN_ENV_VARIABLE")

    Main(authToken).main(args)
}

class Main(val authToken: String, val dispatcher: CoroutineDispatcher = Dispatchers.IO) : CliktCommand() {
    private val apiUrl by option(
        "-b", "--base-url",
        help = "Base URL of the SonarQube instance you want to query against"
    ).convert {
        "$it${(if (it.last() != '/') "/" else "")}api"
    }.required()

    private val pageSize by option(
        "-p", "--page-size",
        help = "Page size used for pagination with SonarQube"
    ).convert {
        it.toInt()
    }.default(500).validate { require(it in 1..500) }

    private val timeout by option(
        "-r", "--read-timeout",
        help = "The timeout for web requests in milliseconds"
    ).convert {
        it.toInt()
    }.default(120_000)

    private val permissionTemplateId by option(
        "-t", "--permission-template",
        help = "The ID of the permissions template to apply to all projects"
    ).required().validate {
        val permissionTemplates = findPermissionTemplate(it).third.get().let { search ->
            search.permissionTemplates + search.defaultTemplates
        }
        require(permissionTemplates.any { template -> template.templateId == it }) {
            "Error: Unable to find permission template with id $it."
        }
    }

    private val bulkApplyMaxProjectAmount by option(
        "--max-bulk-apply",
        help = "The number of projects to which to apply the permission template in bulk at once."
    ).convert {
        it.toInt()
    }.default(1000).validate { require(it in 1..1000) }

    override fun run() {
        runBlocking { doTheWork() }
    }

    suspend fun doTheWork() {
        val totalProjects = projectSearch(1)
            .let { (_, _, result) ->
                result.get().paging.total
            }

        val totalPages = (totalProjects / pageSize) + (if (totalProjects % pageSize > 0) 1 else 0)

        println("Found $totalProjects projects on $totalPages pages")
        println("Retrieving projects (this may take a while - the timeout is set to ${timeout}ms)...")

        val projects = withContext(dispatcher) {
            (1..totalPages).map { page ->
                async {
                    projectSearch(page).third.get().components.also { println("  Retrieved ${it.size} projects on page $page") }
                }
            }.flatMap {
                it.await()
            }
        }

        println("Retrieved a total of ${projects.size} projects.")
        print("Setting projects to private...")

        val progress = AtomicInteger()

        coroutineScope {
            var progressReporter = progressTracker(progress, totalProjects)

            withContext(dispatcher) {
                projects.map { project ->
                    async {
                        val statusCode = setProjectToPrivate(project.key)
                        progress.incrementAndGet()
                        statusCode to project.key
                    }
                }.map {
                    it.await()
                }.reportErrors { statusCode, projectKey ->
                    ("Error: Could not set project '$projectKey' to private (status code $statusCode)")
                }
            }

            progressReporter.cancelAndJoin()
            print("Applying permissions template '$permissionTemplateId'...")
            progress.set(0)
            progressReporter = progressTracker(progress, totalProjects)

            withContext(dispatcher) {
                projects.foldIndexed(mutableListOf<MutableList<SqProject>>()) { index, acc, project ->
                    acc.apply {
                        if (index % bulkApplyMaxProjectAmount == 0) add(mutableListOf())
                        last().add(project)
                    }
                }.mapIndexed { index, projectBatch ->
                    async {
                        val statusCode = applyPermissionTemplateToProjects(permissionTemplateId, projectBatch.map { it.key })
                        progress.addAndGet(projectBatch.size)
                        statusCode to index
                    }
                }.map {
                    it.await()
                }.reportErrors { statusCode, i ->
                    "Error: could not update permissions on batch $i (status code $statusCode)"
                }
            }

            progressReporter.cancelAndJoin()

            println("Done.")

        }
    }

    private fun <T> Iterable<Pair<Int, T>>.reportErrors(lazyMsg: (Int, T) -> String) {
        filter { (statusCode, arg) ->
            statusCode >= 300
        }.forEach { (statusCode, arg) ->
            System.err.println(lazyMsg(statusCode, arg))
        }
    }

    private fun CoroutineScope.progressTracker(progress: AtomicInteger, total: Int) = launch {
        try {
            var lastProgress = -1
            while (true) {
                delay(2_000)
                val current = progress.get()
                if (current != lastProgress) {
                    print("\n  Progress: $current/$total")
                    lastProgress = current
                } else {
                    print(".")
                }
            }
        } catch (e: CancellationException) {
            println()
        }
    }

    private fun Request.auth() = authentication().basic(authToken, "")

    private fun projectSearch(page: Int, pSize: Int = pageSize) = "$apiUrl/projects/search"
        .httpGet(listOf("ps" to pSize, "p" to page))
        .auth()
        .timeoutRead(timeout)
        .responseObject<ProjectsSearch>(json)

    private fun applyPermissionTemplateToProjects(templateId: String, projectKeys: List<String>) =
        "$apiUrl/permissions/bulk_apply_template"
            .httpPost(listOf("templateId" to templateId, "projects" to projectKeys.joinToString(",")))
            .auth()
            .response()
            .second.statusCode

    private fun setProjectToPrivate(projectKey: String) =
        "$apiUrl/projects/update_visibility"
            .httpPost(listOf("project" to projectKey, "visibility" to "private"))
            .auth()
            .response()
            .second.statusCode

    private fun findPermissionTemplate(templateId: String) = "$apiUrl/permissions/search_templates"
        .httpGet(listOf("q" to templateId))
        .auth()
        .responseObject<PermissionTemplateSearch>(json)
}

fun <T> exitWithError(msg: String): T {
    System.err.println(msg)
    exitProcess(1)
}
