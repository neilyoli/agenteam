# agenteam.meeting 实施进度

- 目标插件：`agenteam.meeting`；实施分支：`feature/agenteam-meeting-plugin`。
- 审计基线见 [M0-AUDIT.md](M0-AUDIT.md)；架构与验收以 [agenteam-meeting-plan.md](../agenteam-meeting-plan.md) 为准。
- 每阶段提交后更新本文件：记录提交 SHA、验证证据、剩余事项与恢复入口。

## 阶段状态总览

| 阶段 | 内容 | 状态 | 提交 |
| --- | --- | --- | --- |
| M0 | 架构/领域审计 + 版本提升 0.6.0 | 进行中 | — |
| M1 | 插件基础（模块/领域模型/只读工具/Validator/Skill/桥接契约） | 未开始 | — |
| M3 | 智能分析领域层（分类/证据/去重/负责人/上游） | 未开始 | — |
| M2 | 事件驱动（订阅/Inbox/Delivery/服务身份/事件触发 Run） | 未开始 | — |
| M4 | 飞书同步（Binding/Schema/白名单/Outbox） | 未开始 | — |
| M5 | 确认闭环（卡片/回调） | 未开始 | — |
| M6 | 持续督办（Schedule action） | 未开始 | — |
| M7 | 验收发布 | 未开始 | — |

## M0 记录

- 完成：5 项源码审计（Feishu / Agent 运行时 / Skill+Schedule / 插件运行时 / 迁移构建测试），结论并入 `M0-AUDIT.md`。
- 决策：应用版本与 `release.env` 提升到 0.6.0；新迁移 `V0.6.0.x`。
- 阻塞：飞书真实 API POC 无凭据，留待 M7。

## 恢复入口

- 构建插件：`cd agenteam-server && ./mvnw.cmd -f agenteam-plugin-api/examples/json-tools-plugin/pom.xml clean verify`（参照模板）。
- 完整后端测试：`cd agenteam-server && .\mvnw.cmd test`（需 Docker：mysql:8.4.11、redis:7.4-alpine、minio、sandbox-office）。
- 纯单元测试：`.\mvnw.cmd -Dtest=<类名> test`。
