# #310 批2 五子项 wire 契约钉死（2026-09-05,服务器/web 源码取证）

> SR=/home/linuxbrew/.linuxbrew/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai。wire 方法名=TypertRemoteMap 键('domain/method')。

## ① 子智能体续聊
- RPC:'subagents/prompt'(SubagentPromptRequest)→{messageId};'subagents/interruptByParent'(childSessionId,parentSessionId,mode:'continuable')→{accepted:true};'subagents/list'(parentSessionId)→SubagentCatalog{entries,parentAvailable}(dsh-subagent/lib/typert.remote-client.d.ts:11-18)
- 载荷:SubagentPromptRequest={requestId(randomUUID),parentSessionId,childSessionId,mode:'continuable' 固定,content:PromptContentPart[],clientTimeZone?}(types/control-types.d.ts:72-107)
- 与主会话差异:session/prompt={requestId,sessionId,mode:'queue'|'steer',...}(session-controller types.d.ts:285)——子会话无 queue/steer、多 parent/child 地址;停止=interruptByParent(durable 父地址,父 Agent 不在也能中断);无单子 history RPC(转录仍 session/follow);服务端落点 agent.followup(session-controller index.js:758)
- web:mod04.js:922 prompt 路由/:989 cancel/:1645 list;mod33.js:822 one-shot 禁 composer/:837 openSubagent

## ② 消息反馈
- 'messageFeedback/put' {sessionId,messageId,rating:'positive'|'negative',note?,ifVersion:version|null}→MessageFeedbackItem;'messageFeedback/delete' {sessionId,messageId,ifVersion}→{absent:true} 幂等;'messageFeedback/list' {sessionId}→{items}(dsh-message-feedback typert.remote-client.d.ts:15-17)
- MessageFeedbackItem={messageId,rating,note?,version(CAS token),createdAt,updatedAt};失败码:session-not-found/target-not-found/version-conflict(带 current)/note-blank/note-too-large
- web mod37:put:188(ifVersion=observed?.version??null;conflict 用 error.current 重同步)/delete:206/list:227

## ③ Plan 模式(三通道,无独立 RPC)
1) 切换=slash:'commands/execute'(agentId,line,images)(dsh-commands typert:12,16);服务端命令 name:"plan" hint"[off|message]" images:true(dsh-plan-mode/lib/index.js:181-184)
2) 状态=projection key:"plan"(index.js:66),由 command/run+data.name==='plan'(:84)折叠;客户端视图{active,pending},有效态=pending?!active:active
3) 审阅=user-questions waterfall:EXIT_PLAN_MODE→userQuestions.ask({questions:[{id:'plan-review',header:'Plan review',question,detail=plan,options:[Approve/Keep],intent:{kind:'plan-review',approve}}]})(:255-300);应答经 waterfall 返回值回传,无独立 RPC;intent 只改呈现(:11-27)

## ④ 轨迹台账
- 无专用 RPC——数据源=转录回放:session/follow durable 事件投影为台账节点 trajectoryNode(target:"trajectory",anchorSeq,data)(mod44.js:412-425)
- 消费事件:user/message·assistant/message·assistant/chunk·tool/call·tool/result·tool/code-dispatch(-start)·step/start·step/end·turn/end·request/header·llm/retry·compaction/*·session/end-seed;UI 偏好客户端持久化 dsh.trajectory.duration

## ⑤ @ 会话源引用
- 'sessionReferenceResolver/candidates'(agentId,query)→SessionReferenceMentionCandidate[](dsh-session-reference typert:11-14;agent 作用域变体:18-21)
- 候选={sessionId,label,cwd?,sameWorkspace,createdAt,mention};mention=规范 @[label](dsh-session:id) 序列化进 prompt 草稿;按 cwd 亲和排序(types.d.ts:42-64)
- 文件孪生:'fileReferences/list'(agentId,query)(session-controller typert:11)——**即 #321 的落点**
- web mod34:113-114 并行合并 fileReferences.list+sessionReferenceResolver.candidates(quoted===true 跳过会话候选);触发 mod30 input-trigger:"@"(:85) 与 "/"(:103),词边界(:46-64),query=trigger→光标切片,span={start,caret}(:74-76)

## 实现排序启示
①数据层三方法+发送/停止分流(通道就绪)→②独立域 CRUD→③projection+slash+question intent 呈现→⑤与 #321 同管线(并行合并候选)→④纯客户端投影(UI 量大,最后)
