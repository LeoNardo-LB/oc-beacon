# Tier C-4（#204）：i18n key 改名 — category 族→tag + abort→interrupt

> 状态：已实施完结（2026-08-24 用户授权代验收官；迁移证据与真机验证见 docs/journal/2026-08-24-tier-c-contract-renames.md）。对应 backlog #204 / 冲突 C28（tag/category 分裂全链）+ C 词表 interrupt 定名。
> 前置事实（2026-08-24 实测）：全仓 `getIdentifier` 动态资源查找 **0 处** → key 改名 100% 编译器保护（R.string 引用点改漏即编译失败），风险等级从评估文档的「运行时资源缺失崩溃」降为「编译期捕获」。maestro 34 flows 不锁涉改文案（grep 实证零 "Aborted"/"Category" 断言）。

## 改名映射（C28 定案：key 与文案统一到 tag）

| 旧 key | 新 key | 现值（EN） | 引用点 |
|---|---|---|---|
| `category` | `add_tag` | Add Tag | TagPickerDialog.kt:102（对话框标题） |
| `assign_category` | `assign_tag` | Add Tag | SessionRow.kt:425（行菜单动作） |
| `category_name` | `tag_name` | Tag name | TagManagementSection.kt:303、TagPickerDialog.kt:157（输入框 label） |
| `chat_aborted` | `chat_interrupted` | Aborted: %1$s → **Interrupted: %1$s** | PartContent.kt:317（中断提示） |

## 死键删除（×15 语言文件同步删，CI 奇偶校验兜底）

`no_category`、`new_category`、`set_category`、`no_favorites_in_category` —— 全源集 grep **0 引用**（旧分类功能遗留，文案已无渲染路径）。改名无意义（继续保持死键），删除。

## 译文值同步（interrupt 定名，仅措辞为 abort/cancel 的 8 语言）

| 语言 | 旧值 | 新值 |
|---|---|---|
| en | Aborted: %1$s | Interrupted: %1$s |
| zh-rCN | 已中止：%1$s | 已中断：%1$s（CONTEXT.md 定名：中断=interrupt） |
| de | Abgebrochen: %1$s | Unterbrochen: %1$s |
| es | Abortado: %1$s | Interrumpido: %1$s |
| fr | Annulé : %1$s | Interrompu : %1$s |
| pt-rBR | Abortado: %1$s | Interrompido: %1$s |
| id | Dibatalkan: %1$s | Dihentikan: %1$s |
| tr | İptal edildi: %1$s | Yarıda kesildi: %1$s |

已是中断语义不动：it（Interrotto）/ ru（Прервано）/ uk（Перервано）/ pl（Przerwano）/ ja（中止）/ ko（중단됨）/ ar（تم الإيقاف）。

## 不改项（明确出界）

- `chat_revert*` 族：英文 revert 是「撤销」的正确英文原词（CONTEXT.md），key/值均无冲突
- `task_sheet_*` 族：task→subagent 属组件更名叙事（TaskSheet→AgentSheet 已在代码层完成），i18n key 改名未列 C28 裁决——观察 `task_sheet_title` 等 key 的存活性另记
- 译文值其他措辞（C36 directory/folder 等）：属 Phase2b 值修订域，已完成，不随本卡重开

## 验证计划

1. sed ×15 文件改名+删死键 → grep 残留归零
2. 4 个代码引用点改新名（编译器断言改漏）
3. CI i18n 检查脚本（15 语言 key 奇偶）
4. 三源集编译（main/test/androidTest）+ `testDevDebugUnitTest --rerun`
5. 真机（houji）：标签管理对话框标题/输入框/行菜单「添加标签」渲染正常；会话中断提示文案（V2 中断一条消息后观察）——与 #205 真机轮合并执行
