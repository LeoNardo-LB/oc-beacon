# DSH 0.1.5 / V3 事件载荷实况取证（#391 切片7 / #398）

> 取证源：本机 DSH 0.1.5-rc.1（wire=v012）——~/.dsh/sessions/**/session.v3.jsonl.zstd 共 29 个归档，
> zstd -dc 后 grep 原始信封行。App 侧对应 DshEventMapper 的 SESSION_FORMAT_V3 具名降级（目前不渲染）。
> 日期：2026-09-11。用途：#398 逐类渲染的权威 wire 规格（不臆造字段）。

## 一、事件频次（29 归档聚合）

| type | 出现次数 | 优先级 |
|---|---|---|
| assistant/attempt | 692 | **低**（瞬态尝试：681/692 后随 llm/retry，逐条渲染会刷屏；终态失败走 turn/end 或 stream/error） |
| system/message | 41 | 中（系统/插件上下文节点）——**已实现（2026-09-11）** |
| subagent/catalog | 16 | 中（子智能体目录） |
| deliverables/presented | 15 | 中（产物交付卡） |
| feedback/message-put | 0 | 低——**2026-09-12 定音**：包 schema 权威（见 §二-5），log-only，映射为忽略 |
| feedback/message-delete | 0 | 低（同上） |

## 二、载荷样本与字段

### 1. assistant/attempt（LLM 失败尝试）

    {"type":"assistant/attempt","seq":2063,"time":1788498579904,
     "data":{"turn":2,"step":35,
       "stream":[
         {"type":"chunk","time":1788498579898,"chunk":{"type":"usage","usage":{"inputTokens":0,"outputTokens":0,"totalTokens":0}}},
         {"type":"chunk","time":1788498579904,"chunk":{"type":"finish","reason":{"kind":"error",
           "failure":{"message":"502 <html>…Bad Gateway…","code":"SERVER"}}}}]}}

- 结构：data.{turn,step,stream[]}；stream[].chunk 与既有 assistant/chunk 的 chunk 同形（usage/finish）。
- 语义：该 turn/step 的尝试流，终态 finish.reason.kind；kind=error 时带 failure.{message,code}。
- **修正（2026-09-11 频次交叉）**：attempt 是低层「尝试流」记录，非终态失败——692 例中 681 例随后 `llm/retry`/`llm/retry-started`，逐条渲染会刷屏。**维持静默**；终态失败应走 turn/end 的 reason 或 stream/error。若未来要可视化重试，应聚合成「重试中」单卡而非逐 attempt。

### 2. system/message（系统 / 插件上下文消息）

    {"type":"system/message","seq":9,"time":1788991851436,
     "data":{"turn":1,"step":1,"message":{
       "id":"v2-to-v3-system-1bf46bf3…","role":"system",
       "source":{"kind":"plugin","plugin":"@deepseek-ai/dsh-system-prompt"},
       "content":[]}},"surfaceOp":"append"}

- 结构：data.message{id,role=system,source{kind,plugin},content[]}（与 assistant/message 同嵌套；内容块可为空）。
- 建议映射：当作注入类消息渲染（复用 Message.User.injectionKind → SyntheticNotificationCard，injectionKind 用 "system:"+plugin）；content 块按既有 text/file/image 规则。
- 41 例且 content 多为空——首版可仅渲染类型/来源标签，避免文本墙。

### 3. subagent/catalog（子智能体目录条目）

    {"type":"subagent/catalog","seq":595,"time":1789031280335,
     "data":{"version":0,"childId":"00b3cbcd-…","childCreatedAt":1789031280303,
       "mode":"continuable","label":"Fact-find repo patterns for plan"}}

- 结构：data.{version,childId,childCreatedAt,mode,label}。
- 建议映射：子智能体目录项（父消息的子会话元数据）——接入既有 SubagentCatalog 领域模型与子智能体卡；需先确认与 subagents/catalog RPC 投影是否重叠，避免双源。

### 4. deliverables/presented（产物交付）

    {"type":"deliverables/presented","seq":205,"time":1789040800453,
     "data":{"turn":1,"callId":"call_4b6a41bb…:ptc:1",
       "files":[{"path":"/tmp/recon/slice3-capabilities.md",
                 "description":"切片3 五族私有能力端口化只读盘点报告（file:line + 签名 + 行为）"}]}}

- 结构：data.{turn,callId,files[{path,description}]}。
- 建议映射：新 L2 卡「产物交付」——title = 文件数，行 = path + description；path 走既有 PathUtils.fileName，点击可接既有文件预览器。
- 需新增领域事件（如 SseEvent.DeliverablesPresented）+ synthetic 风格卡。

## 三、实施建议顺序

1. ~~assistant/attempt(kind=error)~~——**修正为维持静默**（瞬态尝试，见 §二-1）。
2. deliverables/presented——接既有 client-only deliverables 折叠面（ui/screens/chat/tools/TurnDeliverables.kt，当前从工具调用 args 折，改由服务器权威事件供给）。
3. system/message——**已实现**（复用注入类精简卡，见 DshEventMapper.mapSystemMessage）。
4. subagent/catalog——需先厘清与既有子智能体目录投影的关系。
5. ~~feedback/message-*——需新会话取证~~ **2026-09-12 定音（无需实况样本）**：权威 schema = `dsh-message-feedback/lib/types/types.d.ts`——`put {sessionId, item{messageId,rating,note?,category?,version,createdAt,updatedAt}}`、`delete {sessionId,messageId}`，两者均 **log-only（永不进模型历史/表面）**。App 已有权威 RPC 路径（`MessageFeedbackDelegate.seed ← messageFeedbackList`；put/delete 走 `ChatRepository`），故事件面映射为 `LOG_ONLY` 忽略，不落转录、不建第二存储。

## 五、反馈事件权威载荷（2026-09-12 补，源：dsh-message-feedback types.d.ts）

    {"type":"feedback/message-put","seq":9,"time":1,
     "data":{"sessionId":"s1","item":{"messageId":"m1","rating":"positive",
       "note":"…","category":"…","version":"v1","createdAt":1,"updatedAt":2}}}

    {"type":"feedback/message-delete","seq":10,"time":2,
     "data":{"sessionId":"s1","messageId":"m1"}}

- `rating` ∈ `positive|negative`；`category` 属固定反馈分类表；`version` 是 CAS token（每次实质 put 换新）。
- 重复同值 put 是无变化操作（不追加事件）；delete 不存在项幂等成功。

## 四、约束

- spec 差异分级：以上均属 L2 内容层——允许私有内容，但必须渲染在统一壳内、复用主题令牌、不得为新交互模式另起壳。
- 未知词汇仍走 SESSION_FORMAT_V3 具名降级（切片7 容错优先）；本批是「已知词汇的渲染补全」，不得把降级改回拒绝重建。
- 所有映射为纯函数，须补 DshV3AdaptationTest 同款单测。
