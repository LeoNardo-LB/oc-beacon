# ch07 · Room 持久化 —— 注解驱动的本地数据库

> 状态：⬜ 待生成 ｜ 前置：ch04, ch06
> 本章解决：Entity/DAO/Database 三件套、Flow 化查询、迁移策略；对照 JPA/Hibernate。
> 填充方式：对 AI 说「填充 docs/learning 的 ch07」，规范见 [../AGENTS.md](../AGENTS.md)

## 提纲要点
- @Entity/@Dao/@Database 对照 @Entity/@Repository/EntityManager
- DAO 返回 Flow<List<T>>：数据变化自动推送（JPA 做不到的事）
- suspend DAO 与事务
- Migration 版本升级策略；TypeConverter
- 本项目的 Zstd 消息压缩存储特例

## 本项目锚点（待填充时重新验证）
- `data/local/OcBeaconDatabase.kt:10` — @Database 主类
- `data/local/MessageDao.kt` / `LogDao.kt` 等 DAO
- `data/local/Migrations.kt`、`ZstdCodec.kt`

## 观察任务预告
- 找出一个返回 Flow 的 DAO 方法，追到 UI 消费端画全链路
- 阅读 Migrations.kt 总结历次 schema 演进
