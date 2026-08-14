# 视觉 AI 工具 / MCP 服务器调研报告（面向 DSH 接入）

> 调研方法：20+ 轮 web_search + 官方文档抓取（playwright.dev、platform.kimi.com、GitHub API、npm registry 元数据）+ OpenRouter 实时模型 API（2026-08 快照）。所有结论均附真实来源 URL。

## 0. 关键前提（决定方案选择）

1. **Playwright MCP 本身不做视觉理解**。官方 README 明确写 "No vision models needed, operates purely on structured data"（基于 accessibility tree）。--caps=vision 只增加坐标鼠标工具（browser_mouse_move_xy / click_xy / drag_xy 等），**截图后的"看懂"依赖 agent 自己的 LLM 具备视觉能力**。DeepSeek 主模型是文本模型 → Playwright MCP 无法单独替代 zai。
2. **zai MCP 429 的本质**：供应商（智谱 zai）5 小时滑动窗口用量上限。替代方案的共同目标是**换一个额度窗口/供应商**，而不是绕限流。
3. DSH 接入方式：@deepseek-ai/dsh-mcp-client，stdio，serverName 前缀即工具名前缀（mcp__<serverName>__<tool>）。

---

## 1. 候选清单表

| 名称 | npm包/命令 | 接入方式 | 视觉能力 | 免费额度/限制 | 需要 key | 推荐度 |
|---|---|---|---|---|---|---|
| **llm-vision-mcp** | npm @mseep/llm-vision-mcp v1.2.0（无 bin，需 git clone+build）| stdio (node dist/index.js) | describe_image / describe_images；**6 后端**：OpenAI、Anthropic、Google(Gemini)、Ollama(本地 llava)、OpenAI 兼容(DeepSeek/Qwen-VL/Together)、Generic HTTP | 取决于后端（Gemini 免费层 / Ollama 零成本）；**内置 429 重试×3 指数退避** | 按后端（Gemini 免费 key / Ollama 无） | ⭐⭐⭐⭐⭐ |
| **open-vision-mcp** | npm open-vision-mcp v2.1.0（bin: open-vision-mcp）| stdio (npx open-vision-mcp) | 单一 MCP 接 **10 个推理商**（OpenRouter/OpenAI/Together/DeepInfra/Fireworks/Groq/Chutes/Cerebras/Azure…） | MIT；README 自述 "live provider validation pending"（163/163 单测过，但无真实凭据端到端验证） | 按 provider | ⭐⭐⭐⭐ |
| **forloopcodes/vision-mcp** | npm @forloopcodes/visionmcp v1.0.1 | stdio | analyze_image（本地文件/URL→OpenRouter 视觉模型）+ screen（Windows 截窗） | OpenRouter 免费视觉模型可用 | OpenRouter key | ⭐⭐⭐ |
| **kimi-vision-mcp-server** | npm kimi-vision-mcp-server v0.1.0（bin 一键 npx）| stdio (npx kimi-vision-mcp-server) | Kimi 视觉：kimi-k3 / kimi-k2.5/2.6/2.7-code / moonshot-v1-*-vision-preview，**K3/K2.6 等支持视频**，1M 上下文 | OpenAI 兼容 (api.moonshot.cn/v1)；国内手机号注册 | MOONSHOT_API_KEY | ⭐⭐⭐⭐（国内环境） |
| **Playwright MCP** | npm @playwright/mcp v0.0.79（微软官方）| stdio (npx @playwright/mcp@latest) | **不做理解**；vision 模式仅加坐标鼠标工具；browser_take_screenshot 出图 | 免费；浏览器 UI 自动化最强 | 无 | ⭐⭐⭐（配合 vision MCP 用） |
| **Azure AI Vision MCP** | GitHub Azure-Samples/azure-ai-vision-mcp-server | stdio | 官方 sample 包装 Azure AI Vision（OCR/describe/face） | Azure 付费；需 Azure 账号 | Azure key | ⭐⭐ |
| **ollama-mcp** | npm ollama-mcp v2.1.0（Tim Green，**AGPL-3.0**）| stdio | 暴露 Ollama SDK 全部 14 个工具；配 qwen2.5vl/llava/minicpm-v 可看图 | 本地零额度；显存即上限 | 无 | ⭐⭐⭐ |
| **本地 ollama + qwen2.5vl** | ollama pull qwen2.5vl:7b/32b/72b（ollama 官方库已收录）+ llm-vision-mcp --provider ollama | stdio | 强视觉/OCR/UI 截图理解 | 零 API 限制；7b Q4≈6GB 显存、32b≈20GB+；CPU 可跑但慢 | 无 | ⭐⭐⭐⭐ |
| **minicpm-vision-mcp** | GitHub wjh1547485653-max/minicpm-vision-mcp（**专为 DeepSeek 单模态模型做"眼睛"**）| stdio | Ollama + MiniCPM-V 4.6 本地图片描述/视频/语音（faster-whisper） | 本地零额度 | 无 | ⭐⭐⭐ |
| **tesseract OCR MCP** | GitHub maximdx/tesseract-mcp-server；rjn32s/mcp-ocr；Ricardo-M-L/mcp-ocr-server（均无 npm 包）| stdio | **纯 OCR**（无语义理解）| 本地零额度、CPU 快 | 无 | ⭐⭐⭐（OCR 专用补充） |
| **Gemini API（直接当后端）** | REST/OpenAI 兼容端点 generativelanguage.googleapis.com/v1beta/openai | 作为上述 MCP 的 provider | gemini-2.5-flash 等全系多模态 | AI Studio 免费层免信用卡（多数地区）；限 RPM/RPD（flash 档约 10 RPM / 250 RPD 量级，数字官方动态调整） | Google key | ⭐⭐⭐⭐ |
| **OpenRouter 免费视觉模型** | openrouter.ai/api/v1（OpenAI 兼容）| 作为 provider | 2026-08 快照免费视觉模型仅 6 个：google/gemma-4-26b-a4b-it:free、google/gemma-4-31b-it:free、nvidia/nemotron-nano-12b-v2-vl:free（含视频）、nemotron-3-nano-omni-30b-a3b-reasoning:free、nemotron-3.5-content-safety:free、openrouter/free 路由 | 免费模型有独立限速（官方页面动态更新）；**清单会变动**（历史 qwen2.5-vl-72b:free 等已下架） | OpenRouter key（免费档免卡）| ⭐⭐⭐ |
| **智谱 GLM-4.6V-Flash** | POST open.bigmodel.cn/api/paas/v4/chat/completions（OpenAI 兼容）| 作为 provider / 直连 | 免费视觉模型，128K 上下文 | **免费但有速率限制，超限 429（与 zai 同厂商但不同额度体系）**；需国内手机号+实名 | ZHIPU_API_KEY | ⭐⭐⭐ |
| **Kimi (Moonshot) API** | api.moonshot.cn/v1（OpenAI SDK 直接可用）| 作为 provider / 直连 | kimi-k3 视觉+视频 | 计费（无明确免费层）；需国内手机号 | MOONSHOT_API_KEY | ⭐⭐⭐ |

---

## 2. Top 3 推荐（针对 DSH：易接入、稳定、额度限制小、覆盖截图/OCR/UI 测试）

### 🥇 Top 1：llm-vision-mcp（@mseep/llm-vision-mcp）+ Gemini 免费后端（云端主力）

**为什么**：一个 MCP 服务器挂 6 种后端（Google/Ollama/OpenAI 兼容等），工具把图片（路径/URL/base64）发给视觉模型、**返回纯文本描述**——与 DeepSeek 纯文本 agent 完美匹配；内置 429/5xx 重试×3 指数退避；Gemini 免费层限流窗口是"分钟/天"级（不同于 zai 的 5 小时窗口），作为主力可彻底摆脱 zai 锁死；后端可热切换（Gemini 超限→Ollama 本地兜底→Qwen-VL）。

**接入配置**（先 clone 构建，无 npm bin）：

~~~bash
git clone https://github.com/ghbalf/llm-vision-mcp && cd llm-vision-mcp
npm install && npm run build
~~~

DSH mcpServers（env 或 CLI 二选一）：

~~~json
{
  "mcpServers": {
    "vision": {
      "command": "node",
      "args": ["/path/to/llm-vision-mcp/dist/index.js", "--provider", "google", "--google-api-key", "AIza..."],
      "env": { "OLLAMA_BASE_URL": "http://localhost:11434", "OLLAMA_MODEL": "qwen2.5vl" }
    }
  }
}
~~~

工具前缀：mcp__vision__describe_image（DSH 直接可用）。故障转移：换 --provider / key 即可切 Ollama/其他后端，无需改 DSH 配置。
来源：https://github.com/ghbalf/llm-vision-mcp 、https://www.npmjs.com/package/@mseep/llm-vision-mcp

### 🥈 Top 2：open-vision-mcp（npx 一键，10 供应商统一入口）

**为什么**：唯一有 npm bin 的"多供应商视觉 MCP"，MIT 协议，npx open-vision-mcp 即起；一个服务器覆盖 OpenRouter（含免费视觉模型）/OpenAI/Together/Groq 等，方便做多供应商 failover（OpenRouter 免费→OpenAI 付费）。**注意坑**：README 自述 live provider 端到端验证"pending"，首次接入建议先用真实 key 冒烟测试。
来源：https://www.npmjs.com/package/open-vision-mcp

### 🥉 Top 3：本地 Ollama + qwen2.5vl + llm-vision-mcp(--provider ollama)（零 API 限制兜底）

**为什么**：彻底无额度、无 key、无实名、离线可用；qwen2.5vl 是 ollama 官方库模型（7b/32b/72b），截图理解/OCR/UI 文案识别都强；与 Top 1 同一 MCP 服务器，只改 provider。适合把 zai 场景切成"本地常驻 + Gemini 云上补充"双通道。

**接入**：

~~~bash
ollama pull qwen2.5vl:7b        # ~5-6GB，Q4；32b 需 ~20GB+ 显存
~~~

~~~json
{ "mcpServers": { "vision-local": {
    "command": "node",
    "args": ["/path/to/llm-vision-mcp/dist/index.js", "--provider", "ollama",
             "--ollama-base-url", "http://localhost:11434", "--ollama-model", "qwen2.5vl"] } } }
~~~

纯 OCR（CPU 快、无显存要求）可加装 tesseract MCP：https://github.com/maximdx/tesseract-mcp-server

**补充组合**：Playwright MCP（npx @playwright/mcp@latest --caps=vision）做浏览器 UI 自动化 + 坐标交互，配上述任一 vision MCP 做"看懂截图"——UI 测试闭环。国内网络环境优先考虑 kimi-vision-mcp-server（npx 一键、OpenAI 兼容）：https://www.npmjs.com/package/kimi-vision-mcp-server

---

## 3. 注意事项（每个候选的坑）

1. **llm-vision-mcp**：① 无 npm bin，必须 clone+build（TypeScript 构建产物 dist/）；② 社区维护（ghbalf，单作者），升级节奏不保证；③ Gemini 免费层仍会 429——但限流是 RPM/RPD 级（分钟/天窗口），重试后即可恢复，不会锁 5 小时。
2. **open-vision-mcp**：live provider 验证未完成（README 原话），Groq/Cerebras 视觉支持 "specifically unverified"；先冒烟再上生产。
3. **本地 Ollama 方案**：① 显存门槛——qwen2.5vl 7b Q4≈5-6GB、32b≈20GB+、72b≈45GB+；无 GPU 时 CPU 推理单图可能数分钟，不适合高频截图；② ollama-mcp 是 **AGPL-3.0**（商用/闭源集成注意许可证）；③ minicpm-v 类方案同样吃显存。
4. **OpenRouter 免费视觉模型**：免费模型清单**动态变动**（本次快照仅 6 个，历史热门 qwen2.5-vl-72b:free / llama-3.2-11b-vision:free 已不在列）；免费档有限速（以官方页面为准）；"openrouter/free" 是随机路由不可控。
5. **Gemini 免费层**：数字随官方政策变动（2026 快照：flash 档约 10 RPM / 250 RPD 量级，见 dev.to cheatsheet 与 apiyi 文章，**以 ai.google.dev/gemini-api/docs/rate-limits 为准**）；需 Google 账号，个别地区注册验证流程卡。
6. **国内 API（智谱 GLM-4.6V-Flash / Kimi / 通义 qwen-vl）**：全部需**国内手机号 + 实名认证**（企业认证额度更高）；智谱免费模型同样有速率限制、超限 429（与 zai 同源厂商，但属不同额度体系，可作第二通道）；Kimi 无明确免费层。
7. **Playwright MCP 误区**：不加视觉模型时"看不懂"图片内容；vision 模式只给坐标工具；且它是浏览器域（Android 模拟器截图分析需要 adb 截图 + 独立 vision MCP，而不是 Playwright）。
8. **Azure AI Vision MCP**：官方 sample（非产品线承诺），Azure 计费，仅当已用 Azure 时考虑。
9. **tesseract MCP 们**：只出文本不做语义理解，适合"提取屏幕上所有文字"的硬 OCR；无 npm 统一包，需 git clone。

---

## 4. 来源 URL 汇总

- Playwright MCP 官方：https://playwright.dev/mcp/vision-mode 、https://github.com/microsoft/playwright-mcp 、npm https://www.npmjs.com/package/@playwright/mcp
- llm-vision-mcp：https://github.com/ghbalf/llm-vision-mcp 、https://www.npmjs.com/package/@mseep/llm-vision-mcp
- open-vision-mcp：https://www.npmjs.com/package/open-vision-mcp
- forloopcodes/vision-mcp：https://github.com/forloopcodes/vision-mcp 、https://www.npmjs.com/package/@forloopcodes/visionmcp
- Kimi 官方视觉模型文档：https://platform.kimi.com/docs/guide/use-kimi-vision-model 、kimi-vision-mcp-server https://www.npmjs.com/package/kimi-vision-mcp-server
- Gemini 免费层：https://ai.google.dev/gemini-api/docs/rate-limits 、https://dev.to/hiyoyok/gemini-api-cheatsheet-2026-free-tier-limits-models-and-endpoints-in-one-place-2god 、https://help.apiyi.com/google-ai-studio-free-quota-limits-solution.html
- OpenRouter：模型 API https://openrouter.ai/api/v1/models 、免费视觉模型清单 https://openrouter.ai/models?max_price=0（快照：google/gemma-4-26b-a4b-it:free 等）
- 智谱 GLM-4V-Flash 免费（GLM-4.6V-Flash 为现行免费档）：https://mp.weixin.qq.com/s/lVwGqB_OMDTuAmTRGttFFQ 、https://hub.baai.ac.cn/view/41730 、配置参考 https://github.com/LinHaiJ/configure-glm-vision/blob/main/references/setup.md
- Ollama qwen2.5vl 官方库：https://registry.ollama.com/library/qwen2.5vl ；ollama-mcp npm：https://www.npmjs.com/package/ollama-mcp
- MiniCPM 视觉 MCP（为 DeepSeek 单模态设计）：https://github.com/wjh1547485653-max/minicpm-vision-mcp ；本地 Ollama 照片+OCR：https://github.com/YehudRaanan/photo-vlm-mcp
- tesseract MCP：https://github.com/maximdx/tesseract-mcp-server 、https://github.com/rjn32s/mcp-ocr 、https://github.com/Ricardo-M-L/mcp-ocr-server
- Azure AI Vision MCP sample：https://glama.ai/mcp/servers/Azure-Samples/azure-ai-vision-face-api-mcp-server 、https://github.com/ever-works/awesome-mcp-servers/blob/master/details/azure-ai-vision-mcp-server.md