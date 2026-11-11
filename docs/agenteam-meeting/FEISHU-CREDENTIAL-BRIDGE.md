# 飞书会议插件凭据桥接

## 目标

`agenteam.meeting` 不再要求用户在 Java 插件配置中手动创建或粘贴 Bearer Token。宿主根据当前 Run 的企业和执行用户，解析其已绑定的企业飞书接入，并复用 AgenTeam 现有 `IntegrationTokenService` 获取、缓存和刷新应用访问凭据。

## 身份与凭据边界

- 飞书用户绑定（`user_channel_binding`）用于确定当前执行用户对应的飞书成员身份及其所属企业接入。
- 企业应用密钥仍保存在 `enterprise_integration_secret`，由 `IntegrationSecretService` 解密读取。
- 应用访问 Token 由 `IntegrationTokenService` 获取和缓存；缓存键按企业、接入编号和凭据版本隔离，刷新锁由 Redis 协调。
- Java Plugin Worker 只接收当前工具调用所需的 `Authorization: Bearer ...` 请求头。
- Token 不写入 Agent Prompt、Skill、插件静态配置、工具参数、工具结果或日志。

用户身份绑定不等于持有一个长期用户 Token。会议插件当前使用企业应用身份访问其 API 权限范围内的资源；如果某个未来接口明确要求 `user_access_token`，必须通过独立的用户 OAuth 授权与安全存储实现，不得把 tenant token 当作 user token。

## 解析规则

1. 从宿主 `ToolContext` 读取 `enterpriseId` 和 `actorId`，不接受模型参数覆盖。
2. 查询该企业、该执行用户的 active 飞书账号绑定。
3. 只接受绑定身份类型 `feishu_open_id`。
4. 只接受同一企业中已启用、已完成身份校验、具有 `enterprise` 用途且属于飞书的接入。
5. 没有绑定时返回 `FEISHU_USER_BINDING_REQUIRED`。
6. 接入不可用时返回 `FEISHU_INTEGRATION_UNAVAILABLE`。
7. 存在多个可用绑定时返回 `FEISHU_BINDING_AMBIGUOUS`，不静默选择。
8. 通过 `IntegrationQueryService.application` 和 `IntegrationTokenService.get` 获取短期应用 Token。

## 配置体验

- `agenteam.meeting` 的 Java 插件配置不再显示通用「连接凭据」选择器。
- 选择该插件并发布后，运行时自动解析当前执行用户绑定。
- 其他 Java 插件仍沿用原有通用 HTTP 凭据配置，不受影响。

## 运行前置条件

- 企业必须存在已启用、已完成身份校验的飞书 `enterprise` 接入。
- 接入必须已配置 App ID、App Secret。
- 当前 Agent Run 的执行用户必须在该企业绑定此飞书账号。
- 飞书应用必须已获得对应会议、妙记、参会人 API 的应用身份权限。
- Token 获取失败、应用密钥缺失、权限不足时，返回明确错误；不回退到手工 Token，不伪造成功。

## 验证

自动化测试覆盖：
- 从当前执行用户的有效绑定解析企业飞书接入。
- 复用 `IntegrationTokenService` 返回的短期 Token。
- 缺少执行用户或有效绑定时拒绝。
- 多个可用绑定时拒绝歧义选择。
- 禁止手动凭据覆盖会议插件的宿主凭据来源。

真实飞书验证仍需在企业测试租户确认应用权限和目标会议资源可见性。
