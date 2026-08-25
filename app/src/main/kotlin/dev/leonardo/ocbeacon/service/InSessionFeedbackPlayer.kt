package dev.leonardo.ocbeacon.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 会话内反馈事件类型（镜像通知分类，#155）。 */
enum class FeedbackType { TURN_COMPLETE, PERMISSION, QUESTION, ERROR }

/**
 * 策略镜像管线的纯输出（#155 spec §5.2）：渠道×铃声档×DND×开关 → 声音+震动计划。
 * 纯数据，无 Android 依赖，可单测全矩阵。
 */
data class SoundPlan(
    val soundUri: android.net.Uri?,
    val vibrationPattern: LongArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SoundPlan
        return soundUri == other.soundUri && vibrationPattern?.contentEquals(other.vibrationPattern) == true
    }
    override fun hashCode(): Int = (soundUri?.hashCode() ?: 0) * 31 + (vibrationPattern?.contentHashCode() ?: 0)
}

/** 渠道快照（从 NotificationChannel 读出的镜像输入）。 */
data class ChannelSnapshot(
    val id: String,
    val importance: Int,
    val soundUri: android.net.Uri?,
    val shouldVibrate: Boolean,
    val vibrationPattern: LongArray?,
    val canBypassDnd: Boolean,
)

/**
 * 策略镜像管线（纯函数，spec §5.2）：
 * DND → 渠道镜像（声音/震动）→ RingerMode 三层，输出 [SoundPlan]。
 * 输入全部快照化——单测覆盖 §6 静音矩阵全组合。
 */
object FeedbackPolicy {

    /** 铃声档（镜像 AudioManager.RingerMode，int 常量避免测试依赖 Android）。 */
    const val RINGER_SILENT = 0
    const val RINGER_VIBRATE = 1
    const val RINGER_NORMAL = 2

    /** DND 中断过滤器（镜像 NotificationManager.INTERRUPTION_FILTER_*）。 */
    const val FILTER_ALL = 1
    const val FILTER_UNKNOWN = 0

    fun plan(
        channel: ChannelSnapshot?,
        ringerMode: Int,
        interruptionFilter: Int,
        defaultNotificationSound: android.net.Uri?,
    ): SoundPlan {
        // 渠道不存在（极端：系统回收）→ 完全静默
        if (channel == null) return SoundPlan(null, null)

        // 1) DND：非 ALL/UNKNOWN 且渠道无豁免 → 无声无震
        val dndActive = interruptionFilter !in intArrayOf(FILTER_ALL, FILTER_UNKNOWN) &&
                !channel.canBypassDnd
        if (dndActive) return SoundPlan(null, null)

        // 2) 渠道镜像
        // 声音：importance >= DEFAULT 才有声；渠道自定义铃声优先，缺省用系统默认通知音
        val sound: android.net.Uri? = if (channel.importance >= NotificationManager.IMPORTANCE_DEFAULT) {
            channel.soundUri ?: defaultNotificationSound
        } else null
        // 震动：渠道开关；有自定义模式用模式，否则 null（Vibrator 默认单次震）
        val vibration: LongArray? = if (channel.shouldVibrate) {
            channel.vibrationPattern ?: longArrayOf(0, 50)
        } else null

        // 3) RingerMode 叠加
        return when (ringerMode) {
            RINGER_SILENT -> SoundPlan(null, vibration)
            RINGER_VIBRATE -> SoundPlan(null, vibration)
            else -> SoundPlan(sound, vibration)
        }
    }
}

/**
 * 错误 streak 状态机（spec §5.3，通知侧与提示音侧共用语义）：
 * 连续错误只提示第一次；成功完成 turn 或用户发新消息重置。
 */
class ErrorStreakTracker {
    private val active = ConcurrentHashMap<String, Boolean>()

    private fun key(serverId: String, sessionId: String) = serverId + "::" + sessionId

    /** 错误事件到达：返回 true = 应提示（streak 首次），false = 连发静默。 */
    fun onError(serverId: String, sessionId: String): Boolean {
        val k = key(serverId, sessionId)
        if (active.getOrDefault(k, false)) return false
        active[k] = true
        return true
    }

    /** 成功完成 turn / 用户发新消息 → 重置。 */
    fun reset(serverId: String, sessionId: String) {
        active.remove(key(serverId, sessionId))
    }

    fun isActive(serverId: String, sessionId: String): Boolean =
        active.getOrDefault(key(serverId, sessionId), false)
}

/** Ringtone/Vibrator 薄壳（注入 fake 断言调用，spec §7）。 */
interface FeedbackPlayerShell {
    fun play(soundUri: android.net.Uri, vibrationPattern: LongArray?)
}

/**
 * 会话内反馈播放器（#155 R1）：被抑制的系统通知转为提示音+震动，
 * 策略完全镜像系统通知（渠道配置/铃声档/DND/app 开关）。
 *
 * 挂载在抑制分支内部（spec §5.4）——SSE 与 REST 兜底路径自动全覆盖；
 * 独立去重 map（Q11：不污染通知侧去重，避免响过一声后离场补发被吞）。
 */
@Singleton
class InSessionFeedbackPlayer @Inject constructor(
    private val sessionFocusHolder: SessionFocusHolder,
    private val settingsRepository: SettingsRepository,
    @param:dev.leonardo.ocbeacon.di.ApplicationScope private val appScope: CoroutineScope,
) {
    private val errorStreak = ErrorStreakTracker()

    /** 独立事件去重（Q11）：per (server, session, type) 最近一次内容键。 */
    private val lastPlayedBySession = ConcurrentHashMap<String, String>()

    /** 通知侧共用的 streak（R4：SessionError 通知同款去重）。 */
    val notificationErrorStreak: ErrorStreakTracker get() = errorStreak

    // shell 延迟初始化（真机生产实现；测试注入 fake）
    @Volatile private var shell: FeedbackPlayerShell? = null
    @Volatile private var appContext: Context? = null

    fun attach(context: Context, playerShell: FeedbackPlayerShell? = null) {
        appContext = context.applicationContext
        if (playerShell != null) shell = playerShell
    }

    /** 成功完成 turn（SessionIdle 且有输出）→ 重置该会话错误 streak。 */
    fun onTurnCompleted(serverId: String, sessionId: String) {
        errorStreak.reset(serverId, sessionId)
    }

    /** 用户发出新消息 → 重置（Q10：用户主动重发不算连发）。 */
    fun onUserMessage(serverId: String, sessionId: String) {
        errorStreak.reset(serverId, sessionId)
    }

    /** 进入会话（cancelSessionNotifications 时机）→ 随去重一并重置。 */
    fun onSessionEntered(serverId: String, sessionId: String) {
        errorStreak.reset(serverId, sessionId)
    }

    /**
     * 抑制分支内调用：前台+焦点匹配（与 shouldSuppress 同条件，由调用方保证）时播放。
     * 判定顺序（spec §5.1）：focus（调用方已验）→ notificationsEnabled →
     * streak（ERROR，R3）→ 独立去重（Q11）→ 策略管线 → 播放。
     *
     * [dedupKey]：事件内容键（turn=messageId / permission=权限串 / question=问题文本 /
     * error=错误文本）——镜像通知侧去重语义但物理隔离，防 SSE 重放双响。
     */
    fun playIfFocused(
        serverId: String,
        sessionId: String,
        type: FeedbackType,
        dedupKey: String,
        silentNotifications: Boolean,
        notificationsEnabled: Boolean,
    ) {
        if (!notificationsEnabled) return
        if (type == FeedbackType.ERROR && !errorStreak.onError(serverId, sessionId)) return

        // Q11 独立去重：同事件重放只响一次；不触碰通知侧 map
        val dedupMapKey = serverId + "::" + sessionId + "::" + type.name
        if (type != FeedbackType.ERROR && lastPlayedBySession[dedupMapKey] == dedupKey) return
        lastPlayedBySession[dedupMapKey] = dedupKey

        val ctx = appContext ?: return
        val playerShell = shell ?: DefaultShell(ctx)

        val channelId = when (type) {
            FeedbackType.TURN_COMPLETE ->
                if (silentNotifications) CHANNEL_TASKS_SILENT else CHANNEL_TASKS
            FeedbackType.PERMISSION -> CHANNEL_PERMISSIONS
            FeedbackType.QUESTION -> CHANNEL_QUESTIONS
            FeedbackType.ERROR -> CHANNEL_TASKS
        }

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel: NotificationChannel? = nm.getNotificationChannel(channelId)
        val snapshot = channel?.let {
            ChannelSnapshot(it.id, it.importance, it.sound, it.shouldVibrate(), it.vibrationPattern, it.canBypassDnd())
        }

        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val plan = FeedbackPolicy.plan(
            channel = snapshot,
            ringerMode = am.ringerMode,
            interruptionFilter = nm.currentInterruptionFilter,
            defaultNotificationSound = defaultSound,
        )

        if (plan.soundUri == null && plan.vibrationPattern == null) return
        dev.leonardo.ocbeacon.logging.AppLogger.i(
            "InSessionFeedback",
            "play type=" + type + " channel=" + channelId +
                " sound=" + (plan.soundUri != null) + " vibration=" + (plan.vibrationPattern != null),
        )
        playerShell.play(plan.soundUri ?: android.net.Uri.EMPTY, plan.vibrationPattern)
    }

    private class CHANNELS

    companion object {
        // 渠道 ID 单一真相源见 NotificationChannels（2026-08-24 收口：
        // 原双处镜像声明无任何保护，注释声称的钉死单测并不存在）
        const val CHANNEL_TASKS = NotificationChannels.TASKS
        const val CHANNEL_TASKS_SILENT = NotificationChannels.TASKS_SILENT
        const val CHANNEL_PERMISSIONS = NotificationChannels.PERMISSIONS
        const val CHANNEL_QUESTIONS = NotificationChannels.QUESTIONS
    }
}

/** 生产 shell：Ringtone（USAGE_NOTIFICATION，F4 不请求音频焦点）+ Vibrator。 */
private class DefaultShell(private val context: Context) : FeedbackPlayerShell {
    override fun play(soundUri: android.net.Uri, vibrationPattern: LongArray?) {
        if (soundUri != android.net.Uri.EMPTY) {
            runCatching {
                val ringtone = RingtoneManager.getRingtone(context, soundUri)
                ringtone?.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                ringtone?.play()
            }
        }
        vibrationPattern?.let { pattern ->
            runCatching {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            }
        }
    }
}