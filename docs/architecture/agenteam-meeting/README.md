# agenteam.meeting 架构基线

- 状态：**已确认，作为后续实施依据**
- 确认日期：2026-11-10
- 目标仓库：neilyoli/agenteam
- 插件标识：agenteam.meeting
- 本文档范围：架构决策与实施计划；不代表生产代码已经实现。

## 正式设计文档

1. [架构基线](architecture-baseline.md)：职责边界、事件驱动运行时、Meeting Agent、统一飞书 Base、同步与安全设计。
2. [实施路线与验收标准](implementation-roadmap.md)：M0–M7 交付阶段、依赖关系、测试矩阵和发布门禁。

## 已确认的关键决策

- 企业使用一个统一飞书多维表格 Base，包含「会议主表」和「会议督办宽表」。
- 会议督办宽表是任务业务状态的唯一事实来源；AgenTeam 不再维护第二套会议任务状态库。
- agenteam.meeting 是无状态 Java 工具插件；不在插件进程中运行长期 WebSocket、定时任务或 Worker。
- 飞书事件监听、持久化、事件路由、事件触发 Run、权限、通知与 Bitable Outbox 由 AgenTeam Core 提供。
- 会议分析使用 AgenTeam 原有 Agent Runtime；初期一个 Meeting Agent，通过版本化 Skills 指导分析。
- 会议处理采用事件驱动和 Agent 自主决策，不迁移 magic-ai 的固定 MeetingProcessStage 状态机。
- 同步采用 Binding、字段白名单、Schema 校验、稳定业务键、幂等 Upsert、Versioned Outbox 和可恢复重试。

## 实施约束

本架构先经过 M0 架构符合性审计与关键飞书 API POC，再进入生产代码实施。未完成 POC 的飞书事件类型、权限范围、回调能力和接口幂等语义不得当作已验证事实。

文档是本项目会议插件设计的唯一架构基线。若实施中发生决策变化，必须先更新架构文档和 ADR，再修改代码；不得以代码先行形成第二套事实源。
