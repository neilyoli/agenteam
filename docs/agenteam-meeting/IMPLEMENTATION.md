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
| M2 | 事件驱动（订阅/Inbox/Delivery/服务身份/事件触发 Run） | 未开始 | — |
| M4 | 飞书同步（Binding/Schema/白名单/Outbox） | 未开始 | — |
| M5 | 确认闭环（卡片/回调） | 未开始 | — |
| M6 | 持续督办（Schedule action） | 未开始 | — |
| M7 | 验收发布 | 未开始 | — |

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

## 恢复入口

- 构建插件（先安装本地 API 依赖，再测试/打包）：
  `cd agenteam-server && mvn -q -f agenteam-tool-api/pom.xml install -DskipTests`
  `mvn -q -f agenteam-plugin-api/pom.xml install -DskipTests`
  `mvn -f agenteam-plugin-meeting/pom.xml test`（产物在 `agenteam-plugin-meeting/target/`）。
- 完整后端测试：`cd agenteam-server && .\mvnw.cmd test`（需 Docker：mysql:8.4.11、redis:7.4-alpine、minio、sandbox-office）。
- 纯单元测试：`.\mvnw.cmd -Dtest=<类名> test`。
