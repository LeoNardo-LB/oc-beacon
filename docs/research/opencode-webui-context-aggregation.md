# OpenCode WebUI "已探索"聚合逻辑调研

> 源码：`packages/ui/src/components/message-part.tsx`（SolidJS，78.9KB）
> 仓库：`github.com/anomalyco/opencode`（原 sst/opencode），分支 `dev`

## 1. "已探索"是什么

i18n key：
```
"ui.sessionTurn.status.gatheringContext": "Exploring"   // 进行中
"ui.sessionTurn.status.gatheredContext":  "Explored"    // 已完成
```

当 AI agent 连续使用 `read` / `glob` / `grep` / `list` 工具探索代码库时，UI 将这些工具调用**聚合为一个可折叠卡片**，标题显示 "Exploring"（进行中）或 "Explored"（已完成），旁边附带动态计数摘要。

## 2. 核心常量

```typescript
// 哪些工具被归类为"上下文探索"
const CONTEXT_GROUP_TOOLS = new Set(["read", "glob", "grep", "list"])

// 哪些工具被完全隐藏
const HIDDEN_TOOLS = new Set(["todowrite"])
```

## 3. 分组算法 `groupParts`（行 570-612）

```typescript
export function groupParts(parts: { messageID: string; part: PartType }[]) {
  const result: PartGroup[] = []
  let start = -1

  // 将 start..end 范围内的连续 context 工具合并为一个组
  const flush = (end: number) => {
    if (start < 0) return
    const first = parts[start]
    const last = parts[end]
    if (!first || !last) { start = -1; return }
    result.push({
      key: `context:${first.part.id}`,
      type: "context",
      refs: parts.slice(start, end + 1).map((item) => ({
        messageID: item.messageID,
        partID: item.part.id,
      })),
    })
    start = -1
  }

  parts.forEach((item, index) => {
    if (isContextGroupTool(item.part)) {
      if (start < 0) start = index   // 标记 context 组起点
      return                          // 继续累积
    }
    // 遇到非 context 工具 → flush 之前的 context 组
    flush(index - 1)
    // 当前 part 作为独立项
    result.push({
      key: `part:${item.messageID}:${item.part.id}`,
      type: "part",
      ref: { messageID: item.messageID, partID: item.part.id },
    })
  })

  flush(parts.length - 1)  // 处理末尾的 context 组
  return result
}
```

**PartGroup 类型**（从代码推断）：
```typescript
type PartGroup =
  | { key: string; type: "context"; refs: { messageID: string; partID: string }[] }
  | { key: string; type: "part";   ref:   { messageID: string; partID: string } }
```

**算法本质**：线性扫描 parts 序列，将**连续**的 context 工具（read/glob/grep/list）合并为一个组。遇到非 context 工具（如 bash/edit/write/task）时切断分组。

## 4. 摘要计算 `contextToolSummary`（行 811-816）

```typescript
function contextToolSummary(parts: ToolPart[]) {
  const read   = parts.filter((p) => p.tool === "read").length
  const search = parts.filter((p) => p.tool === "glob" || p.tool === "grep").length
  const list   = parts.filter((p) => p.tool === "list").length
  return { read, search, list }
}
```

三类计数：
| 分类 | 包含的工具 | i18n 示例 |
|------|-----------|----------|
| read | `read` | "1 file read" / "{{count}} files read" |
| search | `glob`, `grep` | "1 search" / "{{count}} searches" |
| list | `list` | "1 list" / "{{count}} lists" |

## 5. ContextToolGroup 组件（行 947-1053）

```
┌──────────────────────────────────────────────┐
│  🔍 Explored   3 files read · 2 searches  ▸ │  ← 折叠状态
├──────────────────────────────────────────────┤
│  👓 Read    src/Main.kt                     │  ← 展开后
│  👓 Read    src/Utils.kt                    │
│  🔎 Glob    **/*.kt                         │
│  🔎 Grep    fun main                        │
│  📋 List    src/                            │
└──────────────────────────────────────────────┘
```

```typescript
export function ContextToolGroup(props: { parts: ToolPart[]; busy?: boolean }) {
  const [open, setOpen] = createSignal(false)

  // pending = busy（turn 进行中且这是最后一组）或有工具正在运行
  const pending = createMemo(() =>
    !!props.busy || props.parts.some((p) =>
      p.state.status === "pending" || p.state.status === "running")
  )

  const summary = createMemo(() => contextToolSummary(props.parts))

  return (
    <Collapsible open={open()} onOpenChange={setOpen}>
      <Collapsible.Trigger>
        {/* 标题：Exploring / Explored */}
        <ToolStatusTitle
          active={pending()}
          activeText={i18n.t("ui.sessionTurn.status.gatheringContext")}  // "Exploring"
          doneText={i18n.t("ui.sessionTurn.status.gatheredContext")}    // "Explored"
        />
        {/* 摘要：动态计数 */}
        <AnimatedCountList items={[
          { key: "read",   count: summary().read,   ... },
          { key: "search", count: summary().search, ... },
          { key: "list",   count: summary().list,   ... },
        ]} />
      </Collapsible.Trigger>

      <Collapsible.Content>
        {/* 展开后：每个工具的详情（文件名/pattern） */}
        <Index each={props.parts}>
          {(part) => <contextToolTrigger ... />}
        </Index>
      </Collapsible.Content>
    </Collapsible>
  )
}
```

## 6. 跨消息聚合（关键！）

`AssistantParts` 组件接收**多条** AssistantMessage，将它们的 parts **拍平为一个序列**后再分组：

```typescript
export function AssistantParts(props: {
  messages: AssistantMessage[]    // ← 一个 turn 的所有 assistant 消息
  working?: boolean
  ...
}) {
  const grouped = createMemo(() =>
    groupParts(
      props.messages.flatMap((message) =>
        data.store.part?.[message.id]              // 每条消息的 parts
          .filter((part) => renderable(part))       // 过滤隐藏/空 parts
          .map((part) => ({
            messageID: message.id,
            part,
          }))
      ),
    )
  )
  // ...渲染 grouped
}
```

**这意味着**：即使 `read` 工具在 message-1 中，`glob` 工具在 message-2 中，只要它们在 flatMap 后的序列中是**连续的**（中间没有 bash/edit 等非 context 工具），就会被聚合到同一个 "Explored" 卡片中。

## 7. 数据流总结

```
SSE 事件流
  │
  ▼
SessionProjector (服务端)
  │  将事件写入数据库：message + parts
  ▼
data.store.part[messageID] (客户端状态)
  │  PartType[]：{ type: "tool"|"text"|"reasoning", tool: "read"|"glob"|..., state: { status, input, ... } }
  ▼
AssistantParts 组件
  │  flatMap 多条消息的 parts → filter(renderable) → groupParts()
  ▼
PartGroup[]
  │  type="context" → ContextToolGroup (Explored 卡片)
  │  type="part"    → 独立渲染（bash/edit/text/reasoning/...）
  ▼
UI 时间线
```

## 8. 与 OC Remote Android 客户端的关系

当前 Android 客户端（oc-tether）**没有实现**这个聚合逻辑——每个工具调用都是独立渲染的卡片。

如果要实现类似功能，需要：
1. 定义 context 工具集合（read/glob/grep/list）
2. 在 `MessageCard` 或 `ChatMessageList` 层面，将连续的 context 工具 parts 分组
3. 渲染为可折叠卡片，显示计数摘要
4. 处理跨消息边界（一个 turn 中多条 assistant 消息的 parts 合并）

## 9. 关键源码位置

| 文件 | 行号 | 内容 |
|------|------|------|
| `packages/ui/src/components/message-part.tsx` | 570-612 | `groupParts()` 分组算法 |
| 同上 | 811-816 | `contextToolSummary()` 摘要计算 |
| 同上 | 947-1053 | `ContextToolGroup` 组件 |
| 同上 | ~1060+ | `AssistantParts` 组件（跨消息聚合） |
| `packages/ui/src/components/tool-count-summary.tsx` | - | `AnimatedCountList` 动态计数 |
| `packages/ui/src/components/tool-status-title.tsx` | - | `ToolStatusTitle` 状态标题 |
| `packages/ui/src/i18n/en.ts` | - | i18n key 定义 |
