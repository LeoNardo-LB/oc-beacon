# Rename OC Tether → OC Beacon (Design)

日期：2026-07-31

## 背景与决策

用户对 "Tether" 名称不满意（发音/拼写不友好、太平淡无记忆点、语义偏负面/困惑），经候选评估最终选定 **OC Beacon**（信标——信号引导意象，独特有记忆点，OC 前缀下 GitHub 无同名冲突）。

- 保留 OC 前缀（OC = OpenCode 缩写，保持品牌关联）
- 独立于上游（crim50n/oc-remote），满足上游 issue #17 的独立品牌诉求（继承自 oc-tether 改名的合规状态）

## 改名映射

| 项 | 旧 | 新 |
|----|----|----|
| 应用显示名 | OC Tether | OC Beacon |
| namespace | dev.leonardo.octether | dev.leonardo.ocbeacon |
| applicationId (stable) | dev.leonardo.octether | dev.leonardo.ocbeacon |
| applicationId (dev) | dev.leonardo.octether.dev | dev.leonardo.ocbeacon.dev |
| applicationId (beta) | dev.leonardo.octether.beta | dev.leonardo.ocbeacon.beta |
| Kotlin 源码包路径 | kotlin/dev/leonardo/octether/** | kotlin/dev/leonardo/ocbeacon/** |
| 测试包路径 | test/.../octether/** | test/.../ocbeacon/** |
| androidTest 包路径 | androidTest/.../octether/** | androidTest/.../ocbeacon/** |
| GitHub 仓库 | LeoNardo-LB/oc-tether | LeoNardo-LB/oc-beacon |
| git remote fetch URL | git@github.com:LeoNardo-LB/oc-tether.git | git@github.com:LeoNardo-LB/oc-beacon.git |
| git remote push URL | git@github.com:LeoNardo-LB/oc-remote-plus.git（残留！） | git@github.com:LeoNardo-LB/oc-beacon.git |
| 代码内标识符 | octether / OCTETHER / OC Tether / OC_TETHER | ocbeacon / OCBEACON / OC Beacon / OC_BEACON |
| 版本号 | VERSION_CODE=1 / VERSION_NAME=1.0.0 | **保持 1.0.0（不 bump，用户要求）** |

## 扫描现状（2026-07-31）

| 模式 | 文件数 | 位置 |
|------|--------|------|
| `octether`（包路径/标识符） | 641 | 代码包路径（大头）、maestro 脚本、README |
| `ocremote` | 75 | docs 历史报告、README |
| `oc-remote` | 34 | README、scripts、docs、maestro |
| `OC Tether`（应用名） | 23 | 15+ 语言 strings.xml、README、backlog |
| `oc-tether` | 22 | docs specs/plans、测试、research |
| `OC Remote`（旧名） | 38 | README、maestro、settings.gradle.kts、scripts |

额外残留：
- git remote origin **push URL 仍是 oc-remote-plus**（fetch 已 oc-tether）→ 本次修复
- 本地残留 `rename/oc-tether` 分支 → 删除

## 范围与策略

### 必须改（当前生效）
1. **代码层**：包路径重命名（641 文件）、全部 import、namespace/applicationId、BuildConfig 引用、通知渠道标识符、日志 tag、WorkManager/Room 等组件标识符（含 octether 字符串的代码）
2. **配置层**：app/build.gradle.kts、settings.gradle.kts、AndroidManifest.xml、CI（.github/workflows）、Maestro 脚本（yaml 里的包名/应用名）
3. **资源层**：15 语言 values-*/strings.xml 的 app_name（OC Tether → OC Beacon）
4. **文档层（当前）**：README.md、AGENTS.md、backlog.md
5. **Git 层**：remote URL（fetch + push 统一 oc-beacon）、删除 rename/oc-tether 分支
6. **GitHub 层**：repo rename（oc-tether → oc-beacon）、release/tag 重建

### 保留（不影响）
- 图标：官方 OpenCode Android 图标（与名字无关，drawable/mipmap 资源）
- 通知图标 ic_notification.xml（单色 O 环，无名字标识）
- version.properties：保持 VERSION_CODE=1 / VERSION_NAME=1.0.0
- 上游关系：upstream → crim50n/oc-remote 不变
- **历史文档**（docs/superpowers/specs+plans+reports、docs/research 等带旧名的记录性文档）：保留旧名作为历史记录，不逐字替换（避免破坏历史真实性）；仅当文档标题/内容明显是"当前状态描述"时更新

## 发版策略

- 保持版本 1.0.0（不 bump，用户要求）
- **发布 beta 版**：release 标记 `--prerelease`（用户明确要求发 beta 版）
- 流程：构建 beta APK → 删旧 release v1.0.0 + tag → 重建 tag v1.0.0 指向新 commit → 发新 release（oc-beacon-1.0.0.apk，prerelease）
- APK 为 beta flavor（dev.leonardo.ocbeacon.beta）

## 成功标准

1. `assembleDevDebug` + `assembleBetaRelease` 编译通过
2. 单元测试全绿（`testDevDebugUnitTest --rerun`）
3. 仓库内 octether/OC Tether/oc-tether 残留清零（历史文档除外）
4. git remote 统一 oc-beacon
5. GitHub 仓库名为 oc-beacon，release v1.0.0 可用
