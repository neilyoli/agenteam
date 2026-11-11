# agenteam.meeting M0 架构与领域审计

- 状态：审计完成，差距清单可追踪；未把规划能力当作现成能力。
- 审计日期：2026-11-10。
- 审计基准：`main`（57e0cc9），静态核验当前源码与依赖，不依赖方案文档的假设表述。
- 结论：本方案可在现有 AgenTeam 架构上实现；会议领域逻辑归插件，事件/同步/身份/卡片/调度归宿主扩展，但**大量宿主能力需新增**，且飞书真实 API 需 POC（当前无凭据，见 §7 阻塞项）。

## 1. 版本与迁移决策

- 应用版本：`agenteam-server/pom.xml` 与 `deploy/registry/release.env` 均为 `0.5.0`；上游已发布 v0.5.0（2026-11-02）。
- 按 AGENTS.md「发布后版本冻结」规则，新增 Flyway 脚本不得沿用 `V0.5.0.x`。
- **决策**：本分支将应用版本与 `release.env` 提升到 `0.6.0`，新迁移使用 `V0.6.0.<序号>__<中文说明>.sql`。
- Flyway：目录 `classpath:db/schema`，`validate-on-migrate=true`、`validate-migration-naming=true`、`clean-disabled=true`；最新迁移 `V0.5.0.5`。

## 2. 宿主真实能力核验

### 2.1 已确认可复用

| 能力 | 位置 | 复用方式 |
| --- | --- | --- |
| Java 插件契约 | `agenteam-plugin-api` `JavaToolPlugin`/`JavaPluginContext` | 会议插件实现现有接口 |
| 插件单次子进程执行 | `service/plugin/javaworker`（`JavaPluginProcesses`/`JavaPluginWorker`/`JavaPluginProtocol`） | 无状态工具调用；stdin/stdout 单行 JSON，4 MiB |
| 插件安装/校验/发布 | `JavaPluginManagementService`/`JavaPluginPackageReader`/`JavaPluginInstallationTransactions` | 沿用安装、版本、授权、取消 |
| 凭据解析 | `HttpCredentialService.resolve`（bearer/basic/api_key 静态值）+ `sources[].credentialId` | 插件经 `credentialHeaders()` 取鉴权头 |
| Agent 运行时 | AgentScope 2.0.4 `HarnessAgent`，`AgentExecutionFactory` 每次 Run 重建 | 不另建引擎 |
| Run 提交/执行 | `RunSubmissionService`（create/send/**scheduled**/collaboration）、`RunWorker`、`RunLifecycleService` | 事件触发 Run 复用；租约 30s |
| Skill | 资源 kind=skill，版本化，纯提示词注入（`SkillPromptMapper`/`SkillExecutionService.freeze`） | meeting-intelligence 以平台资源发布 |
| Schedule | `ScheduleActionHandler`/`Registry`（现有 agent.run、notification.send）、`ScheduleTriggerService`、`ScheduleActionWorker` | 新增会议督办 action |
| 可靠投递模式 | `background_job`（dedupe_key/lease/attempt）、`chat_outbound_message`（dedupe+request_id）、`channel_delivery` | 会议 Outbox 借鉴其租约/退避/幂等 |
| 飞书接入 | Webhook 验签（`FeishuWebhookVerifier`）、长连接（`FeishuConnectionProvider`）、聊天 Inbox（persist-then-ACK+`chat_inbound_receipt` 去重）、卡片收发（`FeishuMessageCards`/`FeishuChatAdapter`） | 复用验签/去重/卡片机制，抽象通用事件入口 |
| 加密凭据 | `IntegrationSecretService`/`IntegrationTokenService`（加密、刷新、撤销） | 会议资料凭据沿用，不写代码/日志 |

### 2.2 确认不存在的缺口（须新增）

| 缺口 | 证据 | 归属 |
| --- | --- | --- |
| 飞书 Bitable/Base 读写 | 全仓仅 `docs/agenteam-meeting-plan.md` 提及，无 `/open-apis/bitable` 调用 | 宿主新增（M4） |
| 会议/妙记/逐字稿 API | 无 `/open-apis/vc`、`/minutes` 调用 | 插件新增（M1） |
| 通讯录检索 | 无 `/open-apis/contact/v3`，仅能读当前授权人身份 | 宿主/插件新增（M1/M4） |
| 通用事件订阅 + Inbox + Delivery + 事件触发 Run | 事件仅聊天消息→协作→Run，无通用领域事件总线 | 宿主新增（M2） |
| 服务身份（service principal） | `AuthContext` 必须持有真实 `UserEntity`；`ExecutionAccessService.actor(run)` 要求 active 用户；无 system/service 账号 | 宿主新增（M2） |
| Bitable Binding + Schema 校验 + 字段白名单 + Versioned Outbox | 无 app_token/table_id/record/outbox 实现 | 宿主新增（M4） |
| 业务卡片投递 + 可信回调（非聊天） | 仅群聊卡片，无通用业务卡片回调校验 | 宿主新增（M5） |
| 会议督办 Schedule action | 仅 agent.run、notification.send | 宿主新增（M6） |

### 2.3 插件硬约束（必须遵守）

- `supportsDeduplication`/`supportsResultQuery` 在安装校验强制 `false`；宿主对 Java 插件**无自动重试、无去重、无结果查询**。
- 插件实例按调用创建，独立子进程，不能依赖静态状态、长连接、线程池、数据库。
- 凭据仅 `bearer/basic/api_key` 三种静态头；Feishu `tenant_access_token` 需宿主 Credential Broker 刷新后注入（见 §5）。
- 单包最多 20 MiB、展开 80 MiB、描述 64 KiB、10000 条目；`configurationSchema` 仅 ≤30 平铺标量字段。
- 写操作经 `beforeSend()` 复核版本/权限/确认/取消；写结果未知时宿主不重放，插件不得自行重试。

## 3. 桥接协议（插件↔宿主）

当前 `JavaPluginContext` 仅提供身份与静态凭据，**不含**宿主业务服务桥接。M1 采用最小契约：

- 只读会议工具通过 `credentialHeaders()` + 配置的 `apiBaseUrl` 直接调用飞书 Open API（JDK `HttpClient`，无额外依赖）。
- 目标资源（app_token/table_id/通讯录范围）不得由模型参数指定；M4 起由宿主 Binding 解析并经受控 Core 工具暴露。
- 插件不持有、不记录 App Secret / access_token；日志与 Tool Result 脱敏。
- 会议纪要/逐字稿/任务写入由宿主 Core 工具完成，插件只产出结构化候选 + 领域校验结果。

## 4. 字段所有权矩阵（飞书为唯一事实来源）

| 字段类别 | 示例字段 | 写入规则 |
| --- | --- | --- |
| 系统事实 | meeting_key/meeting_id/时间/主持人/来源链接/来源证据 | 仅可信来源；AI 不覆盖 |
| AI 建议 | task_title/description/classification/evidence/ai_suggested_owner/ai_suggested_due_date | 按分析版本更新并审计 |
| 人工确认 | owner/due_date/collaborators/task_status | 确认后由人维护；AI 重分析不得覆盖，冲突转复核（`task_status` 为确认+执行合并状态；确认意见/完成证据留 MySQL 台账，不落 Base） |
| 计算/只读 | 公式、Lookup、AutoNumber | 同步器不写入 |

稳定身份：`meeting_key = source_app_id + meeting_id`；`task_key` 在 meeting_key 命名空间内含首次分配的稳定 `task_id`，不能用标题/负责人生成。重跑先读已有任务并匹配证据/语义，确认新事项才分配新 task_id。

## 5. 可靠投递与并发（Outbox 契约）

技术记录字段：enterprise_id、binding_id、business_key、command_id、desired_version、delivered_version、record_id、create_token、claim_token、lease_until、attempt_count、next_attempt_at、payload_ref、last_error、关联 Run。

- 业务候选与 Outbox 命令在同一宿主持久化事务提交；飞书网络请求在事务外。
- 按 business_key 领取租约，claim token 防过期 Worker 回写；发送前复核权限/版本。
- 写成功但响应丢失：先按 record_id/business_key 对账，禁止盲目重建；无法证明未写入时转复核。
- 字段级 patch，人工字段不被旧 payload 回滚；仅检查自定义 version 不构成飞书原子 CAS，受控字段冲突进人工复核。

## 6. 实施顺序调整

方案原顺序 M0→M7 保持，但按实际依赖微调：

1. **M0** 审计（本文件）→ 版本提升 0.6.0。
2. **M1** 插件基础（独立模块，可脱离宿主单测）。
3. **M3** 智能分析领域层（L1/L2/L3、证据、去重、负责人解析、上游覆盖）——与 M1 同属插件领域代码，先于宿主事件落地，便于纯逻辑验证。
4. **M2** 事件驱动（订阅/Inbox/Delivery/服务身份/事件触发 Run）。
5. **M4** 飞书同步（Binding/Schema/白名单/Outbox）。
6. **M5** 确认闭环（卡片/回调）。
7. **M6** 持续督办（Schedule action）。
8. **M7** 验收发布。

## 7. 阻塞项与未验证项

| 项 | 状态 | 后续 |
| --- | --- | --- |
| 飞书会议结束事件类型/字段/去重行为 | 未验证（无凭据/租户） | M7 POC |
| Note/Minutes/Transcript 真实 API、分页、就绪时机、大小限制 | 未验证 | 插件客户端按已知契约实现，标注 POC |
| Bitable Schema/记录/变更事件/幂等/限流 | 未验证 | M4 实现按契约，标注 POC |
| 卡片回调签名/操作者/版本/幂等 | 未验证 | M5 实现按契约，标注 POC |
| magic-ai / erp-middle-center 参考分支可访问性 | 待核验 | 不可访问则记录具体阻塞，不编造已有实现 |
| 真实端到端 | 未执行 | M7 明确列出，不以 mock 代替真实验收 |

本文件是 M0 交付；实施进度见 `IMPLEMENTATION.md`。
