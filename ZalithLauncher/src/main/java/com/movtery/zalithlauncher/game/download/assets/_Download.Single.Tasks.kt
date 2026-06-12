package com.movtery.zalithlauncher.game.download.assets

import android.content.Context
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.coroutine.Task
import com.movtery.zalithlauncher.coroutine.TaskSystem
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformDependencyType
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformVersion
import com.movtery.zalithlauncher.game.download.assets.platform.getVersions
import com.movtery.zalithlauncher.game.download.assets.platform.mcim.mapMCIMMirrorUrls
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.utils.file.ensureParentDirectory
import com.movtery.zalithlauncher.utils.file.formatFileSize
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.network.downloadFromMirrorListSuspend
import com.movtery.zalithlauncher.utils.network.toLocal
import com.movtery.zalithlauncher.utils.network.withSpeedReport
import com.movtery.zalithlauncher.viewmodel.ErrorViewModel
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import okio.IOException
import org.apache.commons.io.FileUtils
import java.io.File
import java.net.ConnectException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

private const val TAG = "DownloadSingle"

/**
 * 为一些版本下载单独的资源文件
 * @param version 要下载单独资源版本信息
 * @param versions 为哪些游戏版本下载
 * @param folder 版本游戏目录下的相对路径
 * @param autoDownloadDependencies 是否自动下载必需的前置依赖（默认开启）
 * @param processedProjectIds 已处理过的项目Id集合，避免循环依赖导致的重复下载
 * @param onFileCopied 文件已成功复制到版本游戏目录后 单独回调
 * @param onFileCancelled 文件安装已取消 单独回调
 */
fun downloadSingleForVersions(
    context: Context,
    version: PlatformVersion,
    versions: List<Version>,
    folder: String,
    autoDownloadDependencies: Boolean = true,
    processedProjectIds: MutableSet<String> = mutableSetOf(),
    onFileCopied: suspend (zip: File, folder: File) -> Unit = { _, _ -> },
    onFileCancelled: (zip: File, folder: File) -> Unit = { _, _ -> },
    submitError: (ErrorViewModel.ThrowableMessage) -> Unit
) {
    val cacheFile = File(File(PathManager.DIR_CACHE, "assets"), version.platformSha1() ?: version.platformFileName())

    downloadSingleFile(
        version = version,
        file = cacheFile,
        onDownloaded = { task ->
            task.updateProgress(-1f, R.string.download_assets_install_progress_installing, version.platformFileName())
            versions.forEach { ver ->
                val targetFolder = File(ver.getGameDir(), folder)
                val targetFile = File(targetFolder, version.platformFileName())
                if (targetFile.exists() && !targetFile.delete()) throw IOException("Failed to properly delete the existing target file.")
                cacheFile.copyTo(targetFile)
                onFileCopied(targetFile, targetFolder) //文件已复制回调
            }

            //自动下载必需的前置依赖
            if (autoDownloadDependencies) {
                downloadRequiredDependencies(
                    context = context,
                    version = version,
                    versions = versions,
                    folder = folder,
                    processedProjectIds = processedProjectIds,
                    onFileCopied = onFileCopied,
                    onFileCancelled = onFileCancelled,
                    submitError = submitError
                )
            }
        },
        onError = { e ->
            Logger.warning(TAG, "An error occurred while downloading the resource files.", e)
            val message = mapExceptionToMessage(e).let { pair ->
                val args = pair.second
                if (args != null) {
                    context.getString(pair.first, *args)
                } else {
                    context.getString(pair.first)
                }
            }
            submitError(
                ErrorViewModel.ThrowableMessage(
                    title = context.getString(R.string.download_assets_install_failed),
                    message = message
                )
            )
        },
        onCancel = {
            FileUtils.deleteQuietly(cacheFile)
            versions.forEach { ver ->
                val targetFolder = File(ver.getGameDir(), folder)
                val targetFile = File(targetFolder, version.platformFileName())
                if (targetFile.exists()) FileUtils.deleteQuietly(targetFile)
                onFileCancelled(targetFile, targetFolder) //文件已取消回调
            }
        },
        onFinally = {
            Logger.info(TAG, "Attempting to clear cached resource files.")
            FileUtils.deleteQuietly(cacheFile)
        }
    )
}

/**
 * 自动解析并下载当前版本所必需(REQUIRED)的前置依赖项目
 * 会根据当前版本的游戏版本号、加载器类型，挑选依赖项目中最匹配、最新的版本进行下载
 */
private fun downloadRequiredDependencies(
    context: Context,
    version: PlatformVersion,
    versions: List<Version>,
    folder: String,
    processedProjectIds: MutableSet<String>,
    onFileCopied: suspend (zip: File, folder: File) -> Unit,
    onFileCancelled: (zip: File, folder: File) -> Unit,
    submitError: (ErrorViewModel.ThrowableMessage) -> Unit
) {
    val requiredDeps = version.platformDependencies()
        .filter { it.type == PlatformDependencyType.REQUIRED }
        .filter { processedProjectIds.add(it.projectId) } //标记为已处理，同时过滤已处理过的依赖

    val currentGameVersions = version.platformGameVersion().toSet()
    val currentLoaders = version.platformLoaders().map { it.getDisplayName() }.toSet()

    requiredDeps.forEach { dependency ->
        TaskSystem.submitTask(
            Task.runTask(
                id = "dependency_resolve_${dependency.projectId}",
                task = {
                    getVersions<PlatformVersion>(
                        projectID = dependency.projectId,
                        platform = dependency.platform,
                        pageCallback = { _, _ -> },
                        onSuccess = { result: List<PlatformVersion> ->
                            //初始化所有版本数据，过滤掉初始化失败的版本，按发布时间倒序排序
                            val initializedVersions = mutableListOf<PlatformVersion>()
                            for (depVersion in result) {
                                if (depVersion.initFile(dependency.projectId)) {
                                    initializedVersions.add(depVersion)
                                }
                            }
                            initializedVersions.sortByDescending { it.platformDatePublished() }

                            //挑选最匹配当前游戏版本与加载器，并且发布时间最新的依赖版本
                            val bestMatch = initializedVersions
                                .filter { dep ->
                                    val depGameVersions = dep.platformGameVersion().toSet()
                                    val depLoaders = dep.platformLoaders().map { it.getDisplayName() }.toSet()
                                    val gameVersionMatch = depGameVersions.any { it in currentGameVersions }
                                    val loaderMatch = currentLoaders.isEmpty() ||
                                            depLoaders.isEmpty() ||
                                            depLoaders.any { it in currentLoaders }
                                    gameVersionMatch && loaderMatch
                                }
                                .maxByOrNull { it.platformDatePublished() }
                                //若没有完全匹配的版本，回退到只匹配游戏版本的最新版本
                                ?: initializedVersions
                                    .filter { dep ->
                                        dep.platformGameVersion().any { it in currentGameVersions }
                                    }
                                    .maxByOrNull { it.platformDatePublished() }

                            bestMatch?.let { depVersion ->
                                downloadSingleForVersions(
                                    context = context,
                                    version = depVersion,
                                    versions = versions,
                                    folder = folder,
                                    autoDownloadDependencies = true,
                                    processedProjectIds = processedProjectIds,
                                    onFileCopied = onFileCopied,
                                    onFileCancelled = onFileCancelled,
                                    submitError = submitError
                                )
                            } ?: run {
                                Logger.warning(
                                    TAG,
                                    "No matching dependency version found for project ${dependency.projectId}, skipping."
                                )
                            }
                        },
                        onError = {
                            Logger.warning(TAG, "Failed to resolve dependency project ${dependency.projectId}.")
                        }
                    )
                }
            )
        )
    }
}

private fun downloadSingleFile(
    version: PlatformVersion,
    file: File,
    onDownloaded: suspend (Task) -> Unit,
    onError: (Throwable) -> Unit = {},
    onCancel: () -> Unit = {},
    onFinally: () -> Unit = {}
) {
    TaskSystem.submitTask(
        Task.runTask(
            id = version.platformSha1() ?: version.platformFileName(),
            task = { task ->
                val totalFileSize = version.platformFileSize()
                var downloadedSize = 0L

                //更新下载任务进度
                fun updateProgress() {
                    task.updateProgress(
                        (downloadedSize.toDouble() / totalFileSize.toDouble()).toFloat(),
                        R.string.download_assets_install_progress_downloading,
                        version.platformFileName(),
                        formatFileSize(downloadedSize),
                        formatFileSize(totalFileSize),
                    )
                }
                updateProgress()

                withSpeedReport(
                    onSpeedReport = { bytes ->
                        task.updateSpeed(bytes)
                    },
                    onClear = {
                        task.clearSpeed()
                    }
                ) { report ->
                    downloadFromMirrorListSuspend(
                        urls = version
                            .platformDownloadUrl()
                            .mapMCIMMirrorUrls(),
                        sha1 = version.platformSha1(),
                        outputFile = file.ensureParentDirectory(),
                        sizeCallback = { size ->
                            downloadedSize += size
                            updateProgress()
                            report(size)
                        }
                    )
                }

                onDownloaded(task)
            },
            onError = onError,
            onCancel = onCancel,
            onFinally = onFinally
        )
    )
}

fun mapExceptionToMessage(e: Throwable): Pair<Int, Array<Any>?> {
    return when (e) {
        is HttpRequestTimeoutException -> Pair(R.string.error_timeout, null)
        is UnknownHostException, is UnresolvedAddressException -> Pair(R.string.error_network_unreachable, null)
        is ConnectException -> Pair(R.string.error_connection_failed, null)
        is ResponseException -> e.toLocal()
        else -> {
            val errorMessage = e.localizedMessage ?: e::class.simpleName ?: "Unknown error"
            Pair(R.string.empty_holder, arrayOf(errorMessage))
        }
    }
}
