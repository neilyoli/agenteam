# agenteam.meeting 实施进度

- 目标插件：`agenteam.meeting`；实施分支：`feature/agenteam-meeting-plugin`。
- 审计基线见 [M0-AUDIT.md](M0-AUDIT.md)；架构与验收以 [agenteam-meeting-plan.md](../agenteam-meeting-plan.md) 为准。
- 每阶段提交后更新本文件：记录提交 SHA、验证证据、剩余事项与恢复入口。

## 阶段状态总览

| 阶段 | 内容 | 状态 | 提交 |
| --- | --- | --- | --- |
| M0 | 架构/领域审计 + 版本提升 0.6.0 | 完成 | 3fc41e2 |
| M1 | 插件基础（模块/领域模型/只读工具/Validator/Skill/桥接契约） | 完成 | 0c14ec0 |
| M3 | 智能分析领域层（分类/证据/去重/负责人/上游/评估集） | 完成 | 见 M3 记录 |
| M2 | 事件驱动（订阅/Inbox/Delivery/服务身份/事件触发 Run） | 完成 | 8da0558 |
| M4 | 飞书同步（Binding/Schema/白名单/Outbox） | 完成 | 见 M4 记录 |
| M5 | 确认闭环（卡片/回调） | 完成 | d9cfc25 |
| M6 | 持续督办（Schedule action） | 完成 | 见 M6 记录 |
| M7 | 验收发布 | 进行中 | 见 M7 记录 |

## M0 记录

- 完成：5 项源码审计（Feishu / Agent 运行时 / Skill+Schedule / 插件运行时 / 迁移构建测试），结论并入 `M0-AUDIT.md`。
- 决策：应用版本与 `release.env` 提升到 0.6.0；新迁移 `V0.6.0.x`。
- 阻塞：飞书真实 API POC 无凭据，留待 M7。

## M1 记录

- 完成：`agenteam-server/agenteam-plugin-meeting` 插件模块（普通 JAR，Plugin-Id `agenteam.meeting`）。
  - 7 个只读工具：`meeting_search` / `meeting_get` / `meeting_artifact_status` /
    `minutes_get` / `transcript_get` / `meeting_people_resolve` / `task_validate`。
  - 领域模型（Meeting / Participant / Artifact / TaskEvidence / OwnerCandidate /
    TaskProposal / TaskClassification），负责人解析（歧义保留）、证据规范化（限纪要/逐字稿、
    可定位锚点）、L1/L2/L3 业务校验（L1/L2 强制负责人+证据+截止日期）。
  - 飞书只读 HTTP 客户端：JDK HttpClient + 宿主注入凭据头，错误按鉴权/限流/权限/参数分类，
    发送前 `beforeSend()` 复检；Jackson 打包重定位到 `com.stonewu.meeting.internal.jackson`。
- 验证：`mvn -f agenteam-plugin-meeting/pom.xml test` → 21 项单元测试全通过；`package` 产物
  含 `META-INF/extensions.idx`、`agenteam-plugin.json` 与正确 `Plugin-Id` 清单。
- 交付：Skill 参考文件 `docs/agenteam-meeting/skills/meeting-intelligence.md`、
  `meeting-follow-up.md`（平台原生资源，导入后绑定；插件安装自动关联 Skill 为待补能力）。
- 剩余：飞书真实端点字段以契约实现，需在 M7 用真实租户 POC 校准；写工具与任务/上游搜索由宿主承接（M2–M6）。

## M3 记录

- 完成：确定性分析领域层（插件侧）。
  - 稳定任务标识 `TaskKeyService`：规范化标题 + 负责人 openId 的 SHA-256 内容哈希，跨会议/跨运行稳定。
  - 去重合并 `TaskConsolidationService`：按 task_key 合并，证据/上游引用取并集，分类取更重（L1>L2>L3），截止日期取最早。
  - 上游覆盖判定：根据候选 `upstreamTaskRefs` 输出 covered / uncovered。
  - 新只读工具 `task_consolidate`；`task_validate` 复用统一候选解析。
- 验证：插件单元测试增至 28 项全通过；固定评估集 `meeting-eval-set.json` + `MeetingEvalSetTest` 可复现。
- 阈值：见 [M3-EVAL.md](M3-EVAL.md)（分类/证据/负责人/去重/错误阻断阈值）。
- 剩余：模型语义层（从纪要/逐字稿提取候选的召回与准确率）留待 M7 真实租户 + 模型链路评分。

## M2 记录

- 完成：宿主侧事件驱动链路（不另建执行引擎，复用 Agent Runtime 与 background_job 租约）。
  - 迁移 `V0.6.0.1`：`meeting_subscription`（订阅声明，固定插件版本+雇佣+事件类型）与
    `meeting_event`（持久收件箱），并扩展 `background_job` CHECK 允许 `meeting_event` 类型。
  - 事件识别 `MeetingEventNormalizer`：`meeting.`/`minutes.` 前缀识别，`app_id`/`tenant_key`
    归属校验（不匹配抛 `MEETING_APPLICATION_MISMATCH`）。
  - 事件入口 `MeetingEventIngressService`：挂在 `ChatConnectionManager` 飞书连接事件路由，
    先落收件箱再按 `(enterprise, integration_id, event_id)` 去重；未订阅的会议事件不持久正文。
  - 投递 `MeetingEventTransactions`/`MeetingEventWorker`：复用宿主租约（claim/renew/finish），
    以订阅配置者服务身份（`MeetingEventAccessService`）触发 Run；Run 创建与 `run_id` 回写同一事务，
    A03 崩溃恢复不产生重复有效 Run；插件升级/卸载后版本不匹配 → `ignored(PLUGIN_VERSION_MISMATCH)`，
    订阅缺失/配额不足 → `ignored`，403/404 → 终态失败，其余按租约次数退避重试。
  - 订阅 API：`/api/v1/enterprises/{id}/meeting/subscriptions` GET/POST/PATCH/DELETE，
    声明集成、事件类型、雇佣与固定插件版本。
- 验证：
  - `mvn -Dtest=MeetingEventNormalizerTest test` → 7 项全通过（识别、归属校验、非会议事件拒绝）。
  - `mvn -Dtest=MeetingEventSchemaTest test`（Docker mysql:8.4.11）→ 2 项全通过：迁移从零初始化、
    订阅/事件唯一键去重、企业外键链、后台任务类型扩展。
  - `mvn compile` → BUILD SUCCESS。
- 剩余：飞书真实事件类型清单与信封字段需 M7 真实租户 POC 校准（订阅 API 已按 `event_type` 自由声明）；
  A03/A15 的 Run 创建与回写同事务、版本不匹配停用的完整链路集成测试留待 M4 飞书同步旅程测试补足
  （当前以事务边界代码路径 + 迁移约束覆盖）。

## M4 记录

- 完成：系统到飞书的可靠投递，机制来源复核见 [M4-SOURCE.md](M4-SOURCE.md)。
  - 迁移 `V0.6.0.2`：`meeting_binding`（企业统一 Base 绑定，`UNIQUE(enterprise_id, business_type)`，
    外键链到企业/接入/成员，状态与业务类型 CHECK）与 `meeting_sync_outbox`（版本化投递箱，
    `UNIQUE(enterprise_id, binding_id, business_key)`，`delivered_version <= desired_version`、尝试次数 CHECK）。
  - 字段白名单 `MeetingFieldCode`（约 50 项，AI 提议/人工/技术三类所有权）与结果键 `MeetingWritebackResult`；
    人工字段（确认/执行进度）以飞书为唯一事实来源，不可配置为系统可写目标、绝不进入投递 payload。
  - `MeetingBitableFieldValidator`：映射白名单 + 唯一键覆盖 + 去重 + 拒绝人工字段；回写结果键归一化；
    联网按真实 schema 校验字段存在且类型可写（Formula/Currency 只读明确报错）。
  - `MeetingBitableClient`：沿用宿主 `IntegrationHttpClient`/`IntegrationTokenService` 的
    listFields/findRecordId/create/update 窄接口；异常按可重试（5xx/429/限流码）与阻断（其余非 0 码）分类，
    不输出凭证与原始响应正文。
  - `MeetingBindingStore`/`MeetingBindingApiService`/`MeetingBindingApiController`：绑定管理
    （列表/创建/更新/启停/删除），保存前在非事务门面完成真实 schema 校验再落库。
  - 版本化 Outbox：`MeetingSyncOutbox`（`Attempt` 记录 + 入队/领取/绑定/完成/失败/阻断/补推/到期扫描），
    `MeetingSyncOutboxSqlMapper`（upsert 版本递增且 `create_token` 稳定、claim 栅栏 `claim_token`+`lease_until`、
    find-before-create 对账、失败退避 `min(300, 5<<min(6,n-1))` 带抖动）。
  - `MeetingSyncTransactions`（领取→对账→幂等创建→字段级 update→回写同步状态）、`MeetingSyncWorker`+
    `MeetingSyncScheduling`（单有界线程轮询）、`MeetingSyncCommandService`（受控宿主命令，业务候选与 Outbox
    同事务、白名单与所有权校验、映射为飞书字段级 patch）、`MeetingSyncApiService`/`MeetingSyncApiController`
    （管理员失败队列对账与补推）。
- 验证：
  - `mvn -Dtest=MeetingBitableFieldValidatorTest test` → 9 项全通过（映射白名单/唯一键/人工字段/只读类型/回写归一化）。
  - `mvn -Dtest=MeetingSyncSchemaTest test`（Docker mysql）→ 4 项全通过：建表、唯一键与 CHECK 约束、
    版本递增且 create_token 稳定、claim 栅栏互斥、complete 推进 delivered_version、fail 退避/block/retry 重置。
  - `mvn -o compile` → BUILD SUCCESS。
- 剩余：回写字段（sync_time 等）的类型转换与飞书真实租户字段类型需 M7 POC 校准；
  插件桥接到 `MeetingSyncCommandService.publish` 的宿主服务桥接契约留待 M5/M7；
  真实飞书端到端投递（A08/A13/A19）无凭据，未以 mock 冒充真实验收。

## M5 记录

- 完成：任务负责人的确认闭环（发卡 → 可信回调 → 确认字段写回飞书）。
  - 迁移 `V0.6.0.3`：`meeting_confirmation` 版本与身份技术索引
    （`UNIQUE(enterprise_id, binding_id, business_key)`，外键链到企业/绑定/Run，状态 CHECK），
    确认状态仍以飞书为唯一事实来源，本表只做版本与身份校验、卡片投递与回调去重快照。
  - `MeetingConfirmationCard`：schema 2.0 卡片，三动作（确认/需调整/拒绝），回调 `behaviors.value`
    携带稳定 `task_key`、`task_version` 与 `binding_id`，markdown 特殊字符转义防注入。
  - `MeetingConfirmationCardSender`：面向负责人 `open_id` 投递（`receive_id_type=open_id`），
    与群聊卡片 `chat_id` 路径分离；沿用宿主令牌缓存与脱敏网络层，失败不输出凭证或响应正文。
  - `MeetingConfirmationCallbackService`：挂在 `ChatCardInteractionService`，仅识别 `meeting_confirm`
    动作；校验机器人状态/凭据修订/绑定归属，再经 `ChatIdentityService` 解析操作者身份。
  - `MeetingConfirmationService.answer`：企业锁 + 事件去重（`last_event_id`）+ 状态/版本/卡片消息编号/
    负责人身份四重校验，原子回答后经独立命令服务把确认字段写入投递箱。
  - `MeetingConfirmationCommandService`：仅允许人工确认字段 `task_status`（任务状态，飞书唯一事实来源），
    映射为飞书字段后入版本化投递箱，普通 AI 提议字段不可冒充确认；审计（评论/操作人/时间）留 MySQL
    `meeting_confirmation`。
  - `MeetingConfirmationStore`/`MeetingConfirmationMapper`：`beginRound`（重新确认重置版本/负责人/状态）、
    `recordCard`（回填平台消息编号）、`answer`（状态+版本+事件去重原子更新）。
- 验证：
  - 纯单元：`MeetingConfirmationCardTest`（4）、`MeetingConfirmationStateTest`（3）、
    `MeetingConfirmationServiceTest`（5：非负责人拒绝 A05、版本/卡片消息不匹配拒绝 A10、事件去重幂等、确认字段映射）。
  - Docker（mysql:8.4.11）：`MeetingConfirmationSchemaTest`（3：建表/唯一键/CHECK/外键、回答版本与去重栅栏、
    beginRound 重置与 recordCard 回填）、`MeetingConfirmationCommandServiceTest`（2：非确认/非人工字段拒绝、确认字段映射入队）。
  - `mvn -o compile` → BUILD SUCCESS；M5 合计 17 项测试全部通过。
- 剩余：同步完成后自动发卡的宿主→插件负责人解析桥接、重新确认触发新 Run
  （`RunSubmissionService.meetingRevision` + `MeetingEventAccessService.requireOwner`）留待 M6 补充；
  真实飞书端到端发卡与回调（A05/A10 现场链路）无凭据，未以 mock 冒充真实验收，留待 M7。

## M6 记录

- 完成：复用宿主 Schedule 触发的持续督办（无智能体额度，仅确定性扫描 + 限频催办）。
  - 迁移 `V0.6.0.4`：`meeting_follow_up`（逐任务催办台账，`UNIQUE(enterprise_id, binding_id, business_key)`，
    只做限频去重与证据快照）与 `meeting_follow_up_scan`（每次 Schedule 发生记录一个扫描作业，
    外键链到企业/绑定，状态与重试 CHECK），任务业务状态仍以飞书为唯一事实来源。
  - `MeetingFollowUpPolicy`：绑定 + 提醒间隔 + 工作时段（Asia/Shanghai）+ 跟进条件的固化快照；
    `MeetingFollowUpClassifier`：确定性候选识别（未确认/逾期/上游未覆盖），解析负责人 open_id、日期、
    文本，附来源证据（标题/截止日期/证据定位/未覆盖上游）。
  - `MeetingFollowUpStore`：台账 `remind` 以 `next_reminder_at` 原子抢占限频槽位，扫描作业
    `claim/complete/fail/block` 用 `claim_token + lease_until` 防过期 Worker 回写；失败退避带抖动。
  - `MeetingFollowUpTransactions`：领取扫描 → 读飞书宽表最新状态（分页 `listRecords`）→ 逐条识别 →
    台账限频 → 工作时段内才催办 → 负责人卡片投递 → 成功才 `recordSent`；不可重试错误 `block`、
    可重试错误 `fail` 退避、非工作时段完成并记 `outside_window`。
  - `MeetingFollowUpActionHandler`（`meeting.follow_up`）：`prepare` 校验绑定（task 类型且启用）、
    提醒间隔/工作时段/跟进条件、强制 `maxRetries=0`；`submit` 只排入扫描作业（事务内不调网络）；
    `cancel` 阻断该发生记录未处理作业；`reconcile` 汇总各扫描作业结果并给 running/completed/failed/blocked。
  - `MeetingFollowUpResultService`：按扫描结果聚合候选数/催办数/限频跳过数/失败数，作为发生记录状态证据。
  - 只读可观测 API：`/api/v1/enterprises/{id}/meeting/follow-up`（台账列表）与 `/scans`（扫描作业列表）。
- 验证：
  - 纯单元：`MeetingFollowUpClassifierTest`（5：未确认/逾期/上游未覆盖判定、负责人解析、无匹配跳过）、
    `MeetingFollowUpPolicyTest`（2：工作时段/冷却/条件）、`MeetingFollowUpTransactionsTest`
    （4：限频不重发、催办成功回填、非工作时段跳过、投递失败单独计数）、
    `MeetingFollowUpActionHandlerTest`（4：策略快照与强制零重试、非 task 绑定拒绝、非零重试拒绝、提交排入扫描）。
  - Docker（mysql:8.4.11）：`MeetingFollowUpSchemaTest`（3：建表/唯一键/CHECK/外键、remind 限频栅栏与
    recordSent 累计、扫描 claim/complete 令牌栅栏与取消阻断与耗尽标记）。
  - `mvn -o compile` → BUILD SUCCESS；M6 合计 18 项测试全部通过。
- 说明：角色看板由企业统一飞书 Base 视图承载（会议主表 + 督办宽表，按负责人/状态分视角），
  本阶段交付的是“提醒”确定性闭环；升级/修订/关闭等智能动作由 `meeting-follow-up` Skill 承载，留待 M7。
- 剩余：同步完成后自动发卡、重新确认触发新 Run 的宿主桥接（M5 遗留）继续顺延 M7；
  飞书真实字段值形状（日期毫秒/人员对象/单选标记）与工作时段时区需 M7 真实租户 POC 校准；
  真实端到端催办投递无凭据，未以 mock 冒充真实验收。

## M7 记录

- M7-C（完成）：负责人确认完整闭环——任务同步成功后自动、独立、可恢复地投递确认卡片。
  - 迁移 `V0.6.0.6`：`meeting_confirmation` 新增卡片投递列（card_status pending/sending/sent/failed/
    blocked/unknown/unresolved、标题/描述快照、尝试计数与上限、next_attempt_at、claim_token+lease_until
    租约栅栏、card_last_error 与索引）。
  - `MeetingConfirmationDeliveryService`（卡片对账 + 投递状态机）：
    - 对账扫描（`deliveredTasksAwaitingConfirmation` JOIN 投递箱×绑定×确认）：只有任务 Outbox
      delivered_version > 确认轮次版本时才创建/推进确认轮次——任务未写入成功绝不发卡；卡片失败
      不回滚任务同步；重启后凭扫描自动恢复，不依赖内存状态。
    - 负责人在建轮时经 `ChatIdentityService` 按 `feishu_open_id` 重新可信解析；未绑定或歧义 →
      status=unresolved + card_status=unresolved，绝不发卡。
    - 命令编号 `card:{bindingId}:{taskKey}:{version}` 稳定生成，重试复用同一 uuid，不重复创建
      有效确认轮次；同版本重投递不重建轮次（版本栅栏）。
  - `MeetingConfirmationCardWorker` + `MeetingConfirmationScheduling`（单有界线程，5 秒轮询，
    `agenteam.meeting.confirmation-worker-enabled` 开关）。
  - `MeetingCardDeliveryException`（继承 ChannelProviderException，兼容督办链路）：网络未取得明确
    结果 → UNKNOWN（card_status=unknown，**停止自动重发**——uuid 幂等窗口不可靠，转人工核查）；
    飞书明确拒绝 → REJECTED（限流 429/99991400 退避重试，其余 blocked 待修复；超上限阻断）。
  - 需调整修订闭环：`MeetingConfirmationCallbackService` 在回答提交后触发
    `MeetingConfirmationService.triggerRevision`（独立事务，失败仅记录不影响确认回答）→
    `RunSubmissionService.meetingRevision`（mode=meeting_revision，固定 meetingRevisionTaskKey + 订阅快照）
    → 修订 Run 经 `MeetingRunExecutionPolicy` 修订分支授权（确认轮次必须仍处 needs_revision）重新发布
    （existingTaskKey 强制、meeting_id 用任务身份固定的来源会议、不重复记来源）→ 新版本投递成功后
    对账扫描 beginRound 重置轮次 → 新版本卡片重新确认。
  - 验证：`MeetingConfirmationDeliveryServiceTest`(6：delivered 后建轮/未绑定不发卡/版本推进/身份缺失
    跳过/稳定命令编号发送成功/三类失败分类)、`MeetingConfirmationSchemaTest` 增至 4 项（卡片状态机
    租约栅栏：旧 token 不可完成、unknown 不再领取、超限阻断）；全量 Meeting*Test → **101 项全通过**。
  - 回写恢复语义：确认回答与 task_status 回写命令同事务（M5 既有），回写失败由版本化投递箱退避重试，
    看板以飞书为准，不提前展示未回写状态；`publish()` 同步发卡死代码已删除。
- M7-B（完成）：稳定任务身份与跨会议关联，替换 M3 标题+负责人哈希算法。
  - 迁移 `V0.6.0.5`：`meeting_task_identity`（宿主分配的稳定身份：task_key 随机分配、标题/负责人仅作
    展示快照、title_normalized 供候选匹配、status active/merged/review、来源计数）与 `meeting_task_source`
    （任务↔会议来源关系 + 证据快照，`UNIQUE(enterprise, task_key, meeting_id)`）。两表只保存技术身份、
    关系与审计，任务业务状态仍以飞书为唯一事实来源。
  - `MeetingTaskIdentityService.resolve`：existingTaskKey 显式引用 → 复用身份（键永不变，快照刷新）；
    无引用且同规范化标题+负责人已有活动身份 → `MEETING_TASK_DUPLICATE_CANDIDATE` 列出候选，要求显式
    关联或改写更具体标题，绝不相似度自动合并；否则分配 `T-<uuid>` 新身份。同一会议重复提及保持一条
    来源并更新证据，跨会议提及追加来源。`requireUpstreamReferences` 强制上游引用指向真实活动身份。
  - `meeting_task_publish` 新增 `existingTaskKey` 参数；`MeetingTaskPublicationService` 发布事务内完成
    身份解析与来源记录（与 Outbox 命令同事务）。
  - 新增只读工具 `meeting_task_search`（按任务编号/标题关键词/会议/负责人检索，可选 includeLiveStatus
    实时读飞书任务宽表最新状态，读取失败降级 unavailable 不中断检索）与 `meeting_upstream_search`
    （检索当前会议之外的上游候选，只保留确有外部来源的任务；未检索到如实返回空，不推断为未覆盖），
    由 `MeetingTaskQueryService` + `MeetingTaskSearchToolHandler` 承载；`MeetingBitableClient` 新增
    `findRecordFields` 单记录全字段读取。
  - 验证：`MeetingTaskIdentityServiceTest`(6)/`MeetingTaskQueryServiceTest`(4)/
    `MeetingTaskIdentitySchemaTest`(2，Docker：唯一键/CHECK/外键/来源唯一)；发布测试更新为身份服务契约；
    全量 Meeting*Test + BuiltinPluginSelectionApiTest → **98 项全通过**（工具数 50→53）。
  - 已知限制：并发发布同标题+负责人任务可能各建一个身份（无数据库唯一约束阻止），依赖发布前的候选
    检查与人工合并（status=merged）兜底；真实租户联调时观察是否需要额外护栏。
- M7-A（完成）：Agent 到宿主的任务发布闭环，实施合同见 [M7-A-IMPLEMENTATION.md](M7-A-IMPLEMENTATION.md)。
  - 宿主内置插件 `meeting_management`（`PlatformBuiltinPlugins` 注册，图标 Lightbulb/teal）+ 唯一写工具
    `meeting_task_publish`（`MeetingManagementToolDefinitions`，严格 JSON Schema、additionalProperties=false、
    60 秒超时），经 `MeetingTaskPublishToolHandler`（PlatformToolHandler 扩展机制自动注册）委托
    `MeetingTaskPublicationService`。
  - `MeetingTaskPublicationService` 宿主二次校验：L3 返回 skipped 不入队；L1/L2 强制负责人 openId
    （`ChatIdentityService` 按 `feishu_open_id` 解析到企业成员，未绑定/歧义拒绝）、参会人归属
    （自动 Run 必须携带 meeting_get 参会人清单且负责人在列）、可定位证据（minutes/transcript + 锚点）、
    截止日期（YYYY-MM-DD → 文本 `yyyy-MM-dd 00:00:00`）；技术字段（task_key/meeting_id）由宿主构造；
    AI 提议字段按绑定映射过滤（未映射可选字段不投递，task_key/title 必须映射）；返回 queued 不声称已写入。
  - `MeetingRunExecutionPolicy`：会议事件 Run 自动写的唯一授权判定（Run 模式/来源、收件箱事件 routed 且
    run_id 一致、事件与快照 integration/eventType/meetingId 一致、订阅启用且 hire/pluginVersion/agentVersion
    与快照一致、订阅配置者即 Run 身份、插件版本 active、`requireOwner` 资格、task 绑定启用且同接入）。
    四层强制：`ExecutionToolCatalog`（meeting_event Run 只暴露 read + meeting_task_publish，scheduled/preview
    不见会议写工具）、`ExecutionToolRuntime.checkPermissions`（策略放行免审批）、`ToolCallTransactions.requireApproval`
    （事务内复验）、`PlatformBuiltinInvocation.write`（meeting_event 专用分支重新全量校验，其余非交互仍拒绝）。
  - Run 快照固定：`MeetingEventTransactions` 传入 `MeetingEventRunSnapshot`（subscriptionId/hireId/pluginVersion/
    agentVersionId/integrationId/selectedVersionId），`RunSubmissionService.meetingEvent` 写入 executionConfig，
    模型参数不可覆盖。
  - `MeetingFieldCode` 新增 `owner_name`（任务负责人信息）；`MeetingSyncCommandService` 暴露
    `bindingMapping` 供发布服务按映射过滤。
  - 任务身份过渡：`MeetingTaskIdentityService.assignKey`（标题+负责人内容哈希，M7-B 前过渡实现，
    已独立封装，M7-B 替换为持久化稳定身份）。
  - 验证：新增 `MeetingRunExecutionPolicyTest`(7)/`MeetingTaskPublicationServiceTest`(11)/
    `MeetingTaskPublishToolHandlerTest`(2)/`ExecutionToolCatalogMeetingTest`(3)；`BuiltinPluginSelectionApiTest`
    内置插件 6→7、工具 50→51；全量 `mvn -o test -Dtest=Meeting*Test` → **82 项全通过**；
    `PlatformBuiltinToolApiTest`(5)/`PlatformToolRiskTest`(3)/`BuiltinPluginSelectionApiTest`(4) 通过。
  - 剩余：M7-B 稳定任务身份、M7-C 同步成功后自动确认卡片、M7-D 事件过滤/Skill/资料等待、M7-E 验收。
- 校准（完成）：按用户“直接联调”要求，从 magic-ai 真实源码校准飞书 Base 字段、值形状与事件类型，
  结论见 [M7-CALIBRATION.md](M7-CALIBRATION.md)。
  - 事件类型：`meeting.ended` / `meeting.all_meeting.ended` / `card.action.trigger` /
    `im.message.receive_v1`，统一过滤 `meetingSource==1`。
  - 任务宽表（`tasks`）真实字段：会议ID、待办、任务描述、任务确认人信息/任务确认人、任务负责人信息/
    任务负责人、会议名称、会议时间、截止时间、验收标准。
  - 关键差异：原无独立“任务唯一键”列（复合键 `会议ID+待办`），已确认企业侧新增文本列「任务ID」
    承载稳定 `task_key`；日期为文本 `yyyy-MM-dd HH:mm:ss`（非毫秒）；人员为 open_id 数组；
    `上游任务覆盖状态` 在会议主表（Select：待检查/无需上游）。
  - 状态字段收敛（字段能少则少）：合并「确认状态」「执行状态」为单「任务状态」字段
    （`task_status`，单选：待确认/进行中/已完成/需调整/已拒绝），M5 确认动作写它、M6 督办读它；
    确认评论/操作人/时间不再写 Base，审计留 MySQL `meeting_confirmation`。
  - 代码校准：`MeetingFollowUpClassifier.date()` 支持文本日期（Asia/Shanghai）解析；
    `MeetingFollowUpClassifierTest` 由 5 项增至 8 项；状态字段收敛相关 6 个生产类 + 8 个测试类
    同步更新。
  - 交付文档：[RUNBOOK.md](RUNBOOK.md)（运行手册：部署前提/飞书应用/绑定订阅/Skill/测试会议/
    看板/失败队列/停用恢复）与 [M7-ACCEPTANCE.md](M7-ACCEPTANCE.md)（A01–A19 验收矩阵 +
    未执行项清单）。`meeting-follow-up` Skill 已同步「任务状态」收敛。
- 剩余（验收发布项，须在真实租户闭环，不得以 mock 冒充）：
  - 真实飞书链路：绑定真实 Base/表并核对 schema、写回/催办/回调端到端投递（A05/A08/A10/A13/A19）。
  - 故障恢复：进程重启后 Outbox/扫描租约恢复、重复回调去重、版本不匹配停用。
  - 权限：企业管理员绑定/对账权限、负责人身份校验、卡片回调可信性。
  - 升级/卸载：插件版本不匹配 ignored、旧绑定停用、卸载后运行手册。
- 已知待决策差距：任务宽表「任务状态」已收敛为单字段（待企业侧按单选「待确认/进行中/已完成/需调整/
  已拒绝」建列并映射 `task_status`）；M6「上游未覆盖」条件需企业侧在任务宽表另配「上游覆盖」单选
  （已覆盖/未覆盖）；均不在此阶段擅自扩展，待真实租户联调确认后一并处理。

## 恢复入口

- 构建插件（先安装本地 API 依赖，再测试/打包）：
  `cd agenteam-server && mvn -q -f agenteam-tool-api/pom.xml install -DskipTests`
  `mvn -q -f agenteam-plugin-api/pom.xml install -DskipTests`
  `mvn -f agenteam-plugin-meeting/pom.xml test`（产物在 `agenteam-plugin-meeting/target/`）。
- 完整后端测试：`cd agenteam-server && .\mvnw.cmd test`（需 Docker：mysql:8.4.11、redis:7.4-alpine、minio、sandbox-office）。
- 纯单元测试：`.\mvnw.cmd -Dtest=<类名> test`。
