# event-card-divider-design（2026-08-30）

> 状态：待用户验收
> 关联：journal 2026-08-27-event-card-unification.md（二十九轮遗留设计讨论：双块制 vs 恢复分割线）
> 来源：用户裁决「恢复分割线，但确保分割线跟不同的块有合适的距离与设计」

## 裁决与设计

用户定音方案 B（恢复分割线），附加约束：不同块型要合适的距离与设计。落地方案：

- 分隔线 1dp、outlineVariant@0.5（沿既有 token）
- 水平：随 MessageBubble 内容栏内缩 16dp（与正文同栅格，非全出血）
- 垂直：描述行到线 10dp（外层 spacedBy 承担）；线到正文 = bodyTopGap
  （裸文本/Markdown 默认 8dp；shell 卡自带背景面块传 10dp 加码脱开圆角）
- 下线（正文到动作区，有 actions 才有）：上下各 8dp

## 结构性根因（本批次最重要发现，像素取证实锤）

AnimatedVisibility 内容是 Box 叠放语义（非 Column）。Q11 两段式的四个
子级（上线/正文 Column/下线/动作行）此前全部原点重叠：

- 分割线一直被画在正文底下——透明 Markdown 正文时从字底隐约透出
  （「线贴着字」的不协调体感来源，也是当日「双重分隔」误判的背景）
- ShellOutputBlock（不透明 Surface）上身后分割线彻底不可见——44d9dc04
  的「去除」实际去除的是一个根本没在显示的元素

修复：AnimatedVisibility 内容包显式 Column，恢复「线、正文、线、动作区」
正确堆叠。此语义坑对 Compose 通用，后续任何 AnimatedVisibility 多子级
内容都应显式包容器。

## 真机像素实测（480dpi/3.0x，小米 houji e69a99d8）

修复后（02:04 构建，step9.png 逐像素扫描）：

    divider y 2150-2152（1dp+AA）x 84..1115
    卡描边 x 36..1163，内缩 48px = 16dp = contentHPad
    与描述文字（首字形 x86）同栅格
    描述行到线约 10dp（spacedBy）
    线到代码块顶约 10dp（bodyTopGap=10dp）
    块底到卡底边 14dp（卡片竖向 padding）

中间翻车一次并已回退：首版给分隔线额外加 horizontal=16dp，与内容栏的
16dp 叠加成 32dp——线比正文更缩（实测 x132 vs 文字 x86），像素扫描
当场抓住，去掉水平 padding 后对齐。

## 回归检查

- 收起、展开循环：无冻结、无空白、无残留裁剪（#241 ExpandReveal 补偿器
  挂卡根 modifier，不受包装 Column 影响）
- #252 终审不变：shell 卡 defaultExpanded=true
- ChatMessageList 其余调用点（system 卡/task 卡）：默认 bodyTopGap=8dp，
  下线 8dp 呼吸对 TextButton 触达友好

## 提交

- c5536c32 fix(ui): EventCard 分隔线回归并统一定距设计——AnimatedVisibility Box 叠放语义修复
