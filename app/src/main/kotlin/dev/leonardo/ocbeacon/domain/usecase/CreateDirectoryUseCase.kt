package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.CreateSessionOpts
import dev.leonardo.ocbeacon.domain.repository.FileRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * Use Case：在服务器上创建目录。
 *
 * 封装原 DirectoryManager.createDirectory 的 mkdir 临时会话 + shell 执行 +
 * 探测 + finally 清理语义（R6：临时会话必须在任何路径下被删除）。
 *
 * 执行流程：
 * 1. 创建临时会话（title="mkdir"）
 * 2. 尝试 runShellCommand(mkdir -p)；失败回退 executeCommand(bash -lc)
 * 3. finally：删除临时会话
 * 4. 轮询探测目标目录是否已创建
 */
/** #106-4：目录名斜杠规范化正则——顶层预编译（原每次调用现场编译）。 */
private val REPEATED_SLASH_REGEX = Regex("/+")

class CreateDirectoryUseCase @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val manageTerminalUseCase: ManageTerminalUseCase,
    private val fileRepository: FileRepository,
) {
    suspend operator fun invoke(
        serverId: String,
        parentDirectory: String,
        folderName: String,
    ): Result<String> {
        val sanitized = folderName.trim().trim('/').replace(REPEATED_SLASH_REGEX, "/")
        if (sanitized.isBlank() || sanitized == "." || sanitized == "..") {
            return Result.failure(IllegalArgumentException("Invalid folder name"))
        }

        return runCatching {
            val targetDirectory = if (parentDirectory == "/") {
                "/$sanitized"
            } else {
                "${parentDirectory.trimEnd('/')}/$sanitized"
            }

            // W4/D8(2026-09-06 全量 E2E)：原生建目录优先——DSH
            // directoryPicker/createDirectory 直达(无临时会话→无泄漏);V1/V2
            // 无原生端点(UnsupportedOperationException)回落下方临时会话 shell
            // 通道。服务器明确失败(directory-picker/create-failed 等)原样上抛
            // 不回落——回落会把「服务器拒绝」误报成 shell 双失败且多漏一个
            // 临时会话行。
            val native = fileRepository.createDirectory(serverId, parentDirectory, sanitized)
            if (native.exceptionOrNull() !is UnsupportedOperationException) {
                return@runCatching native.getOrThrow()
            }

            val tempSession = sessionRepository.createSession(
                serverId,
                CreateSessionOpts(title = "mkdir", directory = parentDirectory),
            ).getOrThrow()

            try {
                val escaped = sanitized.replace("'", "'\"'\"'")
                val command = "mkdir -p -- '$escaped'"

                val runShellOk = runCatching {
                    manageTerminalUseCase.runShellCommand(
                        serverId = serverId,
                        sessionId = tempSession.id,
                        command = command,
                        agent = "build",
                        model = null,
                        directory = parentDirectory,
                    )
                }.getOrElse { false }

                if (!runShellOk) {
                    val executeOk = manageTerminalUseCase.executeCommand(
                        serverId = serverId,
                        sessionId = tempSession.id,
                        command = "bash",
                        arguments = "-lc \"$command\"",
                        directory = parentDirectory,
                    )
                    if (!executeOk) {
                        throw IllegalStateException("Failed to create directory")
                    }
                }
            } finally {
                // W4/D8:清理失败不再静默——后端无 session.delete 能力位时临时会话
                // 必泄漏为正式行(DSH 实证),至少留可观测痕迹供诊断屏排查。
                val cleanup = runCatching { sessionRepository.deleteSession(serverId, tempSession.id) }
                if (cleanup.isFailure) {
                    dev.leonardo.ocbeacon.logging.AppLogger.w(
                        "CreateDirectoryUseCase",
                        "mkdir temp session cleanup failed (backend without session.delete?) — leaked row: " + tempSession.id,
                    )
                }
            }

            repeat(6) {
                if (directoryExists(serverId, targetDirectory)) {
                    return@runCatching targetDirectory
                }
                delay(200)
            }

            throw IllegalStateException("Directory was not created")
        }
    }

    /** 通过尝试列目录判断目录是否存在（成功即存在）。 */
    private suspend fun directoryExists(serverId: String, directory: String): Boolean {
        return try {
            fileRepository.listDirectory(serverId, directory, "").isSuccess
        } catch (_: Exception) {
            false
        }
    }
}
