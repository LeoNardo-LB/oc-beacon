# ch01 · Kotlin for Java Developers —— 语法迁移地图

> 阅读时长：约 25–30 分钟（可按小节拆成碎片时间多次读完）｜ 前置：ch00a（沿用其 §0 的会话数据；示例统一标注 `▶ 输出`）
>
> 本章目标不是"学会 Kotlin"，而是**消灭语法盲区**——读完后，你打开本项目任何 `.kt` 文件，不应该再有"这个关键字是什么意思"级别的卡点。协程/Flow 只做语法速认，深入留给 ch04/ch05。

## 学习目标

- [ ] 能说出 `val` 与 `var` 的区别，并解释为什么 Kotlin 鼓励 `val`
- [ ] 看到 `?.`、`?:`、`!!`、`lateinit` 时能立刻反应出语义
- [ ] 能把一段 Java 类翻译成等价的 data class / object / sealed class
- [ ] 能识别扩展函数并说出它和 Java 工具类静态方法的本质区别
- [ ] 能读懂 Kotlin 集合链式操作（对照 Java Stream）
- [ ] 看到 `suspend fun` 和 `launch` 时知道"这是协程"，不慌

---

## 概念讲解

### §1 val / var 与类型推导 —— 对应 Java 的 final 局部变量 + var

```kotlin
val name = "oc-beacon"      // val = 只读引用 ≈ Java 的 final String name
var count = 0               // var = 可变引用 ≈ Java 的普通变量
count = 1                   // ✅ 重新赋值 OK
// name = "x"               // ❌ 编译错误：val 不能重新赋值

val timeout: Long = 30_000  // 显式类型；下划线数字分隔符是语法糖
val tags = listOf("a", "b") // 类型推导：List<String>，不用写
```

要点：
- **类型推导是编译器能力，Kotlin 仍是静态强类型语言**——没有 JS 那种动态类型。
- 团队惯例（也是整个 Android 圈的惯例）：**能用 `val` 就用 `val`**。你在本项目里会看到 90% 都是 `val`。
- 数字字面量支持 `30_000`、`0xFF`、`1.5e3`，和 Java 相同。

> ⚠️ 一个坑：`val` 保证的是**引用不可变**，不是对象不可变。`val list = mutableListOf(1)` 之后依然可以 `list.add(2)`。

### §2 null 安全 —— Java 的 NullPointerException 在编译期被拦下

Kotlin 类型系统区分"可空"与"不可空"：

```kotlin
var a: String = "hi"
// a = null                  // ❌ 编译错误：String 不可空

var b: String? = "hi"        // String? = 可空 String
b = null                     // ✅ OK

println(b?.length)           // ▶ 输出：null   安全调用：b 为 null 时整体返回 null（类型 Int?）
println(b?.length ?: 0)      // ▶ 输出：0      Elvis 运算符：左侧为 null 时取右侧 → Int
// b!!.length                // !! 断言非空：此刻 b 是 null，一跑就抛 NPE（代码坏味道）

val name: String? = "oc-beacon"
name?.let { println("昵称长度=${it.length}") }   // 惯用法：非空时才执行 lambda
// ▶ 输出：昵称长度=9        （若 name 为 null，这行静默跳过，不报错）
```

Java 心智对照表：

| Kotlin | Java 里你以前怎么写 |
|--------|---------------------|
| `b?.length` | `b != null ? b.length() : null` |
| `b ?: 0` | `b != null ? b : 0`（或 `Optional.ofNullable(b).orElse(0)`） |
| `b!!.` | `(String) 强转后硬上`——审查时看到要皱眉 |
| `lateinit var x: View` | 字段注入/"我知道它稍后才初始化"的场景，绕开可空检查 |
| 平台类型 `String!` | Java 互操作时编译器不知道 Java 方法会不会返回 null——**Java 调用方传 null 进来照样崩** |

最后一条最重要：**本项目大量与 Java/Android SDK 互操作**，从 SDK 返回的值默认是平台类型，Kotlin 不强制你判空——这是 Kotlin 版 NPE 的主要来源，别掉以轻心。

### §3 函数 —— 告别样板与重载地狱

```kotlin
// 单表达式函数：函数体就是 return，可省略花括号和 return
fun double(x: Int): Int = x * 2

// 默认参数 + 命名参数 —— Java 里的一堆重载在 Kotlin 里是一个函数
fun connect(host: String, port: Int = 8080, useTls: Boolean = false) { /*...*/ }
connect("10.0.2.2")                          // 用默认 port、useTls
connect("10.0.2.2", useTls = true)           // 跳过 port，指定 useTls（Java 做不到！）

// 顶层函数：不属于任何类，直接写在文件里（≈ 工具类静态方法，但更干净）
fun isBlank(s: String?) = s.isNullOrBlank()
```

**扩展函数**——本章最重要的新概念，Java 完全没有对应物：

```kotlin
// 给已有类型"外挂"方法，无需继承、无需改源码
fun String.truncate(max: Int): String =
    if (length <= max) this else take(max) + "…"

println("hello world".truncate(5))   // ▶ 输出：hello…
println("hi".truncate(5))            // ▶ 输出：hi    不超长就原样返回
```

本质：编译成静态方法调用，`this` 就是第一个参数。它**不能访问 private 成员**，所以不是破坏封装，只是语法糖化的工具函数。

### §4 类与对象 —— 四件套对照表

#### ① 主构造器：声明即字段

```java
// Java：字段、构造器参数、赋值三份样板
public class VcsChange {
    private final String file;
    private final int additions;
    public VcsChange(String file, int additions) { ... }
    public String getFile() { ... }
}
```

```kotlin
// Kotlin：一行搞定。构造器参数前写 val/var 就是字段
class VcsChange(val file: String, val additions: Int, val deletions: Int)
```

#### ② data class —— 你已经认识它了：Java record 的完全体

```kotlin
data class VcsChange(
    val file: String,
    val additions: Int,
    val deletions: Int,
    val status: VcsStatus,
)
```

自动生成 equals/hashCode/toString/copy/componentN——比 record 多一个 `copy()`，函数式更新利器：

```kotlin
enum class VcsStatus { MODIFIED, ADDED }   // Playground 里补这行即可运行
val old = VcsChange("build.gradle.kts", 10, 2, VcsStatus.MODIFIED)

val updated = old.copy(additions = old.additions + 1)   // 其余字段原样保留
println(updated)
// ▶ 输出：VcsChange(file=build.gradle.kts, additions=11, deletions=2, status=MODIFIED)
//          ↑ toString 自动生成——data class 白送的

println(old == old.copy())
// ▶ 输出：true     equals 也自动生成：逐字段比较值
```

> 尾随逗号 `,` 是 Kotlin 惯例，diff 友好。

与 Java record 的作用对照（record 心智模型可直接搬过来用）：

| 能力 | Java record | Kotlin data class |
|------|:---:|:---:|
| 一行声明纯数据载体 | ✅ | ✅ 相同 |
| 自动 equals/hashCode/toString | ✅ | ✅ 相同 |
| `copy(改某字段)` 式更新 | ❌ 手工 new | ✅ 独有利器 |
| 解构 `val (a, b) = obj` | ❌ | ✅ |
| 字段可变 var | ❌ 仅 final | 可（约定仍用 val） |

使用场景完全一致：DTO、事件、UI 状态、配置项。本项目 domain/model/ 与 data/dto/ 下几乎全是它。

#### ③ object 单例 & companion object —— static 的替代品

```kotlin
object PathUtils {                    // object = 线程安全的饿汉单例，全局唯一实例
    fun fileName(path: String): String = ...
}
PathUtils.fileName("/a/b.txt")        // 直接当类名用，没有 getInstance()

class Foo {
    companion object {                // ≈ Java 的 static 成员区
        const val TAG = "Foo"         // const val = 编译期常量 ≈ static final
    }
}
Foo.TAG                               // 调用处不用写 Companion
```

#### ④ sealed class —— "枚举加强版"，本项目的灵魂关键字

```kotlin
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Failure(val httpCode: Int) : ApiResult<Nothing>()   // 教学简化：真实项目是 ApiError 对象
}
```

语义：**所有子类必须定义在同一文件里**，编译器因此知道类型全集。配合 `when` 使用时，编译器强制你处理每种情况且不需要 `else`——新增子类时，漏处理的地方直接编译报错。这就是 Java `switch` 加 sealed（JDK 17 才有）做不到的穷举检查力度的日常化。

```kotlin
fun render(result: ApiResult<String>): String = when (result) {
    is ApiResult.Success -> "✅ ${result.data}"          // is = 类型判断+智能转换，直接 .data
    is ApiResult.Failure -> "❌ HTTP ${result.httpCode}"
}   // 不写 else！注释掉任一分支试试——编译器立刻报错，这就是穷举检查

println(render(ApiResult.Success("4 个会话")))
// ▶ 输出：✅ 4 个会话
println(render(ApiResult.Failure(404)))
// ▶ 输出：❌ HTTP 404
```

`when` 本身是**表达式**（有返回值），对比 Java 的 statement 式 switch 是思维转变点。

### §5 lambda 与集合 API —— Stream API 的近亲，但默认惰性集合

如果你写过 Java Stream，这一节 3 分钟毕业：

```kotlin
// Java:  users.stream().filter(u -> u.age > 18).map(User::name).collect(toList());
// Kotlin：没有 stream()/collect() 中间商，直接链式出结果（立即执行，返回新集合）
val names = users.filter { it.age > 18 }.map { it.name }

// 用 ch00a §0 的会话数据跑一遍：
val hot = sessions
    .filter { it.messageCount >= 15 }        // 筛选
    .sortedByDescending { it.messageCount }  // 排序（不改原列表）
    .take(2)                                 // 取前 2 个
    .map { "${it.id}(${it.messageCount}条)" }
println(hot)
// ▶ 输出：[s1(42条), s3(15条)]

// Map 解构遍历：
val byDir = sessions.groupBy { it.directory }
for ((dir, list) in byDir) println("$dir → ${list.size} 个")
// ▶ 输出：
// /home/me/oc-beacon → 2 个
// /home/me/blog → 1 个
// /home/me/demos → 1 个
```

差异点：
- `it` = 单参数 lambda 的隐式参数名（≈ 无需写 `u ->`）
- 链式操作**每次产生新集合**，没有 Stream 的惰性优化——超大数据集才需要 `asSequence()`
- `?:`、`?.` 可以混进链子里：`list.firstOrNull()?.name ?: "unknown"`

> 集合 API 的全量目录（每个都带输出案例）在 ch00a §6——本章只负责讲清"和 Stream 差在哪"。

### §6 作用域函数速查表 —— 读项目代码的最大"生词"

Kotlin 有 5 个魔法函数，让任何对象带着上下文执行代码块。初读代码 90% 的卡顿来自它们。背下这张表：

| 函数 | 引用自己 | 返回值 | 一句话记忆 | 典型场景 |
|------|---------|--------|-----------|---------|
| `let` | `it` | lambda 结果 | "非空就处理" | `x?.let { ... }` |
| `run` | `this` | lambda 结果 | "计算个东西" | 对象上跑一段逻辑 |
| `with` | `this` | lambda 结果 | "对这个家伙…" | `with(builder) { append("a") }` |
| `apply` | `this` | **对象本身** | "配置它，还给它" | 构造/配置 Intent、Builder |
| `also` | `it` | **对象本身** | "顺便干点事" | 打日志、副作用 |

最高频的是 `apply`（链式配置）和 `let`（空安全包裹）。背表没用——每个函数看一遍带输出的实例：

```kotlin
// let —— 非空才处理（空安全标配，lambda 参数是 it）
val token: String? = "abc123"
token?.let { println("Bearer $it") }
// ▶ 输出：Bearer abc123     （token 为 null 时整行静默跳过，不报错）

// apply —— 配置对象并返回它自己（Builder 场景之王，块内 this 就是对象）
data class Request(var url: String = "", var timeoutMs: Int = 0)
val req = Request().apply {
    url = "/api/sessions"       // 直接访问属性，不用写 req.url =
    timeoutMs = 30_000
}
println(req)
// ▶ 输出：Request(url=/api/sessions, timeoutMs=30000)

// also —— 顺便干点事（日志/校验），返回对象本身、链子不断（lambda 参数是 it）
val top = sessions.take(2).also { println("取了 ${it.size} 条") }.map { it.id }
// ▶ 中途输出：取了 2 条
println(top)
// ▶ 输出：[s1, s2]

// with —— 对一个对象连续操作后算个结果（块内 this；非扩展函数，直接传参）
val summary = with(req) { "$url 超时 ${timeoutMs / 1000}s" }
println(summary)
// ▶ 输出：/api/sessions 超时 30s

// run —— 与 with 同语义的调用形式：req.run { ... }（本项目里少见，认识即可）
```

### §7 协程语法速认 —— 只求不慌，ch04 再深入

在数据层代码里你会立刻遇到这些，先建立"识别力"：

```kotlin
suspend fun fetchSession(id: String): Session { ... }
// suspend = "这个函数可以暂停/恢复"。调用它必须也在 suspend 上下文里。
// 心智模型：≈ 一个能被框架调度挂起的同步方法，不是开新线程！

viewModelScope.launch {
    val s = repository.fetchSession(id)   // 挂起点：看起来同步，实际不阻塞线程
    _uiState.value = UiState.Loaded(s)
}

val flow: StateFlow<List<Session>> = repo.sessions   // 响应式数据流，UI 订阅它自动刷新
```

现在只需要记住三个词：**suspend = 可挂起的函数**、**launch = 启动一个后台任务**、**StateFlow = 可观察的状态容器**。细节是后面三章的事。

---

## 在本项目中对应（真实锚点，已验证）

学完概念立刻看真实代码，每个锚点对应上面一节：

| 概念 | 项目中的例子 |
|------|-------------|
| data class 一行定义 | `VcsChange`（domain/model/Vcs.kt:2）；`HomeUiState`（ui/screens/home/HomeViewModel.kt:41） |
| 可空类型 `String?` | `VcsBranchInfo(val branch: String?, ...)`（domain/model/Vcs.kt:4） |
| @Serializable DTO | `ModelSelection`（data/dto/common/ApiModels.kt:13）——data class 同时是 JSON 序列化载体，ch03 细讲 |
| sealed class | `SessionStatus`（domain/model/SessionStatus.kt:9）、`SseEvent`（domain/model/SseEvent.kt:13，260+ 行的事件家族）、`ApiResult<T>`（domain/model/ApiResult.kt:7） |
| object 单例 | `PathUtils`（util/PathUtils.kt:10）——跨平台路径工具，根 AGENTS.md 明文要求全项目用它 |
| 扩展函数 | `ProviderInfo.toDomain(): ProviderCatalog`（data/mapper/ProviderMapper.kt:50）——DTO→领域模型的转换惯用法；`ClipboardManager.copyToClipboard()`（util/ClipboardUtils.kt:14） |
| when 表达式 + sealed | `Part.typeName()`（data/local/MessageStore.kt:495） |
| StateFlow 预告 | `_uiState = MutableStateFlow(...)`（ui/screens/home/HomeViewModel.kt:61） |

## 观察任务（只读代码，写出文字答案即可）

1. **sealed 家族盘点**：打开 domain/model/SseEvent.kt，数出 SseEvent 下有多少个 data class 子类，挑 3 个读懂名字含义，一句话说明为什么这里必须用 sealed class 而不是普通继承。
2. **null safety 实地考察**：在 domain/model/ 目录 grep `String?`，找出 5 处可空字段，对每处回答：这个字段为什么可能是 null？（提示：服务器可能不给）
3. **扩展函数收集**：全项目 grep `\.[a-z][A-Za-z]+\(\): ` 或直接看 data/mapper/ 目录，找到至少 3 个 `xxx.toDomain()` / `xxx.toDto()` 扩展函数，总结这种模式的优点。
4. **作用域函数实战**：打开 MainActivity.kt，找出其中所有 `apply`/`let`/`also` 的使用处（如果有），逐个标注它在表里属于哪种语义。

## 常见坑

- **`==` 已经是值相等**（编译器帮你调 equals），`===` 才是引用相等。从 Java 过来的直觉在这里是反的。
- **三元运算符不存在**：`a ? b : c` 要写成 `if (a) b else c`（if 是表达式）或 `b.takeIf { a } ?: c`。
- **`!!` 不是"我确认非空"的正确姿势**，多数情况下应该用 `?:` 给兜底值或提前返回。
- 位运算符变了样：`& | ^ ~ << >>` 写成 `and or xor inv shl shr`（中缀函数）。
- Java 互操作的平台类型（SDK 返回值）不做空检查——本项目历史上有过 `%NR` 解码崩溃教训，空安全不是免死金牌。

## 我的笔记 / 疑问

<!-- 学到这里，把卡点、疑问、自己的话总结写在这里。下次续学先读这里。 -->

- 

### AI 回复区

<!-- AI 回答你的疑问时写在这里，不得擦除上方原始内容 -->
