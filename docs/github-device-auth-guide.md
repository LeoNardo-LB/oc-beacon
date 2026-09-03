# GitHub Device Flow 授权操作手册（#302 UX + 154b 上报）

> 2026-09-03 真机 E2E 定稿（设备：小米 HyperOS 1200×2670；构建含 4e3449c2/dcb37080）。
> 适用：诊断页「上报到 GitHub」的 device flow 授权（#302 对话框）与 gist 上报（#154b）验证。
> 关联：journal 2026-09-03-154b-gist-299-245.md §一、docs/specs/2026-08-21-error-report-github-design.md

## 一、用户 2 分钟手验流程（#302 + 154b happy path）

1. **导航**：服务器列表（首页）→ 顶栏 ⚙ 设置 → 滚到底「高级」区 → **诊断**
   - 首页底部卡片：已连接的服务器有「会话」按钮（进入会话列表的唯一入口；卡片主体与标题不可点）
2. **触发授权**（无 token 时）：诊断页右上 ⋮ → **上报到 GitHub** → 弹「授权以上报」对话框
   - 已有 token 时菜单里会多一项「重置 GitHub 授权」（清 token 重走 device flow 的唯一入口）
3. **#302 验证**：
   - 对话框中部大号等宽码（XXXX-XXXX）即**复制 chip**——点按任意位置 → Toast「代码已复制」+ 剪贴板
   - 点「**在浏览器中打开**」→ 拉起浏览器直达 github.com 授权页（无需手动输 URL）
4. **浏览器授权**（要求浏览器已有 GitHub 登录态；无登录态会要求账号密码/2FA，届时停止并转人工）：
   - 直达页显示「Continue as <账号」→ 点它
   - **二次输码页**（GitHub 2025 反钓鱼新增）：**点击第 1 个格子 → 长按 → 粘贴**（app 里复制的码已在剪贴板）→ Continue
   - 应用授权页 → **Authorize**
5. **回 app**：授权成功后 app 轮询（~5s 间隔）自动拿 token → 直进「上报预览」
   - ⚠️ 授权期间 app 在后台可能被 MIUI 回收（MainActivity 重建、对话框消失）——**token 已持久化**，回到诊断页重新点「上报到 GitHub」即直进预览（已知可接受行为）
6. **上报**：预览确认「附全量诊断日志」勾选（默认开）→ **提交上报** → Done 消息含 issue URL + gist URL
7. **前提**：诊断日志队列里必须有 **ERROR/FATAL 级**条目（buildPreview 拦截「无错误日志可上报」）；level chip 过滤可快速确认

## 二、GitHub 授权页输码技巧（重点）

- **粘贴必须从第 1 格开始**：先点第 1 格聚焦，再长按粘贴——整码自动分发填满（用户实测可行）
- GitHub 2025 新流程：即使走 verification_uri_complete 直达，**Continue as 之后仍要求重新输码**（防钓鱼二步确认），不要指望直达免输
- 输码页手机端 8 格可能只显示 6 格在屏内（后 2 格出屏），**粘贴是唯一可靠输入方式**；手动逐格点击在边缘格上不可靠

## 三、自动化注入边界（agent/脚本操作者必读）

2026-09-03 实测（HyperOS + 小米浏览器 WebView），**以下手段在 GitHub 输码页全部失败**：

| 手段 | 结果 |
|---|---|
| `input text` 整串 | 只进第 1 格 |
| `type.sh`/逐 keyevent（无 IME） | 只进第 1 格，后续字符丢失 |
| KEYCODE_PASTE (279) | 只填前 6 格（第 7、8 格不落）——DOM 层确实未满，Continue 报 "couldn't find anything" |
| 软键盘弹出后 keyevent | 仍然不进后续格 |
| 双指缩放/横屏露出后 2 格 | 横屏被全屏键盘遮挡；无水平滚动 |
| 长按 (input swipe 同点) 唤出粘贴菜单 | WebView 内不出现原生粘贴气泡 |

**结论**：自动化无法在 GitHub 2025 输码页完成 8 位输入——**该步请用户人工完成**（或用 ask_user_question 请求在场用户协助），其余步骤均可自动化。剪贴板内容的自动化验证替代法：把剪贴板粘到浏览器**地址栏**（原生 EditText，KEYCODE_PASTE 可靠）核对码文本。

**其他环境怪癖**（同日实测）：
- 聊天页顶栏 ⋮ Popup 菜单：合成 tap 只触发 dismiss 不触发 item onClick（压缩/分叉点不动）；诊断页 ⋮ 菜单则正常——遇菜单点击无效时优先怀疑宿主窗口差异
- 添加服务器对话框：软键盘弹出会遮挡保存按钮（tap 落在 IME 上）；用 keyevent 4 收键盘（BACK 优先收 IME 不关对话框；**keyevent 111/ESC 会直接关对话框**）
- 服务器卡片只有右侧「会话/断开」按钮行可交互；SSE 断连时按钮是「连接」，连接成功才是「会话」
- 新建空会话不发消息不持久化（无需清理）

## 四、测试产物清理惯例

- issue：`gh issue close <n> --repo LeoNardo-LB/oc-beacon --comment "E2E 测试产物…"`
- gist：`gh gist delete <id>` 若静默失败用 `gh api -X DELETE gists/<id>`（复核 404）
- gist 校验：`gh api gists/<id> --jq '{public, description, files: (.files | keys), size: …}'`（secret=false 确认、文件名 epochMs、<300K 无截断、内容 [IP] 脱敏）
- PC 侧临时 HTTP 服务器 / adb reverse 隧道：用完即停/即撤
