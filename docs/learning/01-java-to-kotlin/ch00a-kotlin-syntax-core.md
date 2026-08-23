# ch00a · Kotlin 语法核心 —— 程序结构·类型·字符串·控制流·集合

> 阅读时长：约 30–40 分钟（代码块占大半，可拆成 2-3 次碎片时间读完）｜ 前置：无
>
> **本章所有例子围绕同一组"会话数据"展开**（§0 定义一次，全章复用），每段代码都标注了 `▶ 输出`——建议边读边在脑子里（或 Kotlin Playground 里）运行它。
>
> 本章与 ch01 的分工：本章系统过一遍语法地基（每个点讲 90%+ 场景的主流写法），ch01 再讲 Java→Kotlin 的思维差异与惯用法。项目里没用到的特性会明确标注「本项目未使用」。

## 学习目标

- [ ] 看懂任意 .kt 文件的骨架：package/import/顶层声明
- [ ] 掌握基本类型操作的主流写法（尤其类型转换）
- [ ] 能读写 `"$变量"` 字符串模板与常用 String API
- [ ] 能用 when/for/区间写出并读懂地道 Kotlin
- [ ] 分清 List/Set/Map 只读与可变两族，会用 10 个高频集合函数——**每个都见过带输出的完整案例**

---

## §0 本章统一案例数据

先别纠结语法细节，把下面这段当"已知条件"。它定义了一种会话记录 + 一份样例数据，之后每一节的例子都在操作这份数据：

```kotlin
data class Session(                 // data class 先理解为"一组字段的记录"，ch01 §4 详讲
    val id: String,
    val directory: String,          // 会话所在的项目目录
    val messageCount: Int,
    val status: String,             // "busy" 或 "idle"
    val updatedAt: Long,            // 毫秒时间戳
)

val sessions = listOf(              // listOf(...) 建一个只读列表
    Session("s1", "/home/me/oc-beacon", 42, "busy", 1724300001000),
    Session("s2", "/home/me/blog",       7, "idle", 1724300002000),
    Session("s3", "/home/me/oc-beacon", 15, "idle", 1724300003000),
    Session("s4", "/home/me/demos",      0, "idle", 1724300004000),
)
```

---

## 概念讲解

### §1 程序结构 —— 文件长什么样

```kotlin
package dev.leonardo.ocbeacon.util          // 包声明 = 目录路径，必须一致

import android.content.ClipboardManager      // 同 Java
import java.io.File as JFile                 // ✅ 主流技巧：as 别名解决类名冲突

class Foo { ... }        // 顶层声明：类、函数、属性都可以不套类直接写
fun topFunction() { ... }
val topVal = "I'm a singleton"
```

- **没有分号**——行尾不写，一行多语句才用分号隔开（少见）。
- **一个文件可以放多个类**：本项目惯例是相关的小类放一起（如 Vcs.kt 放两个 data class）。
- 程序入口是 `fun main()`（App 开发中由框架接管，你只在测试里见到）。

### §2 基本类型与操作 —— 和 Java 同源，但有三处不同

| 类型 | 对应 Java | 说明 |
|------|-----------|------|
| `Int` / `Long` / `Short` / `Byte` | int/long/short/byte | 位宽相同；首字母大写（一切都是对象） |
| `Double` / `Float` | double/float | 字面量 `1.5` 默认 Double，Float 要写 `1.5f` |
| `Boolean` / `Char` | boolean/char | Char **不是数字**，不能当 Int 用 |

三处不同（90% 的编译报错来自第一条）：

```kotlin
// ① 无隐式拓宽转换！Java 的 long x = intVal 在 Kotlin 编译不过
val i: Int = 100
// val l: Long = i          // ❌ 编译错误
val l: Long = i.toLong()     // ✅ 必须显式：toInt()/toLong()/toFloat()/toString()...
val text = "count=" + i.toString()

// ② 位运算是单词函数，不是符号
println(1 shl 2)      // ▶ 输出：4      （shl = << 左移）
println(6 and 3)      // ▶ 输出：2      （and = & 按位与）
println(6 or 3)       // ▶ 输出：7      （or  = | 按位或）

// ③ == 就是值相等（自动调 equals），=== 才比引用 —— 与 Java 直觉相反
```

其余算术/比较/逻辑运算符（`+ - * / % < > && || !`）与 Java 完全一致。注意整数除法截断：`println(7 / 2)` ▶ 输出：`3`。

### §3 字符串与模板 —— 比字符串拼接优雅十倍

```kotlin
val host = "10.0.2.2"
val port = 4199

// ✅ 90% 场景：模板插值，$ 直接嵌变量，复杂表达式用 ${ }
val url = "http://$host:$port/api"
println(url)
// ▶ 输出：http://10.0.2.2:4199/api

println("会话数=${sessions.size}, 首个=${sessions.first().id}")
// ▶ 输出：会话数=4, 首个=s1        ${} 里可以放任意表达式，甚至函数调用

// 三引号原始字符串：不转义、可换行 —— 写 JSON 样例/正则的主流选择
val json = """
    {"host": "$host", "port": $port}
""".trimIndent()
println(json)
// ▶ 输出：
// {"host": "10.0.2.2", "port": 4199}
```

高频 String API——**每条都配真实输入输出**：

```kotlin
// 切分 split：返回 List<String>（Java 返回数组）
val parts = "/home/me/oc-beacon".split("/")
println(parts)
// ▶ 输出：[, home, me, oc-beacon]      （开头那个 / 产生空串）

// joinToString：集合拼成展示文案（超高频！日志/UI 全靠它）
println(sessions.joinToString(", ") { it.id })
// ▶ 输出：s1, s2, s3, s4               { it.id } 是尾随 lambda，见 §6 map 讲解

// isBlank：空白判断（含纯空格），常配 ?. 做 null 安全判断
println("".isBlank())          // ▶ 输出：true
println("  ".isBlank())        // ▶ 输出：true
println("hi".isBlank())        // ▶ 输出：false

// take(n)：安全取前 n 个字符，不怕越界（Java substring 要手工防越界）
println("/home/me/blog".take(5))       // ▶ 输出：/home
println("ab".take(99))                  // ▶ 输出：ab   （不够长就全给，不抛异常）

// substringBefore / substringAfter：按分隔符取前段/后段
println("/home/me/blog".substringBeforeLast("/"))   // ▶ 输出：/home/me
println("/home/me/blog".substringAfterLast("/"))    // ▶ 输出：blog

// removePrefix / removeSuffix：去前后缀，不匹配就原样返回
println("s1".removePrefix("s"))        // ▶ 输出：1
println("abc".removePrefix("x"))       // ▶ 输出：abc

// contains / in：包含判断
println("oc-beacon".contains("bea"))           // ▶ 输出：true
println("bea" in "oc-beacon")                  // ▶ 输出：true （in 是 contains 的中缀写法）
```

### §4 控制流 —— if/when 都是表达式，这是最大的思维转变

**if 是表达式**（有返回值），所以没有三元运算符：

```kotlin
val s1 = sessions[0]
val label = if (s1.status == "busy") "运行中" else "空闲"
println(label)
// ▶ 输出：运行中
```

**when 是 switch 的全能升级版**——四种主流用法逐一演示：

```kotlin
// ① 按值分支（不用 break！不会穿透）
fun codeName(code: Int): String = when (code) {
    200 -> "OK"
    404, 410 -> "没了"                    // 多值合并到一个分支
    else -> "其他"                         // else ≈ default
}
println(codeName(404))
// ▶ 输出：没了

// ② 按 in 区间分支（switch 做不到）
fun grade(n: Int): String = when {
    n in 0..59 -> "不及格"
    n in 60..89 -> "良好"
    else -> "优秀"
}
println(grade(75))
// ▶ 输出：良好

// ③ 按 is 类型分支：is 之后智能转换，无需强转
fun describe(x: Any): String = when (x) {   // Any ≈ Java 的 Object
    is String -> "文本，长度 ${x.length}"    // x 已被当作 String，直接 .length
    is Int -> "数字，平方 ${x * x}"
    else -> "未知"
}
println(describe("hi"))
// ▶ 输出：文本，长度 2
println(describe(7))
// ▶ 输出：数字，平方 49

// ④ 无参 when：≈ if-else if 链的地道替代
fun summary(): String = when {
    sessions.any { it.status == "busy" } -> "有会话在跑"
    sessions.all { it.messageCount == 0 } -> "全是空会话"
    else -> "全部空闲"
}
println(summary())
// ▶ 输出：有会话在跑        （any/all 见 §6，这里先看 when 结构）
```

（第⑤种用法 sealed class 穷举是本项目灵魂，留给 ch01 §4 重点讲。）

**for 只有 for-in 一种形态**——没有 C 式 `for(i=0;;i++)`：

```kotlin
for (s in sessions) {
    println("${s.id}: ${s.messageCount} 条消息")
}
// ▶ 输出：
// s1: 42 条消息
// s2: 7 条消息
// s3: 15 条消息
// s4: 0 条消息

while (cond) { ... }      // do-while 与 Java 相同
break / continue          // 用法同 Java（标签 label@ 极少见，认识即可）
```

### §5 区间与迭代工具 —— 数字循环的地道写法

```kotlin
// 四种区间构造
println(1..5)             // ▶ 输出：1..5      闭区间 [1,5]
println((1..<5).toList()) // ▶ 输出：[1, 2, 3, 4]   半开区间（旧写法 1 until 5）
println((5 downTo 1 step 2).toList())
// ▶ 输出：[5, 3, 1]      倒序+步长

// in 做成员判断
println('e' in 'a'..'z')  // ▶ 输出：true

// 实战：要下标遍历的三种姿势
for (i in sessions.indices) print("${i} ")         // ▶ 输出：0 1 2 3
for ((i, s) in sessions.withIndex()) {              // 同时拿下标和值
    if (i == 1) println("第${i}个是 ${s.id}")       // ▶ 输出：第1个是 s2
}
sessions.forEachIndexed { i, s -> /* 同 withIndex */ }
```

口诀：**遍历值用 for-in，要下标用 indices/withIndex，倒序 downTo，别再手写 `i++`**。

### §6 集合体系 —— 先分两族，再逐个吃透高频 API

#### 6.1 两族接口与工厂函数

`List/Set/Map`（只读）vs `MutableList/MutableSet/MutableMap`（可变）。**选工厂函数就是选族群**：

```kotlin
val list = listOf("a", "b")          // 只读 —— 90% 场景用它
val mList = mutableListOf("a")       // 需要增删改才用 mutable
mList.add("b")                       // ✅ mutable 才有 add
// list.add("c")                     // ❌ 编译错误：只读列表没有 add

val map = mapOf("host" to 4199, "tls" to false)   // to 造键值对，§7 揭秘
println(map["host"])                 // ▶ 输出：4199     下标 [] 就是 get
println(map.getOrElse("port") { 80 }) // ▶ 输出：80      key 不存在时给兜底值
```

> ⚠️ 关键真相：「只读」接口只是**视图约定**（≈ `Collections.unmodifiableList`），不是真不可变。拿到只读引用的人不能改，但不代表背后没人改。

#### 6.2 高频 API 逐个实战（统一用 §0 的 sessions 数据）

**map —— 变换每个元素**（Stream 的 map，但没有 stream()/collect() 中间层）：

```kotlin
val ids = sessions.map { it.id }          // it 是 lambda 的隐式单参数名
println(ids)
// ▶ 输出：[s1, s2, s3, s4]

val counts = sessions.map { it.messageCount * 10 }
println(counts)
// ▶ 输出：[420, 70, 150, 0]
```

**filter —— 筛选**：

```kotlin
val busyOnes = sessions.filter { it.status == "busy" }
println(busyOnes.map { it.id })
// ▶ 输出：[s1]

val fromBeacon = sessions.filter { it.directory.endsWith("oc-beacon") }
println(fromBeacon.map { it.id })
// ▶ 输出：[s1, s3]
```

**链式组合**（每次产生新集合，立刻执行，无惰性优化）：

```kotlin
val hotIds = sessions
    .filter { it.messageCount > 5 }
    .sortedByDescending { it.updatedAt }   // 按 updated 倒序
    .take(2)                               // 取前 2 个
    .map { it.id }
println(hotIds)
// ▶ 输出：[s3, s2]
```

**firstOrNull / find —— 安全取单个**（空结果返回 null 而不是炸异常，配 `?:` 兜底）：

```kotlin
val emptyOne = sessions.find { it.messageCount == 0 }
println(emptyOne?.id)
// ▶ 输出：s4

val missing = sessions.firstOrNull { it.status == "error" }
println(missing?.id ?: "没有 error 会话")
// ▶ 输出：没有 error 会话
// missing?.id ?: "..." 读作：missing 为 null 时整个表达式的值是右边的字符串（Elvis，ch01 §2）
```

**any / all / none / count —— 布尔统计**：

```kotlin
println(sessions.any { it.status == "busy" })        // ▶ 输出：true   存在即真
println(sessions.all { it.messageCount > 0 })        // ▶ 输出：false  s4 是 0
println(sessions.none { it.directory.isBlank() })    // ▶ 输出：true   一个都没有
println(sessions.count { it.status == "idle" })      // ▶ 输出：3
```

**groupBy / associateBy —— 转 Map**（注意两者区别！）：

```kotlin
// groupBy：按 key 分组 → Map<String, List<Session>>，一对多
val byDir = sessions.groupBy { it.directory }
println(byDir.keys)
// ▶ 输出：[/home/me/oc-beacon, /home/me/blog, /home/me/demos]
println(byDir["/home/me/oc-beacon"]?.size)
// ▶ 输出：2        该目录下有 2 个会话

// associateBy：以某字段为 key → Map<String, Session>，一对一（重复 key 后者覆盖前者！）
val byId = sessions.associateBy { it.id }
println(byId["s2"]?.directory)
// ▶ 输出：/home/me/blog
```

**partition —— 一分为二**：

```kotlin
val (active, idle) = sessions.partition { it.status == "busy" }
// 解构声明：直接把 Pair 拆成两个变量
println(active.size)     // ▶ 输出：1
println(idle.size)       // ▶ 输出：3
```

**sumOf / maxOfOrNull / sortedBy —— 数字聚合**：

```kotlin
println(sessions.sumOf { it.messageCount })
// ▶ 输出：64

val newest = sessions.maxOfOrNull { it.updatedAt }
println(newest != null)
// ▶ 输出：true     maxOfOrNull 在空集合时返回 null（maxOf 会炸）

val byCount = sessions.sortedByDescending { it.messageCount }.map { it.id }
println(byCount)
// ▶ 输出：[s1, s3, s2, s4]      sortedBy 正序 / sortedByDescending 倒序，均不改原列表
```

**fold —— 聚合成单值**（≈ reduce 但带初值）：

```kotlin
val report = sessions.fold(StringBuilder()) { acc, s ->
    acc.append(s.id).append('(').append(s.messageCount).append(") ")
}
println(report.toString().trim())
// ▶ 输出：s1(42) s2(7) s3(15) s4(0)
```

**joinToString 收官**——把上面一切串起来（真实日志/UI 场景）：

```kotlin
println(
    sessions
        .filter { it.messageCount > 0 }
        .joinToString(" | ") { "${it.id}:${it.messageCount}条" }
)
// ▶ 输出：s1:42条 | s2:7条 | s3:15条
```

#### 6.3 数组 —— 与 Java API 互操作才用

```kotlin
val arr = arrayOf("a", "b")        // Array<String>（对象数组）
val ints = intArrayOf(1, 2, 3)     // IntArray（原生 int[]，避免装箱；调 Java 方法时需要）
println(ints.sum())                // ▶ 输出：6
```

日常业务代码 90% 用 List，遇到 Android SDK 要求数组参数时再临时 `toIntArray()`。

### §7 函数补遗 —— Unit、vararg、infix

```kotlin
fun save(): Unit { ... }     // Unit ≈ void，但是真类型；不写返回类型默认就是 Unit

vararg ids: String           // 可变参数 ≈ Java 的 String...
connect("host", "a", "b")    // 调用方可传任意个数
foo(*stringArray)            // * 展开操作符：把数组拆开传入 vararg（Java 无对应）

infix fun Int.pow(n: Int): Int = ...    // infix：让单参函数变成中缀语法
println(2 pow 10)                        // ▶ 输出：1024   不用点和括号
```

`to` 的真相值得记住——你已经天天在见了：

```kotlin
val pair = "host" to 8080        // "host".to(8080)，标准库 infix 函数
println(pair.first)              // ▶ 输出：host
println(pair.second)             // ▶ 输出：8080
val m = mapOf(pair, "tls" to true)   // mapOf 就靠 Pair 建表
```

读到任何"中间没点没括号连着的两个词"（`shl`、`downTo`、`step`、`to`），都是 infix。

---

## 在本项目中对应（真实锚点，已验证）

| 概念          | 项目中的例子                                                                                                                                                                 |
| ----------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 字符串模板 `${}` | `"PerfMon attached: refresh=${refresh}Hz budget=${1000f / refresh}ms"`（MainActivity.kt:182）；多变量插值 `"serverId=$serverId sessionPath=$sessionPath"`（MainActivity.kt:281） |
| when 表达式    | `Part.typeName()`（data/local/MessageStore.kt:495）                                                                                                                      |
| 集合链 + 只读族   | 各 RepositoryImpl 返回 `List<Session>` 处处可见（domain/repository/ 下接口签名）                                                                                                     |
| infix `to`  | `mapOf(... to ...)` 构建请求参数处高频出现（data/api/ 目录随手可见）                                                                                                                      |

## 观察任务（只读代码，写出文字答案）

1. **模板考古**：打开 MainActivity.kt 第 170–300 行，找出 3 处字符串模板，区分哪处用了 `$变量`、哪处用了 `${表达式}`。
   > 我找到了好多处，但好像变量都使用 ${xxx}，像是计算一个表达式，而 $xxx 似乎只有在方法调用（无参）与引用单个变量的时候用？他们事实上的差别就是这样的吗？
2. **when 分类**：读 data/local/MessageStore.kt:495 的 typeName()，回答它是 §4 四种 when 用法里的哪种？如果漏一个分支会怎样？
   > 第三种用法智能转换吧？如果露了一个那就会无法识别？但看上去没有兜底的方法？是否要有一个兜底的方法比较好？
3. **joinToString 收集**：全项目 grep `joinToString(`，找 2 处使用，说明各自的分隔符和 lambda 参数作用。
   > 我大概了解这个用法，数组转字符串，类似Java中流的join
4. **两族辨析**：grep `mutableListOf|mutableMapOf`，挑 1 处回答：为什么这里必须用可变族？
   > 后续要对数组或map的成员进行变动
5. **API 对号入座**：在本项目里找一处 `groupBy` 或 `associateBy` 的调用，对照 §6.2 说明它的 key 和 value 各是什么。

## 常见坑

- `Int` → `Long` 忘写 `.toLong()`（Java 肌肉记忆重灾区）。
- `split(".")` 参数是**字符串**不是正则——Kotlin 行为和你的直觉一致，反而不用像 Java 那样转义。
- `list[999]` 越界照样抛异常；安全取值用 `getOrNull(999)`。
- 只读集合 ≠ 不可变：防御性设计时若担心调用方绕过接口修改，给出副本（`toList()`）。
- `==` 比内容、`===` 比引用——从 Java 迁移后请反向记忆。
- `associateBy` 的 key 重复时后者静默覆盖前者——想要分组请用 `groupBy`。

## 我的笔记 / 疑问

<!-- 学到这里，把卡点、疑问、自己的话总结写在这里。下次续学先读这里。 -->

- “sessions.map { it.id }”中，it可以是任意名称吗？还是固定的it？
- fold用得多吗？这个没看懂，如果不常用那我觉得优先级不是很高？

### AI 回复区

<!-- AI 回答你的疑问时写在这里，不得擦除上方原始内容 -->

> 批改时间：2026-08-23

#### 观察任务批改

**任务1 ✅ 观察敏锐，结论基本正确，补一个关键边界**
你总结的规则方向对，更精确的表述是：**编译器只把 `$` 后面连续的"标识符字符"当作变量名，遇到点号/括号立即停止**。所以：

```kotlin
val list = listOf(1, 2, 3)
println("$list.size")    // ▶ 输出：[1, 2, 3].size   ← 坑！$ 只吃了 list，".size" 是字面文本
println("${list.size}")  // ▶ 输出：3                ← 带点、带调用的必须 ${}
```

你在真实代码里看到"方法调用都用 `${}`"不是风格偏好，是语法限制——`$` 只支持裸标识符。

**任务2 ⚠️ 分类需修正——而这恰恰是本章最重要的一课**
`typeName()` 是第⑤种：**sealed 穷举分支**（Part 是 sealed class，MessageStore.kt:492 注释明说"以穷尽 sealed"）。它确实用了 `is` 智能转换，但灵魂不在 `is`，而在"subject 是封闭类型时，编译器强制穷举所有子类"。

回答你的问题「要不要加个兜底方法？」——**主流答案恰恰是坚决不加**：
- 不写 else：漏一个分支 = **编译错误** → 将来有人给 Part 新增子类，所有没跟上的 when 全部编译报错，逼着开发者逐个处理
- 写了 else：新分支被静默吞进 else → 编译器闭嘴，bug 藏进运行时才炸

只有 subject 不是封闭类型（Int/String 等开放集合）时才需要 else。"没有兜底"不是缺陷，是用类型系统换来的安全网。

**任务3 ✅ 类比正确（≈ Collectors.joining），但"找 2 处"还没落实**
下次观察任务请落到具体 文件:行号——这个习惯对读源码很重要。给你两处真实用例参考：
- `logging/AppLogger.kt:216` — `stack.lineSequence().take(12).joinToString("\n")`：分隔符是换行符，无 lambda（元素已是 String）
- `data/update/UpdateRepository.kt:200` — `digest.digest().joinToString("") { "%02x".format(it) }`：分隔符空串，lambda 把每个字节格式化成两位十六进制——这是在算 SHA-256 指纹！

**任务4 ⚠️ 方向对，但要落到具体代码重做一次**
"后续要对成员进行变动"没错，但太笼统。请挑一处 `mutableListOf/mutableMapOf` 的真实使用处，回答三个问题：① 谁在往里 add/remove/put？② 这个集合的生命周期跟谁走？③ 能不能改成"每次重建新列表"来消除可变性？（提示：能改的都值得改）

**任务5 ⏳ 未作答——给两个真实锚点当起点**
- `service/OpenCodeConnectionService.kt:414` `.groupBy { it.sessionId }` — key = sessionId，value = List<该会话的事件>
- `ui/screens/sessions/SessionListStateBuilder.kt:86` `.associateBy { it.id }` — key = 标签 id，value = Tag 对象本身

对照 §6.2 确认你能说出 groupBy（一对多分组）与 associateBy（一对一、重复 key 后者覆盖）的区别即可。

#### 笔记区疑问解答

**Q1：`it` 可以改名吗？**
可以。`it` 不是关键字，只是单参数 lambda 的**默认隐式名**。显式命名完全等价：

```kotlin
sessions.map { it.id }                    // 默认
sessions.map { session -> session.id }    // 自定义名，效果相同
```

主流约定：单层短 lambda 用 `it` 最地道；**嵌套 lambda（两层都有 it 会分不清）或 lambda 体较长时改具名**。另外多参数 lambda 没有隐式名，必须自己命名：`{ (k, v) -> ... }` 或 `{ acc, e -> ... }`。

**Q2：fold 用得多吗？**
诚实答：业务 App 代码里属于**低频 API**（远低于 map/filter/groupBy），本项目里也几乎不用。你的优先级判断正确——认识符号、知道它是"带初值的聚合"即可，遇到再回查 §6.2，不用现在深究。
