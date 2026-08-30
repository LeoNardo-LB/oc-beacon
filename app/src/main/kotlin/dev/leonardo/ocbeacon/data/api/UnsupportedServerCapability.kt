package dev.leonardo.ocbeacon.data.api

/**
 * 服务器能力缺失异常（backlog #276；设计 §2.1 降级先例对齐）。
 *
 * DSH 52 方法面无对应方法的域操作（delete/share/revert/PTY/shell/文件内容读/
 * 配置写等）以此异常显式失败——与 V1ApiClient 的常量降级（false/空）互补：
 * 返回形态是布尔/列表且调用方期待"尽力而为"时走常量降级；返回形态是对象或
 * 静默成功会误导用户时抛本异常。message 面向日志（UI 文案走 strings.xml）。
 */
class UnsupportedServerCapability(
    val method: String,
    val serverType: String,
) : Exception("serverType=$serverType does not support '$method'")
