# 术语规范名以 OpenCode API（V2）为权威源，每术语必有中文对应名

四轮裁决（2026-08-23，990 文件盘点 + 官方对照后）确定：全项目术语的规范名跟随 OpenCode API V2 现行协议词（interrupt/rename/compact/credential/question），V1 词仅作历史对照；每个术语必须有一个中文对应名（轮次/堆积消息/子智能体/中断/撤销…）。修订分层：注释与文档统一中文并按 CONTEXT.md 术语表执行；UI 文案（EN 源+14 语言）同步对齐；客户端标识符统一 V2 词（wire 层 @SerialName/端点路径除外——那是服务器契约，两种协议都要说）。历史产物（journal/archive/旧 spec）豁免，CHANGELOG 历史段不豁免。

**为何难逆**：标识符改名（abortSession→interruptSession 等 Tier A+B）与 15 语言文案一旦落地，回退成本等于重做一遍。
**为何意外**：后来者会以为注释/文案可随意措辞——本 ADR 说明措辞是裁决过的契约。
**真实取舍**：曾考虑跟 V1（与既有 domain 接口一致）和保留英文不译（provider/tokens），被「最彻底+一致」原则否决。
