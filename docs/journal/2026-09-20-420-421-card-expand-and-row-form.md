# 420-421-card-expand-and-row-form（2026-09-20）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 已完结卡片迁入（2026-09-20）

### **#420 展开思考卡片闪烁跳动** `ui` `chat` `bug`
  - 症状:展开 ReasoningBlock 时界面闪烁+跳动;diagnosing-bugs 流程进行中(2026-09-20)
  - 结构:AnimatedVisibility(CardExpandEnter=fadeIn+expandVertically Top)→heightIn(240dp)+verticalScroll+MarkdownContent(small)
  - 根因(仪器定案):贴底时toggle卡片,item高度变化全额转译为视口位移(实测展开+644px/收起-608px,峰值220px/帧,~170ms);isAtBottom恒真,guard零参与(日志证实);mid-list同理(±444px,2026-08-30守卫注释记载)
  - 设计(方案A·单一时钟同帧配对):CardExpandReveal包装器替换8处AV——AV只留fadeIn/fadeOut(组合生命周期),尺寸由自有f时钟驱动:帧回调dispatchRawDelta(δ)先行+本帧measure上报f*H,严格同帧配对零滞后(击败#262残余的帧界一帧错位);δ取全导数(f变化+H实时变化如展开中markdown迟到解析)
  - 竞态矩阵:R1流式并发=toggle降级裸AV(LocalInStreamingTurn,item级补偿独占)+官方dispatchRawDelta主线程串行| R2守卫=累计+δ越100px时autoScroll=false(离底即离开跟随)| R3滚动中=取消时钟snap f| R4跳转导航=isScrollInProgress取消覆盖| R5双toggle=f可逆重定向| R6 FAB中段浮现=良性| R7中途回收=冷组合snap目标+仅转换时动画| R8贴底收起(flow b)=dispatchRawDelta负向不可消费,物理不可约(防尾部空白),文档化| R9反射=零接触,通道零改动
  - 历史对照:2026-08-30 #262退役因复杂度高而残余跳动(帧界一帧错位+AV边界30px台阶);本设计无subcompose/无状态机层级/无指针吞没,同帧配对根治错位;旧裁决『贴底展开上方上推为终态』由本日新诉求覆盖(最新裁决为准)
  - 修复验证(真机,commit 29ea9a38):CardExpandReveal 单一时钟同帧配对——Test1 贴底四连击 0跳/0闪(修前±644px),Σδ=737≈H=738 逐帧全额消费 | Test2 mid-list 双击 0跳 | Test3 SSE 流式 680帧 0跳(贴底跟随正常) | Test4 飞行中断 f 0.98 丝滑回摆无崩溃 | Test5 拖动取消 cancel-on-scroll snap f=0.96 | Test7 展开→⬇FAB出现→回底→FAB隐 | 全量单测 BUILD SUCCESSFUL
  - 收起侧实证:头部带模板跟踪全程 1px(钉死);全局分析器的-904px读数=答案尾部从折叠线下升入视野(物理必然,唯一移动量)——收起语义正确 | t4b tap2 无日志一例未复现(疑 adb 投递竞态),toggle 活性复验正常
  - 已知边界(文档化):①贴底收起(flow b:⬇回底后再收起)负位移不可消费→上方内容自然吸收(防尾部空白,物理不可约) ②流式turn内卡片降级裸AV(item级COMP-MSG独占) ③展开后⬇FAB出现=离开跟随模式(裁决语义) ④120Hz 下 dispatch 每8-16ms一次,30fps录屏每帧含3-4次(视觉平滑)
  - 证据:docs/acceptance/2026-09-20-420-evidence/(4段mp4+2截图);[DEBUG-420]日志标签全链路可查
  - 迁入依据：用户验收ok(2026-09-20):三轮根修(量化泄漏/终末帧+conflation/渐进组合竞态)+全维验证真机通过（backlog.sh migrate 2026-09-20）

### **#419 user 气泡统计栏外置(扁平化收尾)** `ui` `chat` `消息层扁平化`
  - 共识(2026-09-18 拷问定稿):插话徽标+撤销+复制+详情整栏移出气泡,气泡下方右对齐(右缘平齐列表缘),间隙4dp/紧凑2dp
  - 排布 [徽标][撤销][复制][详情] 右缘起向左;触达修复:视觉14/16dp不变,命中区28dp;净高+6dp,观感不佳时气泡下边距14→10dp
  - ChunkedUserMessage 末段同步;FAB重叠(#418)维持先这样;验收①-⑩+观感拍板,steer徽标走代码审查+用户目检
  - 真机验收(小米14 无线,192.168.110.123:4199):①图标行位于气泡bounds外,行右缘1164=气泡右缘,间隙4dp(图标视觉顶841 vs 气泡底808) ✓
  - ②还原→确认弹窗/详情→消息详情弹窗(时间+删除)/复制→已复制到剪贴板toast ✓ ③触摸节点96-144px宽×144px高(48dp强制触摸目标+相邻裁剪),远超28dp底线 ✓
  - ④多行3202字符消息触发UserChunk分片,末段(407字符)下方外置行 ✓ ⑤紧凑密度(对话字体=小)图标行同构渲染 ✓ ⑥AMOLED纯黑模式截图取证 ✓ ⑦⑧SSE流式全程正常(思考中→流式输出→渲染) ✓ ⑨content-desc(还原/复制/消息详情)全保留 ✓ ⑩全部历史user消息外置行生效 ✓
  - 插话徽标:本日两次发送均未走steer路径(徽标未出现),按共识Q8a留待用户下次自然插话目检;截图证据 docs/acceptance/2026-09-20-419-evidence/;净高度实测+6dp档(气泡下边距14dp未收)——观感拍板时若嫌高可收14→10dp
  - AMOLED 复验修正:浅色主题下 AMOLED 开关无效(设计如此)——补验深色主题+AMOLED:背景带像素亮度11.36/255(近纯黑),右缘带877/900行含图标内容,图标行清晰 ✓;截图 amoled_dark.png 归档
  - 迁入依据：用户验收ok(2026-09-20):十条真机验收+观感拍板;steer徽标留自然插话目检不阻塞（backlog.sh migrate 2026-09-20）
