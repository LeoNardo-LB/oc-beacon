# ch03 · Ktor Client 与 kotlinx.serialization —— 数据层的 HTTP 心脏

> 状态：⬜ 待生成 ｜ 前置：ch01, ch02
> 本章解决：HTTP 客户端怎么配置、JSON 怎么互转；对照 OkHttp/Jackson/Fetch。
> 填充方式：对 AI 说「填充 docs/learning 的 ch03」，规范见 [../AGENTS.md](../AGENTS.md)

## 提纲要点
- HttpClient 构建：engine 选择（为何钉死 OkHttp）、ContentNegotiation、Auth 插件
- Json 实例配置项逐个讲解（ignoreUnknownKeys 等）
- @Serializable DTO ↔ Jackson 注解对照；V1/V2 双版本端点封装
- suspend 函数发起请求的第一现场

## 本项目锚点（待填充时重新验证）
- `di/NetworkModule.kt:47` — provideHttpClient(json)，HttpClient(OkHttp)
- `di/NetworkModule.kt:33` — Json 实例
- `data/api/ApiClient.kt`
- `data/dto/common/ApiModels.kt:13` — @Serializable data class ModelSelection

## 观察任务预告
- 找出一个完整端点调用从 DTO 定义到 Repository 的链路
- 列出 Json 配置里每个 flag 的作用与不加的后果
