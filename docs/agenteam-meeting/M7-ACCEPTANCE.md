# M7 验收报告

会议闭环插件（`agenteam.meeting`）的验收矩阵执行情况。结论：**自动化测试层已通过（114 项，含
Docker 隔离库），真实飞书租户链路尚未执行**——凡涉及真实凭据、真实事件、真实 Base 与进程重启
的项均未以 mock 冒充，明确列为未执行项，当前**不宣称可生产发布**。

## 1. 测试基线

- 纯单元 + Docker（Testcontainers mysql/mariadb + redis）合计 **114 项全部通过**：
  `mvn test -Dtest='Meeting*Test,BuiltinSkillResourceServiceTest'` → BUILD SUCCESS。
- 覆盖测试类：事件订阅/过滤/去重、资料就绪等待与恢复、同步绑定与版本化投递箱、稳定任务身份与
  跨会议关联、任务发布宿主校验与 Run 授权、确认闭环（卡片/状态机/命令服务/台账）、持续督办
  （分类器/策略/事务/扫描作业）、字段白名单校验、内置技能导入/发布/绑定。
- 各阶段累计：M7-A 82 项 → M7-B 98 项 → M7-C 101 项 → M7-D **114 项**（新增事件过滤、
  资料等待、URL 编码与内置技能测试）。
- 字段校准见 [M7-CALIBRATION.md](M7-CALIBRATION.md)，运行步骤见 [RUNBOOK.md](RUNBOOK.md)。

## 2. 验收矩阵

| ID | 场景 | 预期 | 证据（自动化） | 状态 |
| --- | --- | --- | --- | --- |
| A01 | 同一会议事件重复、乱序 | 事件去重；一个有效处理上下文 | `MeetingEventSchemaTest`（收件箱唯一键/去重）+ `MeetingEventNormalizerTest`（meetingSource==1 过滤、event_id 缺失拒绝） | ✅ 自动化已过 |
| A02 | 会议结束后妙记未就绪 | 持久化等待，不占用插件进程；就绪可恢复 | `MeetingEventTransactionsTest`（waiting + 有界退避 + 超时失败）、`MeetingEventIngressServiceTest`（minute_token 到达快速前推复用作业）、`MeetingEventSchemaTest`（waiting 状态持久化） | ✅ 自动化已过（真实事件时序待 E2E） |
| A03 | Run 已创建但 Inbox 确认前崩溃 | 关联键找回 Run，不重复提交 | Outbox/Inbox 关联键设计 | ⚠️ 待进程重启演练 |
| A04 | 重跑及跨会议重复提及任务 | 稳定 task_key，更新/关联已有任务 | `MeetingTaskIdentityServiceTest`(6) + `MeetingTaskIdentitySchemaTest`(2)（宿主分配的持久稳定身份，替换 M3 标题+负责人哈希） | ✅ 自动化已过（待真实重跑） |
| A05 | 同名不同人员、错误身份回调 | 不猜测身份；非负责人拒绝 | `MeetingConfirmationServiceTest`（非负责人拒绝）+ `ChatIdentityService` 可信身份解析 | ✅ 自动化已过 |
| A06 | 缺少截止日期、证据或负责人 | 不编造；阻断或待澄清 | `MeetingBitableFieldValidatorTest` + `MeetingTaskPublicationServiceTest`（L1/L2 强制负责人/证据/截止日期） | ✅ 自动化已过 |
| A07 | L3 日常内部事项 | 记录分析结果，不进入督办 | `meeting-intelligence` Skill（已正式导入/发布/绑定）+ `MeetingTaskPublicationService`（L3 → skipped 不入队） | ✅ 自动化已过 |
| A08 | 写入成功但响应丢失 | 对账识别；未知时停止盲目重建 | Outbox 版本化 + `record_id`/`business_key` 对账 | ⚠️ 待真实对账 |
| A09 | 飞书限流或中断后恢复 | 有界退避、补推；不重新分析 | `MeetingBitableClient` 限流码识别 + 扫描退避抖动 | ⚠️ 待故障注入 |
| A10 | 旧卡片、重复卡片、负责人变更 | 版本和身份核验；不覆盖新状态 | `MeetingConfirmationSchemaTest`（版本/事件去重栅栏）+ `MeetingConfirmationDeliveryServiceTest`（版本栅栏） | ✅ 自动化已过 |
| A11 | 上游承诺未被下游覆盖 | 标明未覆盖/证据不足，提供来源 | `MeetingFollowUpClassifierTest`（上游未覆盖 + 证据）+ `MeetingTaskQueryServiceTest`（未检索到如实返回空，不推断为未覆盖） | ✅ 自动化已过 |
| A12 | 两 Worker 竞争、租约到期 | 过期 Worker 不提交，不重复命令 | `MeetingFollowUpSchemaTest`（claim/complete 令牌）+ Outbox fencing/claim_token | ✅ 自动化已过 |
| A13 | 人工更新进度时后台重试 | 字段级 patch，人工状态不被旧 payload 回滚 | `task_status` 收敛单字段 + 确认命令服务只写 task_status；版本化投递不覆盖人工字段 | ✅ 策略已落地 |
| A14 | Schema 改名、类型变更、只读字段 | 校验失败可见；修复后重投 | `MeetingBitableFieldValidatorTest`（只读/不支持类型拒绝） | ✅ 自动化已过 |
| A15 | 插件升级、卸载、Run 取消 | 停止新触发并阻止失效版本写入；可对账 | 绑定/订阅 `enabled` + 版本不匹配停用 + `MeetingRunExecutionPolicy` 版本快照 | ⚠️ 待真实升级演练 |
| A16 | 超长逐字稿 | 分段与引用获取，遵守预算 | `meeting-intelligence` Skill 分段读取 + 预算上限 | ⚠️ 待真实长会议 |
| A17 | 跨企业数据或凭据访问 | 拒绝访问；日志不泄露 | `IntegrationSecretService`（企业+接入+用途绑定加密） | ✅ 机制已落地 |
| A18 | 无有效任务或订阅不匹配 | 记录/跳过原因可查，不发空通知 | 订阅过滤 + 扫描结果聚合 | ⚠️ 待真实空场景 |
| A19 | 正常真实端到端链路 | 会议→分析→两表→确认→看板一致 | —（需真实凭据/事件/Base） | ❌ 未执行 |

## 3. M7-D 增量覆盖

| 项 | 实现 | 验证 |
| --- | --- | --- |
| 会议事件过滤 | `MeetingEventNormalizer` 仅放行 `meetingSource == 1`（日历会议），`event_id` 为空拒绝 | `MeetingEventNormalizerTest` |
| 资料就绪等待 | `V0.6.0.7` 增加 `wait_until` + 状态扩展 `waiting`；`meeting.ended` 无 minute_token 进入 waiting + 有界退避（60s 起步封顶 300s，最多 6 次后 `MEETING_ARTIFACT_TIMEOUT`）；`minutes.*` 到达快速前推复用作业 | `MeetingEventTransactionsTest`(5) + `MeetingEventIngressServiceTest`(2) + `MeetingEventSchemaTest` |
| 内置技能 | `BuiltinSkillResourceService` 正式导入/发布/绑定 `meeting-intelligence` 与 `meeting-follow-up`（`ResourceKind.SKILL`，按 `pluginVersionIds` 绑定 `meeting_management`） | `BuiltinSkillResourceServiceTest`(3) |
| URL 编码 | `MeetingApiClient.encode` 使用 `URLEncoder.encode(UTF_8).replace("+","%20")` | `MeetingApiClientTest` |

## 4. 未执行项（须真实租户闭环，不得以 mock 冒充）

| 项 | 内容 | 前置 |
| --- | --- | --- |
| 真实飞书链路 | 绑定真实 Base/表核对 schema；写回/催办/回调端到端投递（A05/A08/A10/A13/A19 现场） | 真实 App 凭据、事件订阅、统一 Base、测试负责人 |
| 故障恢复 | 进程重启后 Outbox/扫描租约恢复、重复回调去重、版本不匹配停用（A03/A12 现场） | 运行中的服务 + 重启演练 |
| 权限 | 企业管理员绑定/对账权限、负责人身份校验、卡片回调可信性（A05/A17 现场） | 真实成员 + 飞书授权 |
| 升级/卸载 | 插件版本不匹配 ignored、旧绑定停用、卸载后运行手册（A15 现场） | 插件热升级演练 |

## 5. 结论

代码与自动化测试层面（114 项）已完成且通过；真实飞书租户验收（A19 及上述现场项）尚未执行。
在完成真实租户闭环并逐条回填「实际结果 + 关联 Run/命令 + 脱敏证据」前，本插件**不可宣称可生产发布**。
