package dev.leonardo.ocbeacon.domain.model

/**
 * 界面插槽键（#391 ServerAdapter 架构）——领域层纯枚举，不含界面框架类型。
 *
 * 适配器只**声明**自己在哪些插槽有内容；界面层的扩展接口与注册表由界面层持有，
 * 通用屏幕经组合局部取注册表渲染，不 import 任何具体服务器类型组件。
 */
enum class ServerUiSlot {
    /** 服务器设置页的插槽（类型私有设置分区）。 */
    SERVER_SETTINGS,

    /** 提供商设置页的插槽（类型私有提供商目录 / 凭据管理）。 */
    PROVIDER_SETTINGS,

    /** 聊天输入框附属区（附件 / 模式选择等）。 */
    CHAT_INPUT_ATTACHMENTS,

    /** 聊天工具栏（输入框上方的动作条）。 */
    CHAT_TOOLBAR,

    /** 会话列表头部（筛选 / 分组等）。 */
    SESSION_LIST_HEADER,
}
