# ch00b · Kotlin 语法进阶 —— 可见性·继承·对象·泛型·委托·异常

> 阅读时长：约 25–30 分钟 ｜ 前置：ch00a
>
> 延续「主流用法优先」原则。学完本章 + ch01，读本项目代码不应再有语法级卡点。

## 学习目标

- [ ] 看到类/函数前的 `internal`/`open`/`override` 能说出含义与默认值
- [ ] 能读懂 `object : Interface { ... }` 匿名回调写法
- [ ] 看到 `out T` / `in T` 泛型签名不懵
- [ ] 理解 Kotlin 异常模型与 Java 的关键差异（无受检异常）
- [ ] 会用 `is`/智能转换，知道 `as` 何时危险

---

## 概念讲解

### §1 可见性修饰符 —— 默认值和 Java 相反！

| 修饰符 | 可见范围 | Java 对应 |
|--------|---------|-----------|
| （不写）= **public** | 全局可见 | ⚠️ Java 默认是包私有——这是迁移最大陷阱之一 |
| `private` | 类内/文件内 | 同 |
| `protected` | 类内+子类（不能用于顶层） | 同 |
| `internal` | **同模块内**（本项目整个 :app 就是一个模块） | 无对应物；≈"项目内公共、对外隐藏" |

90% 场景口诀：**对外 API 用 public，工具/辅助用 private，模块内共享用 internal**。

### §2 类与继承 —— Kotlin 默认一切不可继承

```kotlin
class Foo { }                    // 默认 final！Java 反过来默认可继承 ← 大坑
open class Base { }              // open 了才能被继承
class Impl : Base() { }          // 父类名后带括号 = 调用它的构造器
// class Bad : Base { }          // ❌ 编译报错！父类是 class 时括号不能省（等于没调构造器）
// 接口反过来：class A : ChatRepo {} 不带括号才是对的——接口没有构造器可调

abstract class Repo {            // 抽象类同 Java
    abstract fun fetch(): String // 抽象成员必须 override
    open fun log() { }           // 想被覆盖的也要 open
}

class Impl2 : Repo() {
    override fun fetch(): String = "x"   // ⚠️ override 是强制关键字（Java 可省略 @Override）
}
```

其他高频形态：

```kotlin
init { /* 主构造器执行时跑 */ }                 // 初始化块 ≈ 构造器体
constructor(x: Int) : this(x.toString())        // 次构造器必须委托主构造器（本项目少见，认识即可）

// 计算属性：看着像字段，实际每次访问现场计算（≈ Java 里写一个 getXxx() 方法）
class SessionList(val items: List<Session>) {
    val totalMessages: Int get() = items.sumOf { it.messageCount }
    //        ↑ 名字    ↑ get() 是固定语法不是名字的一部分 = "取值时执行右边代码"
}
// 调用处不带括号：println(sl.totalMessages)   ← 像读字段一样
// 何时用它：值能从其他数据推算出来、不想单独存一份（避免两份数据不同步）
//
// ── 三种属性形态 × Java 对照 ─────────────────────────────
// val x = 18            存储属性（构造时定死）   ≈ private final int x = 18; + getter
// val x get() = 算式    计算属性（每次现算）     ≈ 无字段的 int getX() { return 算式; }
// var x                 可变属性                ≈ 字段 + getter + setter
//
// 翻译口诀：看到 val x get() = ... → 脑内替换为"只有方法体的 getXxx()"；
// 反过来，Java 里纯计算的 getXxx()（如 list.size()/isEmpty()），Kotlin 全做成了属性 → 不要带括号！
//
// ── 完整可运行 Demo：存储属性(拍照) vs 计算属性(实时监控) ──
// data class Item(val name: String, val price: Int)
// class Cart(val items: MutableList<Item>) {
//     val storedCount: Int = items.size        // 存储属性：构造那刻的快照
//     val liveCount: Int get() = items.size    // 计算属性：每次访问现查
// }
// fun main() {
//     val cart = Cart(mutableListOf(Item("书", 50)))
//     println(cart.storedCount)   // ▶ 1    println(cart.liveCount)  // ▶ 1
//     cart.items.add(Item("笔", 5))            // 加一件商品
//     println(cart.storedCount)   // ▶ 1 ← 死在构造那一刻！
//     println(cart.liveCount)     // ▶ 2 ← 每次现查，永远新鲜
// }
// 决策口诀：值由别的数据推算且那些数据会变 → 计算属性 get()；值一辈子不变 → 存储属性
// ⚠️ 常见误解：get() ≠ "提前算好缓存起来"！恰恰相反——每次访问都重新执行，读几次算几次。
//    真·只算一次的两种写法：存储属性 val x = 算式（构造时算）；by lazy { }(首次访问算，之后吃缓存)
```

### §3 接口 —— 带"默认方法"无需任何关键字

```kotlin
interface ChatRepo {
    fun fetch(id: String): Session              // 抽象（无实现）
    fun refresh() { /*...*/ }                   // ✅ 直接给实现 = Java 的 default 方法，但不用写 default
}
class KtorChatRepo : ChatRepo {                  // 实现接口不带括号！接口没有构造器
    override fun fetch(id: String): Session = ...
}
```

同时实现多个接口出现同名成员冲突时，必须 override 并可用 `super<A>.foo()` 指定父类（少见，认识即可）。

### §4 object 表达式 —— Android 匿名回调的唯一写法（超高频！）

Java 里 `new Callback() {...}` 匿名内部类，Kotlin 一律写成 `object : 类型 {...}`：

```kotlin
val handler = object : UriHandler {
    override fun openUri(uri: String) { ... }
}

// 继承带构造参数的类也行：
val m = object : Migration(1, 2) {              // Migration(1, 2) 是构造调用
    override fun migrate(db: SupportSQLiteDatabase) { ... }
}
```

Compose 里 onClick 等 lambda 参数本质也是这类回调的简化形态。**这个语法你每天都会见到几十次**，务必形成条件反射。

### §5 嵌套类 vs inner class —— 默认行为又和 Java 相反

```kotlin
class Outer {
    class Nested { }         // ≈ Java 的 static nested class（不持外部引用）← 默认！
    inner class Inner { }    // 必须显式 inner 才持有外部引用 ≈ Java 的普通内部类
}
Outer.Nested()               // 嵌套类直接 new，不需要外部实例
```

90% 场景只用嵌套类（sealed/data class 分组放类里就是这个用法，如 SseEvent 的子类们）。inner 因内存泄漏风险极少用——**本项目未使用**，认识即可。

### §6 枚举 enum class —— 比 Java 紧凑，when 穷举绝配

```kotlin
enum class ContentType { TEXT, BINARY }

enum class SessionViewMode { FOLDER, RECENT }

// 可带构造参数与方法（同 Java）：
enum class Status(val label: String) {
    IDLE("空闲"), BUSY("运行中");
    val isBusy: Boolean get() = this == BUSY
}

fun cn(s: Status): String = when (s) {
    Status.IDLE -> "待机"
    Status.BUSY -> "忙碌"
}                          // 枚举穷举分支，不需要 else——漏写一个直接编译错
println(cn(Status.BUSY))   // ▶ 输出：忙碌
```

枚举 + when 不需要 else 即穷举（编译器检查），新增枚举值漏分支直接编译错——和 sealed 同款安全网。区别：枚举值是有限单例集合；sealed 子类可以各自携带不同结构的数据。

### §7 泛型与型变 —— 只需要认识 out/in/* 三个符号

```kotlin
fun <T> first(list: List<T>): T             // 泛型函数声明同 Java

List<out T>   // out = 协变：只生产 T ≈ Java 的 ? extends T
Consumer<in T> // in = 逆变：只消费 T ≈ Java 的 ? super T
List<*>       // 星投影：不知道也不关心 T ≈ Java 的 <?>

// 使用处型变（调用点才约束）也支持，同 Java 通配符语义：
fun copy(from: Array<out String>, to: Array<in String>)
```

读代码心法：看到 `Flow<out T>`、`sealed class ApiResult<out T>` 这类签名，翻译成"`? extends`"即可继续读，不必深究声明处型变的编译器细节（那是写库的人才需要的知识）。

### §8 属性委托 by —— 认识两种主流形态就够

```kotlin
// 形态①：by lazy —— 首次访问才初始化且缓存（线程安全）
val config: Config by lazy { loadConfig() }
// ⚠️ 本项目未使用此形态（已验证全仓库无 by lazy）；但社区代码极高频，必须认识

// 形态②：ViewModel 获取。社区主流有两种写法：
val viewModel: HomeViewModel = hiltViewModel()      // 直接调用式 ← 本项目的选择（SessionListRoute.kt:17）
val viewModel: HomeViewModel by hiltViewModel()     // by 委托式 ← 其他很多项目的选择
// 两者效果几乎等价，风格差异而已——读到别处代码不要慌
```

自定义委托协议（getValue/setValue）、`Delegates.observable`——写库场景，认识符号即可。

### §9 异常模型 —— Java 后端最大的思维转变点

**Kotlin 没有受检异常**：没有 `throws` 子句，编译器不强制捕获或声明。你的 try/catch 直觉全部适用，但"看签名就知道会抛什么"的能力消失了——文档和约定来补位。

```kotlin
// try/catch/finally 结构同 Java，但 try 是表达式：
val code = try { parse(input) } catch (e: Exception) { -1 }

// throw 也是表达式 → Elvis 兜底惯用法（90% 场景）：
val id = dto.id ?: error("id missing")      // error() 抛 IllegalStateException
require(input.isNotBlank())                  // 前置校验抛 IllegalArgumentException

// runCatching：把异常包装成 Result 风格（≈ Scala Try / Java 的 Optional 心智）：
println(runCatching { "42".toInt() })
// ▶ 输出：Success(value=42)

val e = runCatching { "x".toInt() }        // NumberFormatException 不再抛出，而是被装进值里
println(e.getOrElse { "解析失败" })
// ▶ 输出：解析失败
```

> 🏗️ **本项目的真实教训**（util/RunCatchingCancellable.kt）：`runCatching {}` 会把协程的 `CancellationException` 也吞掉——被取消的任务假装成功继续跑，导致 beta 真机崩溃（#128 根因）。项目为此写了 `runCatchingCancellable {}` 替代。**记住这条铁律：在协程里包裹网络调用，永远优先考虑取消语义**（ch04 展开）。

### §10 is / as 与智能转换 —— 编译器替你强转

```kotlin
val obj: Any = "oc-beacon"
if (obj is String) println(obj.length)     // ▶ 输出：9    is 之后自动当 String 用，免强转！

val n: Any = 7
val s1 = n as? String                      // 安全转换：类型不符返回 null，不抛异常
println(s1 ?: "不是字符串")                 // ▶ 输出：不是字符串
// val s2 = n as String                    // 不安全版：这里一跑就抛 ClassCastException
```

智能转换失效的少数场景（需手工 `as`）：可变字段 `var`（可能被多线程改）、带自定义 getter 的属性。遇到时理解为什么即可。

#### 附：解构声明 —— `val (sessionId, messageId) = key` 是什么？

**作用：一行同时取出多个值，分别装进多个变量**（效果同 TS 解构）。只有三个主流用途：

```kotlin
// 用途① 接住返回"两个东西"的调用，省去 .first/.second 两行
data class MessageKey(val sessionId: String, val messageId: String)
val key = MessageKey("s1", "m42")
val (sid, mid) = key
println(mid)              // ▶ 输出：m42

val (_, onlyId) = key     // 不想要的分量用 _ 扔掉
println(onlyId)           // ▶ 输出：m42

val (num, word) = 1 to "one"     // Pair 同样能拆
println("$num=$word")            // ▶ 输出：1=one

// 用途② 带下标遍历
for ((i, v) in listOf("a", "b").withIndex()) println("$i:$v")
// ▶ 输出：0:a  （下一行）1:b

// 用途③ Map 遍历时键值一起拿
sessions.groupBy { it.directory }.forEach { (dir, list) -> println("$dir: ${list.size}个") }
```

使用边界：能用 `(a, b)` 这样拆的只有 **data class / Pair / Map.Entry**，普通类不行（编译报错）。够用了。

本项目真实用例（已验证）：
- `val (items, _) = V2ResponseWrapper.unwrapList(root)`（data/api/v2/V2ApiClient.kt:142）——只要列表，第二个分量丢弃
- `for ((index, msg) in messages.withIndex())`（ui/screens/chat/util/TurnGroupCalculator.kt:23）
- `for ((key, deltas) in byMessage)`（data/repository/handler/MessageEventHandler.kt:208）

### §11 符号速查总表 —— 读代码前扫一眼

| 符号 | 含义 | 出处 |
|------|------|------|
| `?.` `?:` `!!` | 安全调用 / Elvis / 断言非空 | ch01 §2 |
| `$x` `${expr}` | 字符串模板 | ch00a §3 |
| `..` `..<` `downTo` `step` | 区间 | ch00a §5 |
| `->` | when 分支 / lambda 箭头 | ch00a §4 |
| `::` | 引用：`Foo::class`、`::function` | 本章+ch06 |
| `is` `!is` `as` `as?` | 类型判断/转换 | 本章 §10 |
| `in` `!in` | 区间/集合 membership；for-in | ch00a §5 |
| `to` | infix 造 Pair | ch00a §7 |
| `*` | vararg 展开 / 星投影 | 本章 §7 |
| `out` `in`（泛型处） | 型变 | 本章 §7 |
| `by` | 属性委托 | 本章 §8 |
| `@Xxx` | 注解（@Serializable/@Composable…） | ch03 起 |
| `` `name` `` | 反引号转义标识符（测试方法名常用） | 见测试代码 |
| `object : X` | 匿名对象 | 本章 §4 |
| `(a, b) =` | 解构声明：一行拆出多个值，`_` 忽略不要的 | 本章 §10 附 |
| `it` / `_` | 单参 lambda 默认名 / 未使用的占位 | ch01 §5 |

---

## 在本项目中对应（真实锚点，已验证）

| 概念 | 项目中的例子 |
|------|-------------|
| internal | `internal object HighlightBuilder`（ui/screens/viewer/HighlightBuilder.kt:18）；`internal fun argbToHex(...)`（ui/screens/viewer/CodeWebView.kt:312）；`internal fun BatteryOptimizationBanner(...)`（ui/screens/home/components/BatteryOptimizationBanner.kt:16） |
| enum class | `ContentType { TEXT, BINARY }`（domain/model/FileContent.kt:3）；`SessionViewMode { FOLDER, RECENT }`（ui/screens/sessions/SessionListUiState.kt:10） |
| object 表达式 | `object : UriHandler {...}`（ui/screens/chat/LinkUriHandler.kt:48）；`object : Migration(1, 2)`（data/local/Migrations.kt:8）；匿名 LinkedHashMap 子类（ui/screens/workspace/WorkspaceViewModel.kt:45） |
| hiltViewModel 直接调用式 | `val viewModel: SessionListViewModel = hiltViewModel()`（ui/screens/sessions/SessionListRoute.kt:17） |
| runCatching 及其坑 | MainActivity.kt:191；反模式说明 util/RunCatchingCancellable.kt:8,23-24 |
| 泛型容器 | `ApiResult<T>`（domain/model/ApiResult.kt:7）；`SseEvent` sealed 家族（domain/model/SseEvent.kt:13） |

## 观察任务（只读代码，写出文字答案）

1. **internal 盘点**：grep `^internal` 找出所有 internal 声明，回答：它们为什么不设为 public？（提示：谁会 import 它们？）
2. **匿名对象收集**：打开 data/local/Migrations.kt，数出共有几个 `object : Migration(...)`，说明每个的版本区间；再找 LinkUriHandler.kt:48 回答它匿名实现了什么接口。
3. **异常策略考察**：读 util/RunCatchingCancellable.kt 全文注释，用自己的话复述：为什么裸 runCatching 在协程里是反模式？
4. **enum × when**：找到 ContentType 的消费处（grep `ContentType.` 或看 MessageStore），确认对应 when 是否穷举了两个值、有没有 else。

## 常见坑

- 忘写 `open` 导致继承编译报错；忘写 `override` 同样报错——两条都是 Java 迁移高频首撞墙。
- 接口实现 `class A : B` 不带括号，类继承 `class A : B()` 要带括号——括号有无是"调不调构造器"的区别。
- 在协程里用裸 `runCatching` 吞掉取消（本项目 #128 血泪史）。
- 期望"包私有"语义而省略修饰符——结果类变成了全局 public。
- `as?` 之后忘记配 `?:`，null 一路传播到深处才炸。

## 我的笔记 / 疑问

<!-- 学到这里，把卡点、疑问、自己的话总结写在这里。下次续学先读这里。 -->

- 

### AI 回复区

<!-- AI 回答你的疑问时写在这里，不得擦除上方原始内容 -->
