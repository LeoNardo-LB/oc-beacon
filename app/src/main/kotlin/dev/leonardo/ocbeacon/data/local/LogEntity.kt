package dev.leonardo.ocbeacon.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 诊断日志条目（原 DiagnosticLogDatabase.logs 表等价迁移）。
 * details 为 Map<String,String> 的 JSON 编码字符串。
 */
@Entity(
    tableName = "logs",
    indices = [Index(value = ["timestamp"]), Index(value = ["level", "timestamp"])],
)
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val level: String,                   // ERROR / WARN / INFO / DEBUG / FATAL
    val category: String,
    val message: String,
    val details: String,                 // JSON 编码的 Map<String,String>
    val byteSize: Int,
)
