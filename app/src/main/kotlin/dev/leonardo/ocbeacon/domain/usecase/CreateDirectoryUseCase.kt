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
        val sanitized = folderName.trim().trim('/').replace(Regex("/+"), "/")
        if (sanitized.isBlank() || sanitized == "." || sanitized == "..") {
            return Result.failure(IllegalArgumentException("Invalid folder name"))
        }

        return runCatching {
            val targetDirectory = if (parentDirectory == "/") {
                "/$sanitized"
            } else {
                "${parentDirectory.trimEnd('/')}/$sanitized"
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
                runCatching { sessionRepository.deleteSession(serverId, tempSession.id) }
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
