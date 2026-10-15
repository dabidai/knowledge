# 权限与隔离修复后的代码复审

复审日期：2026-10-15；代码基线：`ae4c88d`。此次逐条阅读当前身份认证、资源读取、会话、导入、缓存、前端渲染和 Nginx/Python 入口；只做静态审查，未修改业务代码、运行攻击验证或连接数据库。

## 结论

此前七类资源入口与导入范围修复已接入当前业务代码，但不能把整个“权限与数据隔离”标为只剩测试。身份生命周期、前端内容执行、AI 外部入口仍有代码/配置缺口；历史撤权处理属于已明确保留的策略边界，需要单独判断是否符合上线要求。

## 已确认接入的修复

| 入口/机制 | 当前实现 |
|---|---|
| BM25/KNN | 同一 AccessScope 构造权限过滤，KNN 显式带相同 filters |
| 搜索/问答证据 | PermissionService 按 SQL 当前 Document/Item 权限过滤；结构化事项查询带范围 |
| 正文/下载 | 存储读取前 requireDocument；新来源链接走带认证的下载代理 |
| 图谱 | 详情 requireItem；查询以 SQL 允许的 Item/Document ID 集合限制 |
| 缓存 | search/browseTree/graphOverview 不再使用内容结果缓存 |
| 会话 | 读取/追加/重命名/删除校验 userId；Chat 历史取服务端 |
| 导入 | 文档匹配限制范围并拒绝多候选；Item/File ID 与关联事项禁止跨范围覆盖 |

现有 40 项测试的报告覆盖资源范围、拒绝入口、ES 过滤构造、会话归属及导入范围等，但不等于整个安全边界已完成审计，也不覆盖下述全部场景。此次没有重新运行这些测试。

## R01 / P1：删除后重建同名账号可继承旧 Token

证据：[JwtUtil](../../backend/src/main/java/com/knowledge/security/JwtUtil.java) 第 31 行以 username 作为 subject；[JwtAuthFilter](../../backend/src/main/java/com/knowledge/security/JwtAuthFilter.java) 第 37 行只按 username 查现有用户，随后直接使用现有 role；[UserController](../../backend/src/main/java/com/knowledge/controller/UserController.java) 第 83 行物理删除，create 允许重新使用已删除用户名。

触发顺序：用户持有未过期 Token → 管理员删除该账号 → 重建同名但不同部门/角色账号 → 旧 Token 的签名和有效期仍合法，过滤器把它认证为新账号。新账号若为 admin，旧 Token 持有人可能获得管理员权限；即使不是管理员，也可能进入新账号的部门和会话身份。

这不同于“按当前角色查询避免 JWT 角色过期”：不可变账号身份必须先一致，才可采用当前角色。应将 sub 绑定不可变 userId 或独立账号实例 ID，必要时配 authVersion/tokenVersion；禁止旧 username Token 在重建账号后映射到新身份。迁移时明确旧 Token 的失效政策，不能继续无条件兼容 username 回退。

## R02 / P1：未清洗 HTML 可在用户权限下执行

证据：[ChatPage](../../frontend/src/views/ChatPage.vue) 第 126 行开启 MarkdownIt html，62/76 行使用 v-html；[BrowsePage](../../frontend/src/views/BrowsePage.vue) 第 103 行开启 html，79 行 v-html；[API 层](../../frontend/src/api/index.ts) 第 12 行从 localStorage 读取 JWT。另有文件名字符串拼接 HTML 和图 tooltip 内容拼接。

文档、模型输出或文件名含恶意 HTML 时，渲染链路没有清洗，存在脚本/事件属性注入风险；具体传播取决于输入内容与用户打开路径。执行后可在受害用户身份下调用已正确鉴权的接口，服务端资源权限不能消除此类风险。此项是前端安全缺口，不表示已修复的 KNN/下载鉴权再次缺失。

应默认关闭原生 HTML，对最终渲染结果和高亮片段采用明确白名单；动态下载链接使用组件/安全属性绑定，图 tooltip 转义。避免只处理 Markdown 而漏掉 snippet 与文件名插值。

## R03 / P1：Compose 网关公开 AI 入口绕开 Java 认证

证据：[Nginx](../../docker/config/nginx/nginx.conf) 第 33 行 `/ai/` 直接代理 Python；[Python 服务](../../ai-service/main.py) `/embed`、`/embed-batch`、`/ask`、`/chat` 未声明身份认证或服务间鉴权。

在部署使用该网关配置、且没有额外上游鉴权时，未登录访问者可调用模型接口，绕开 Java 的登录/管理规则，并消耗 embedding/LLM 资源。该接口自身不查询业务数据库，因此不能据此声称它直接泄漏整个知识库。

应移除公网 `/ai/` 代理，仅允许 Java 通过内部网络调用；如必须暴露，则需要认证、容量限制及请求上限。宿主机启动方式的实际监听和防火墙也须核实。是否在线上可利用取决于真实部署，但仓库配置中的缺口已明确存在。

## 历史权限边界：不能只用“待测试”描述

- ChatController 第 87 行加载所有者的旧消息，第 158 行直接送入模型；没有按当前资源权限重新审核历史回答。用户调部门或资源撤权后，旧私有内容可继续参与新回答。ConversationService 只检查会话所有者。
- 既有权限设计明确保留“所有者可回看历史”，因此历史可读本身不应被当作违反本次约定的新漏洞。但“允许回看旧记录”与“允许把旧内容继续作为当前生成依据”应分别制定策略。
- 已停止签发新 MinIO 直链，但旧签名在有效期内仍可能可用。关闭签发不能立即撤销已发链接；若要求立即撤权，需要具体撤销/对象版本策略，并在允许和备份后处理。
- 历史文件错误归属、索引/对象污染需要只读调查后修正。按 SQL 鉴权不会自动识别一条 SQL 记录实际指向了别人的旧对象。这是历史数据整改，不是补一个测试就能完成。

## 其余未纳入七类资源入口修复的安全事项

[DataInitializer](../../backend/src/main/java/com/knowledge/config/DataInitializer.java) 仍在空库创建固定默认管理员密码并写日志；UserController 仍直接返回含 password 字段的 User 实体。前者需改为受控初始化，后者应用不含密码 hash 的响应 DTO。它们不等同于普通用户已可读取管理员 hash，但不能把整个身份与数据安全工作据此宣布收尾。

## 当前状态的准确表述

**七类资源入口/导入范围的针对性修复已实现；整个权限与数据隔离仍有代码/配置项、历史政策和数据整改项，此外还需要真实组件联调及部署验收。** 优先处理 R01、R02、R03，再明确历史撤权规则；本复审不替代完整安全审计或运行验证。
