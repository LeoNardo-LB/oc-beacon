# W4 波次摘要(搜索/设置/工作区域 D1-D10)——2026-09-06 19:26-20:18

- 执行者:纯净上下文 W4 wave agent(只观测记录,不分析不修复)
- 设备 192.168.110.239:5555 · dev.leonardo.ocbeacon.dev 0.3.0(**构建 2026-09-06 18:39:55,含 A2 修复 60a44edf**,与 W3 同构建;dumpsys 核对)
- logcat:全程 PID 过滤采集 /tmp/e2e-full/W4-logcat.log(**235,277 行,PID 2108 全程未变**——wave 内无 app 重启);crash buffer /tmp/e2e-full/W4-crash.log(**0 字节**);**FATAL EXCEPTION=0**
- 开工准备(§0-9):reverse tcp:3080+tcp:4199 双条目在;POST_NOTIFICATIONS granted;stayon usb ✓;开工时 app 停 e2e06-f1 会话(W3 留下),顶栏「返回」回列表
- 执行顺序:D1→D2→D3→D4→D5→D6→D7→D8→D9→D10 严格顺序,无依赖微调

## D 组终态统计

| item | 卡 | 终态 | 一句话 |
|---|---|---|---|
| D1 内容搜索 | #322 | **✔** | e2e06 消息匹配区多条(24/13 条消息卡+片段)+会话行;tap 命中卡跳 e2e06-c1 定位命中词区;zzzqqx=「目录为空」明确空态 |
| D2 提供方页 | #324 | **✔(核心)/表单腿 feature-absent** | 页打开不崩溃(3cb324a8 回归通过)+DSH 目录+可用区在场;「新增」表单 UI 入口缺失=Add 图标接 onRefresh,showCreate 全历史无 true 赋值(死代码) |
| D3 preset 管理 | #324 | **✔(观测+取消路径)** | roster 四预设展开+查看组成对话框开→关无残留;新建表单不存在=by-design(查看只读/复制/删除三动作,移动端编辑走服务端文件) |
| D4 插件清单+表单 | #324 | **✔** | pluginInventory 5 插件只读呈现(4 启用 1 停用);per-plugin 表单 by-design 不存在(web 同构);服务器配置 schema 表单字段渲染无崩溃 |
| D5 skills 触发组 | #324 | **✔** | 会话内斜杠面板:6 服务器命令+「技能」分组 4+ 技能行(skill 徽标);skills/list RPC;只读观测+清稿退场零写入 |
| D6 归档链 | #311 | **✔** | 靶会话建+发送(回复失败=预期,行非 blank)→行菜单归档→主列表消失+已归档(5)折叠区在场 e2e06-d6 首位+归档行菜单仅「会话详情」无取消归档(单向契约 ✓) |
| D7 多工作区 | #311 | **✔** | NewSessionQuickDialog 现工作区列表+新建入口;OpenProjectDialog 三要素齐(目录列表/新建目录输入/创建会话确认);取消无残留无闪烁 |
| D8 ws remove/order | #330 | **create ✘ + order/remove feature-absent + 泄漏发现** | 新建目录在 DSH 后端不可用(Failed to create directory);order 无手柄/remove 无 UI=BLOCKED-feature-absent(42ceff63 单测+cascade 分类);**意外:临时 mkdir 会话泄漏**;main 三工作区终态在场 |
| D9 deliverables/skill 卡 | #311 | **BLOCKED-contract** | probe r1(树内可达)轮末无「产物」行=run_code 内联族契约性休眠(空不挂载,web 同构);skill 工具卡无场景;c1 写文件轮可见 chips=Run code 族 |
| D10 待审批琥珀点 | #311 | **BLOCKED-environment(注¹)** | 完全访问档无 pending 审批场景;列表扫描待批准/待回答类指示 0 出现;指针 2026-09-04-308-dsh-approval-wire.md(17✔) |

## 环境异常与处置

1. **IME/输入(W1-W3 经验全复用)**:type4.sh 单连接批 keyevent 全程可用;%s=空格存活;发送键 IME 抬升位 (1086,1616) 复验;全选删除=长按+盲点+单次 DEL 法在 f1 会话清 / 草稿时 CTRL+A 法无效(两法互补)。
2. **dump 失明族再添三例**:①设置区展开态首 dump 常空(重试即过,全程 ~12 次重试均成功);②c1 转录 V2 markdown 长文区 4 次 dump 逐字节相同(25887B 固定——正文区节点不暴露,§0-11 已知族);③AgentSheet 展开节点 dump 偶发旧内容(重试刷新)。
3. **导航坑**:搜索页「返回」落服务器管理页(非列表);服务器管理页「设置」为顶栏图标(≠列表底部设置 tab,后者=服务器域设置);BACK 键在 ChatScreen 常无效,顶栏「返回」钮可靠。
4. **清除搜索后焦点丢失**:需重点搜索框才能续输(操作侧注记,非缺陷判定)。
5. 新会话默认模型仍=DeepSeek-V4-Flash(deepseek-official 欠费)——D6 靶会话按预案允许回复失败(行非 blank 达成);W4 无需有效回复的场景,未手选模型。

## 意外观测(非缺陷结论,供主 agent/后续 wave 裁量)

1. **【D8 重点】「新建目录」在 DSH 后端不可用 + mkdir 临时会话泄漏**:OpenProjectDialog→新建目录 e2e06ws→创建=「Failed to create directory」;机制=CreateDirectoryUseCase 临时会话(title=mkdir)+runShellCommand(mkdir)→回退 executeCommand(bash -lc),DSH 两条执行路径均不支持(命令注册表仅 6 命令)→IllegalStateException;**finally 的 deleteSession 静默失败(DSH 无 session.delete 能力位)→ 临时会话泄漏为正式行**(列表顶位「mkdir·workspace·20:03」,logcat session/create+rename+commands/execute 链在案,无 delete 调用)。已按红线③归档清理。**疑似双重缺陷:①DSH 服务器上新建目录功能整体不可用;②临时会话清理在无 delete 能力后端必漏**——建议主 agent 登记裁量。
2. **【D2 重点】提供方页 Add 图标接 onRefresh**:目录标题行 Add 图标(desc=新增自定义 provider)onClick=onRefresh(logcat 实证 tap 即触发 listProviders+listConfigurableProviders 刷新);`showCreate` 状态在 git 全历史无 `= true` 赋值→DshCustomProviderCreateDialog(route/显示名/baseURL/协议/密钥+发现模型 全套表单)为不可达死代码。Add 图标形似「新增」实为刷新=UI 语义混淆。
3. **quick dialog 行长按=点击语义**:长按 workspace 条目直接 onSelectEntry 进入新会话(无上下文菜单)——证实无 order/remove 操作面;副作用产生 blank「无标题会话」(session/create 20:05:58 在案),已归档清理。
4. **归档区存量=前批遗留**:已归档(5) 中 4 行为 9月4/5 会话(前批验收归档),W4 归入 e2e06-d6 后=5;归档行菜单仅「会话详情」一项(无归档/重命名/取消归档)——单向契约在 UI 层完全封死。
5. **消息匹配卡与列表行同屏**:搜索命中区=消息卡(带 N 条消息徽标+片段)+会话行混合呈现,过滤条(角色/时间)在场但未逐一验证过滤功能(非 D1 断言面)。
6. **插件清单 per-preset 分组未出现**:pluginInventory 5 条全局 entries 在场,presets 分组行未渲染(inventory.presets 空?)——只读呈现不影响断言,如实记录。
7. **c1 会话轮次台账 0ms 异态再例**:probe r1 轮次 2 显示「0ms·1 步·0 个工具」(实际有回复文本)——与 B10 -207ms/F3 0ms 同族(#312① 附注裁量)。

## 设备端测试会话/数据(W4 创建,供 H3)

| 会话(行标题) | 首条消息 | 创建 | 终态 | 用途 |
|---|---|---|---|---|
| e2e06-d6,archive,target,message | e2e06-d6,archive,target,message(默认模型,回复 Insufficient Balance=预期) | 19:51 | **已归档**(归档区首位) | D6 归档靶 |
| mkdir | (助手临时会话,无用户消息) | 20:03 | **已归档** | D8 新建目录失败的泄漏副产物(清理) |
| 无标题会话 | (blank,无消息) | 20:05 | **已归档** | D8 长按=点击副产物(清理) |

- 未新建任何 workspace(新建目录失败,注册表零变更);未创建任何宿主文件(e2e06ws 目录未建成,宿主 ls 复核);服务器条目终态=**2**(Host-4199 未连接+prod-3080 已连接,W4-final-serverpage.png);设置页全程只走打开/取消路径,无任何落库(无 settings/credentials/workspace 类写 RPC 发出);**宿主侧未 spawn 任何子代理**。

## 证据索引(/tmp/e2e-full/,W4 新增 ~45 项,择要)

- D1:D1-hits/jumped/empty.png + w4-d1-*.xml
- D2:D2-providers/addform-open/after-tap2.png
- D3:D3-preset-expanded/view-dialog/dialog-closed.png
- D4:D4-plugins-list/config-forms.png
- D5:D5-slash-panel.png + w4-d5-*.xml
- D6:D6-sent/row-menu/after-archive/archive-expanded/archive-rows/archived-row-menu.png
- D7:D7-quick-dialog/open-project-dialog/after-cancel.png
- D8:D8-newfolder-dialog/folder-created/browser-workspace-dir/quickdialog-entries/cleanup-done.png
- D9:D9-c1-turn7/mid/probe-r1-session/probe-r1-turnend.png
- D10:D10-list-no-pending.png
- 收尾:W4-final-serverpage.png/W4-start-list.png;日志:W4-logcat.log(235,277 行)· W4-crash.log(0 字节)

## 遗留给主 agent 的裁量点

1. **mkdir 泄漏双缺陷登记**(意外观测 1):新建目录 DSH 不可用 + 临时会话无 delete 能力必漏——建议 backlog 卡(数据面:finally 清理需能力位门控或改归档语义;功能面:DSH 服务器上新建目录需服务端支持或 UI 隐藏)。
2. **D2 Add 图标语义混淆**(意外观测 2):Add↔refresh 错接+表单死代码——#324① 的「新增自定义 provider」移动端入口实际未交付(表单完整实现但不可达),建议登记核实交付意图。
3. **D8 create 腿的定性**:checklist 预期「新建工作区 e2e06ws→成功」在本 app 形态下=OpenProjectDialog 新建目录(失败);workspace 注册表本就无 app 侧 create 面(服务器维护)——若产品意图需要 app 内新建 workspace,需另立卡。
4. probe r1 轮次 2 台账 0ms/0 工具异态与 B10/F3 同族,建议合并归 #312①。
