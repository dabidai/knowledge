# 鉴权第一批实施记录

日期：2026-10-16。按照 [06 鉴权完善方案](06-auth-improvement-plan.md) 的第一批范围完成代码修改；不增加数据库字段，不实现 authVersion、OAuth/OIDC、刷新 Token 或服务端 Token 撤销。本次未启动业务应用、未连接或修改业务数据库，也未执行管理员初始化。

## 已实现

| 链路 | 实际行为 |
|---|---|
| 登录 | 校验密码、enabled 和合法角色；无部门账号可登录并按原规则仅访问公共资源；超长密码可控拒绝 |
| Token | sub 为正整数用户 ID；一次解析校验签名、有效期、ID；不携带角色/部门作为权限来源 |
| 请求认证 | 按 ID 查询当前账号，读取当前角色/部门；删除或停用账号后凭证被拒绝 |
| 当前身份 | 新增 GET /api/auth/me，返回安全 UserResponse；不重新签发 Token |
| 用户响应 | login 返回 `{ token, user }`；me、列表、新增均返回安全 DTO，实体密码字段也禁止 JSON 序列化及 toString 输出 |
| 账号管理 | role 只允许 admin/default；密码至少 8 字符且 UTF-8 不超过 72 字节；分页上限 100；服务端拒绝直接删除 admin |
| CSV 用户 | 与 API/初始化共用账号规则；已存在且角色/部门一致则明确跳过、保留密码和启用状态；冲突拒绝，不用输入覆盖图中的身份 |
| 初始化 | 仅空库启动流程按显式配置创建初始管理员；已有用户时全部跳过；移除公开 /users/init，无固定密码或密码日志 |
| 前端恢复 | 不信任持久化 user JSON；首次访问通过 me 确认身份后再显示管理入口 |
| 前端异常 | 状态区分 unchecked/authenticated/anonymous/unavailable；网络故障保留凭证并可重试，不在跳登录时自动重复同一失败探测 |
| 前端交互 | 并发 401 只处理一次；登录错误不触发过期跳转；403 不清凭证；迟到请求不能覆盖新身份；保留安全的站内返回路径 |
| 基础防护 | JWT 密钥显式配置；CORS 来源可配置；单实例限流计数过期清理、容量有界、只信任显式代理来源并从转发链右侧验证 |
| 错误语义 | 参数格式 400、禁止访问 403、不存在 404、方法不支持 405、数据冲突 409，不统一包装为 500 |

资源权限沿用已修复的 AccessScope/PermissionService 与会话所有者规则。本次没有改动 AI 网关路径，也没有实现另一项 HTML 安全渲染改造。

## 首次使用配置

在 IDE 的运行环境变量或本地 Spring 配置中填写：

| 环境变量 | 要求 |
|---|---|
| JWT_SECRET | 必填，至少 32 字节的随机密钥；没有代码内置默认值 |
| BOOTSTRAP_ADMIN_USERNAME | 用户表为空时必填，管理员登录名 |
| BOOTSTRAP_ADMIN_PASSWORD | 用户表为空时必填，至少 8 字符且 UTF-8 不超过 72 字节 |
| BOOTSTRAP_ADMIN_DEPARTMENT | 可选，默认“信息技术部” |
| CORS_ALLOWED_ORIGINS | 可选，逗号分隔；默认 localhost:3000 与 127.0.0.1:3000 对应的 HTTP 来源 |
| TRUSTED_PROXY_IPS | 可选，逗号分隔；默认空，忽略转发 IP 头，仅按直接来源限流 |

首次启动仍创建“信息技术部、办公室、研究室”，并允许初始管理员使用指定部门。配置不完整时，账号初始化在写入前报出明确提示。已有用户时，不再因保留的 bootstrap 配置重置账号或密码。未来重建数据库/重置 ID 时，应使开发环境旧凭证失效，不复用旧账号 ID。

完整 Compose 仅同步增加这些环境变量的传递及强制 JWT_SECRET 配置；未执行容器操作。首次启动会写数据库，因此仍需遵守项目的允许与备份规则。

## 接口示例

登录：POST `/api/auth/login`，请求 `{ "username": "owner", "password": "用户自行设置的密码" }`。

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "token": "签发的 JWT",
    "user": {
      "id": 1,
      "username": "owner",
      "role": "admin",
      "deptName": "信息技术部",
      "department": { "id": 1, "name": "信息技术部" },
      "enabled": true
    }
  }
}
```

GET `/api/auth/me` 使用 `Authorization: Bearer ...`，data 直接为上述 user 对象。登录和用户页面已一同调整，开发阶段不保留旧响应兼容分支。

## 验证记录

- 后端：`mvn -o -q -f backend/pom.xml test`，73 项测试通过，0 失败、0 错误、0 跳过。AuthenticationFlowTest 真正经过 SecurityFilterChain，JPA 用例仅使用隔离 H2，外部服务 mock，不加载业务应用启动器。
- 前端：在 frontend 中运行 `npm test`，8 项测试通过。使用现有 TypeScript 和 Node 测试器，测试命令需 Node 22.15+（本机验证为 Node 24.15），未新增 npm 依赖。
- 前端：`npm run build` 完成类型检查与生产构建；保留现有大 bundle/第三方注解告警，不在此次扩大性能改造范围。
- 沙箱曾阻止 Java attach 和 Vite realpath；已获准在沙箱外完成相应验证。测试中还发现移除初始化接口后原异常处理误报 500，已补 405 处理并回归通过。

未进行真实数据库、真实浏览器端到端或正式部署验证；不把单元/集成测试结果等同于上线验收。

## 仍然明确保留的边界

退出仅清除此浏览器页面的登录信息，不撤销仍有效的 Token 副本。密码修改、停用后再启用的强制重登、全部/单设备退出均需以后按具体需求增加失效机制。前端 HTML 注入风险属于独立修复项，不能因认证改进而视为已解决。
