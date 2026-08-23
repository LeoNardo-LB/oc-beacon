# ROADMAP — Kotlin & Android 学习路线图

> 配套：进度打卡看 [PROGRESS.md](PROGRESS.md) ｜ 目录规范看 [AGENTS.md](AGENTS.md)
>
> 设计原则：**项目驱动 + 数据层先行**。你是 Java 后端，先用熟悉的概念（分层、HTTP 客户端、序列化、DI）建立信心，最后才攻 Compose 这个真正的全新领域。

## 总览图

```
阶段一：读懂项目（Java 舒适区）
ch00a 语法核心 ──→ ch00b 语法进阶 ──→ ch01 Kotlin 迁移地图 ──┬─→ ch02 项目全景导览 ──→ ch03 Ktor+序列化 ──→ ch04 协程 ──→ ch05 Flow/StateFlow ──→ ch06 Hilt DI ──→ ch07 Room
                                                              （语言地基，后续一切的钥匙）

阶段二：攻下 Compose（全新领域）        ══ 转折点：从「读懂」到「能改」══
ch08 Compose 思维模型 ──→ ch09 状态管理 ──→ ch10 导航组装 ──→ ch11 副作用与性能

阶段三：架构深水区与独立开发
ch12 SessionStateService 单一真相源 ──→ ch13 SSE 流式管线 ──→ ch14 独立开发路标
```

## 各章一句话定位

### 阶段一 · 语言与数据层（目标：能独立读懂任意 Repository/ViewModel）

> ch00 与 ch01 的分工：**ch00 系统学语法**（每个点讲 90%+ 场景的主流写法，项目未用的特性明确标注），**ch01 讲差异思维**（Java 对照、惯用法、生词表）。先 ch00 后 ch01。

| 章 | 标题 | 解决的问题 | Java 世界对应物 |
|----|------|-----------|----------------|
| ch00a | [Kotlin 语法核心](01-java-to-kotlin/ch00a-kotlin-syntax-core.md) | 程序结构/基本类型操作/字符串模板/控制流/区间/集合体系 | — （语言地基） |
| ch00b | [Kotlin 语法进阶](01-java-to-kotlin/ch00b-kotlin-syntax-advanced.md) | 可见性/继承/object 表达式/枚举/泛型型变/委托/异常模型 + 符号速查表 | — （语言地基） |
| ch01 | [Kotlin for Java Developers](01-java-to-kotlin/ch01-kotlin-for-java-devs.md) | 拿到任何 .kt 文件不再有语法盲区；迁移思维与惯用法 | — （语言本身） |
| ch02 | 项目全景导览 | 三层架构怎么映射到包目录；一次点击的完整旅程 | Spring 的 Controller/Service/Repository 分层 |
| ch03 | Ktor Client 与 kotlinx.serialization | HTTP 请求+JSON 解析在这项目里怎么写的 | OkHttp/RestTemplate + Jackson |
| ch04 | 协程入门 | suspend 函数到底是不是线程？作用域怎么管 | CompletableFuture / ExecutorService |
| ch05 | Flow 与 StateFlow | 为什么 UI 能自动刷新；冷流热流之别 | Reactor Flux/Mono、RxJava |
| ch06 | Hilt 依赖注入 | @HiltViewModel/@Inject 怎么串起来的 | Spring @Component/@Autowired |
| ch07 | Room 持久化 | 注解驱动的 SQLite ORM | JPA/Hibernate/MyBatis |

### 阶段二 · Compose UI（目标：读懂任意 Screen 并做小改动）

| 章 | 标题 | 解决的问题 |
|----|------|-----------|
| ch08 | Compose 思维模型 | 声明式 vs 命令式；@Composable 函数为什么能"画"出界面；重组 |
| ch09 | 状态管理 | remember/mutableStateOf/状态提升；ViewModel 里 _uiState 模式 |
| ch10 | 导航与屏幕组装 | NavGraph 路由表；参数传递与 safeDecodeParam 防 crash |
| ch11 | 副作用与性能 | LaunchedEffect 家族；为什么 SSE 有那些滚动稳定性铁律 |

### 阶段三 · 架构深水区与独立开发（目标：理解承重设计 + 知道下一步学什么）

| 章 | 标题 | 解决的问题 |
|----|------|-----------|
| ch12 | SessionStateService 与 FSM | 项目最承重的架构决策：单一真相源 + 纯函数状态机 |
| ch13 | SSE 流式管线 | 48ms 批处理→高度补偿→渲染；铁律背后的因果链 |
| ch14 | 独立开发路标 | Activity 生命周期、Service、权限、发版——脱离本项目还需要什么 |

## 学习方法约定（针对「碎片+周末大块」节奏）

- **碎片时间（15-30 min）**：读一章的 1-2 个小节 → 完成 1 个观察任务 → 把卡点写进章末笔记区。
- **周末大块（2-4 h）**：连读 2-3 章 + 把前面攒的观察任务答案补全 + 让 AI 批改观察任务答案。
- **观察任务纪律**：任务是"找出并归类"式的只读任务，答案是几行字也必须写——纯阅读留存率极低，输出倒逼输入。
- **断片恢复**：任何时候回来，先看 PROGRESS.md 上次位置 → 读该章末尾笔记区 → 继续。

## 与 AI 协作的提示词模板

```text
填充 docs/learning 的 chXX（<章标题>）：
- 按 docs/learning/AGENTS.md §3 的章节模板和写作规范
- 项目佐证部分先实际读取代码验证再写
- 我当前卡点：<可选，来自上一章笔记区>
```
