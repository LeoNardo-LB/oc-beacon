# DSH 0.1.5 / V3 事件载荷实况取证（#391 切片7 / #398）

> 取证源：本机 DSH 0.1.5-rc.1（wire=v012）——~/.dsh/sessions/**/session.v3.jsonl.zstd 共 29 个归档，
> zstd -dc 后 grep 原始信封行。App 侧对应 DshEventMapper 的 SESSION_FORMAT_V3 具名降级（目前不渲染）。
> 日期：2026-09-11。用途：#398 逐类渲染的权威 wire 规格（不臆造字段）。

## 一、事件频次（29 归档聚合）

| type | 出现次数 | 优先级 |
|---|---|---|
| assistant/attempt | 692 | 高（LLM 失败，当前静默） |
| system/message | 41 | 中（系统/插件上下文节点） |
| subagent/catalog | 16 | 中（子智能体目录） |
| deliverables/presented | 15 | 中（产物交付卡） |
| feedback/message-put | 0 | 低（归档无样本，需新会话取证） |
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
- 建议映射：finish.kind=error → 合成一条错误通知（复用既有错误/synthetic 卡），文案取 failure.message（截断）；kind!=error 静默。
- App 落点：新增 mapAssistantAttempt → 既有错误通知通道；需确认 UI 是否有可承载长错误文本的卡。

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

1. assistant/attempt(kind=error)——最高频且静默失败，收益最大；先确认既有错误通知承载面。
2. deliverables/presented——自包含，新卡边界清晰。
3. system/message——复用注入类合成卡，低风险。
4. subagent/catalog——需先厘清与既有子智能体目录投影的关系。
5. feedback/message-*——需新会话取证；App 已有 feedback 端口，事件侧补映射即可。

## 四、约束

- spec 差异分级：以上均属 L2 内容层——允许私有内容，但必须渲染在统一壳内、复用主题令牌、不得为新交互模式另起壳。
- 未知词汇仍走 SESSION_FORMAT_V3 具名降级（切片7 容错优先）；本批是「已知词汇的渲染补全」，不得把降级改回拒绝重建。
- 所有映射为纯函数，须补 DshV3AdaptationTest 同款单测。
