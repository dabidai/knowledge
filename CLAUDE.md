# AI 协作通用开发规范

以下规范提炼自多个项目的实战经验，适用于所有使用 AI 辅助的编程项目。

---

## 一、项目启动

### 1.1 脚手架检查清单

**前端：**
- [ ] TypeScript strict 模式开启（`"strict": true`）
- [ ] ESLint 配置好（至少继承推荐规则集）
- [ ] 路径别名配置好（`@/` → `./src/`）
- [ ] 开发代理配置好（前端 `/api` → 后端端口）
- [ ] `npm run dev` 能启动，能看到 hello world
- [ ] 设计系统（CSS 变量/主题 token）在后续 UI 开发中按需加入，**脚手阶段不写**

**后端：**
- [ ] 分层目录结构建好（controller/service/model/config）
- [ ] 全局异常处理器配好
- [ ] 日志框架配好（结构化日志，至少含时间、级别、消息）
- [ ] 配置管理配好（敏感信息用环境变量，非敏感有默认值）
- [ ] 健康检查端点写好（`GET /api/health`）
- [ ] 服务能启动，健康检查能返回 200

### 1.2 数据库选型

原型和单用户场景优先选 **SQLite**：
- 零配置单文件数据库，随应用启动自动创建
- 不需要 docker-compose 里多一个数据库容器
- 测试用 `:memory:` 模式，每个测试独立隔离
- 缺点：不支持并发写入

需要全文搜索、地理空间查询、高并发写入时换 MySQL/PostgreSQL。

### 1.3 环境变量

项目第一个 commit 就应该包含 `.env.example`：

```bash
# 必填 — 不配启动就报错
API_KEY=your-key-here

# 可选 — 不配则降级
GITHUB_TOKEN=

# 数据库
DATABASE_URL=postgresql://localhost:5432/db
```

要点：每个变量注释说明用途、标记必填/可选、敏感值用占位符、**决不能提交真实的 .env 文件**。

### 1.4 .gitignore

```
node_modules / __pycache__
dist / target / .next
.idea / .vscode
.env / .env.*
logs / *.log
uploads / data/*.db
*.mp4 / *.mov
```

---

## 二、Commit 规范

### 2.1 前缀

使用 Conventional Commits：

| 前缀 | 用途 | 示例 |
|------|------|------|
| `feat:` | 新功能 | `feat: 添加语音识别引擎` |
| `fix:` | Bug 修复 | `fix: 修复重复事件在跨月时的查询错误` |
| `refactor:` | 重构 | `refactor: 提取规则引擎为独立模块` |
| `docs:` | 文档 | `docs: 补充 API 文档和架构图` |
| `chore:` | 杂项 | `chore: 添加一键部署脚本` |
| `style:` | 样式 | `style: 优化移动端响应式布局` |
| `test:` | 测试 | `test: 补充安全规则检测用例` |

### 2.2 三条铁律

- **一个 commit 只做一件事** — 不把 bugfix 和 feature 混在一起
- **说清楚做了什么** — 禁止 "update code"、"fix bug" 这种模糊描述
- **主题行后用 `——` 补充细节** — 如 `fix: 修复轮询超时 —— 把 code=2 的状态也视为正常响应`
- **一个 PR 内多次 commit** — 一个 PR 对应一个大功能。若大功能可拆分为多个独立小功能，每完成一个小功能就 commit 一次，逐步推送到同一个 feature 分支，最后整体合并。避免把所有代码堆在一起一次性 commit

### 2.3 分支与 PR 策略

- **基于 PR 开发**：每个功能从 main 建 feature 分支 → 开发 → commit → push → 创建 PR → 合并回 main
- **一个 PR 一件事**：粒度尽可能细，大功能拆成多个独立 PR
- **PR 合并后 main 必须可运行**
- 分支命名：`feature/<描述>`、`fix/<描述>`、`docs/<描述>`、`chore/<描述>`
- **PR 描述四部分**：标题（一句话做了什么）+ 功能描述 + 实现思路 + 测试方式

---

## 三、代码规范与架构

### 3.1 命名规范

- **文件/类名**：PascalCase。避免通用教程名 —— 用具体业务名降低代码相似度风险
- **方法/变量**：camelCase（`uploadFile()`、`userCount`）
- **常量**：UPPER_SNAKE_CASE（`MAX_FILE_SIZE`）
- **包名/目录名**：全小写（`controller`、`service`）

### 3.2 注释规范

**类注释**：说明类的功能作用。
```java
/** 分章解析器 —— 用正则匹配章节标题模式识别章节边界 */
```

**方法注释**：说明函数作用、参数含义、返回值含义。
```java
/**
 * 将全文切分为章节列表。
 * @param content 全文文本
 * @return 按序排列的章节列表，序号从 1 开始
 */
```

**字段注释**：每个字段说明含义。
```java
/** 章节序号，从 1 开始 */
private int index;
```

**不写的**：getter/setter、toString、构造器等自动生成的方法。不写 "这个类是用来..." 的废话。

### 3.3 设计模式

**策略模式** — 当需要支持多种实现时：
```
Engine (interface)
  ├── PrimaryEngine     # 主方案
  ├── FallbackEngine    # 降级方案
  └── [FutureEngine]    # 未来扩展

Orchestrator            # 路由 + 降级逻辑
```
加新引擎只需新增一个实现类，调用方零改动。

**管道模式** — 将复杂流程拆成独立阶段：
```
输入 → 阶段1 → 阶段2 → 阶段3 → 输出
```
每个阶段可独立测试、独立替换。阶段性结果要持久化。

**适配器模式** — 一套内容多端输出：
```
输入 → 中间表示 → 适配器A → 平台A
                → 适配器B → 平台B
```
新增目标 = 新增一个适配器，核心逻辑不动。

### 3.4 分层架构

```
Controller（参数校验 + 路由，不写业务逻辑）
  → Service（业务逻辑）
    → Repository/Mapper（数据访问）
    → External API Client（第三方调用）
```
层与层之间通过接口通信，便于单测时 mock。

---

## 四、安全

### 4.1 Fail-Closed 原则

外部服务出错时，**宁可拒绝也不静默放行**：

```
❌ try { result = check(text); } catch { return PASS; }  // 静默放行
✅ try { result = check(text); } catch { return REVIEW; } // 标记为需人工确认
```

### 4.2 密钥不泄漏

- API Key 只存后端环境变量，前端永远不接触
- 需要前端调第三方 API 时，通过后端代理转发
- 日志中不能打印 API Key 或完整敏感内容
- `.env.example` 可以提交，`.env` 绝不能提交

### 4.3 服务端重校验

- 关键操作必须在服务端重新校验
- 不能信任客户端传来的状态，前端只是缓存/展示层

### 4.4 输入校验

- 所有 API 输入都要验证：类型、长度、格式、枚举白名单
- 前端用 Zod（TS）/ Joi（JS），后端用 Pydantic（Python）/ Bean Validation（Java）

---

## 五、测试策略

### 5.1 按 ROI 分配

| 优先级 | 测什么 | 原因 |
|--------|--------|------|
| **最高** | 安全检查 | 漏了有安全后果 |
| **高** | API 契约 | 调不通就是事故 |
| **高** | 纯函数（解析器、转换器、编码器） | 容易出错、容易测试 |
| **中** | 核心业务流程端到端 | 保护主链路 |
| **低** | UI 组件渲染 | 变化快、维护成本高 |

### 5.2 安全测试关键用例

每个安全功能至少覆盖：明确安全→放行 / 明确不安全→拦截 / 服务异常→fail-closed / 边界值

### 5.3 测试基础设施

- 使用内存数据库做测试，避免外部依赖
- HTTP 客户端用 mock server，不要真调外部 API
- 每个测试文件独立，不依赖执行顺序

---

## 六、工程化

### 6.1 工具链

- **ESLint**：前端必须配
- **Prettier**：统一格式化
- **TypeScript strict**：`strict: true` + `noUnusedLocals` + `noUnusedParameters`

### 6.2 部署

- 提供一键部署脚本或 docker-compose.yml
- 线上地址要稳定

### 6.3 优雅降级

应用在**没有配置任何第三方服务时**应该能启动并显示可用的界面：
- API Key 未配置 → 提示用户配置，不白屏
- 第三方服务超时 → 返回降级结果，不抛异常
- 可选功能不可用 → 隐藏入口、给出提示

---

## 七、文档

### 7.1 README 结构

```
# 项目名称
一句话定位 — 解决什么问题

[Badge] [License]

线上地址：...

## 核心功能
| 功能 | 说明 |

## 技术架构（Mermaid 图）

## 快速开始
git clone → 配环境变量 → docker compose up / npm run dev

## 技术栈
| 层 | 技术 | 版本 |

## 第三方依赖
列出所有引用的库/框架及其用途

## API 文档
| 方法 | 路径 | 说明 |

## 项目结构
目录树
```

### 7.2 Mermaid 架构图

```mermaid
graph LR
    A[用户输入] → B[API 网关]
    B → C[业务服务]
    C → D[外部 API]
    C → E[数据库]
```

### 7.3 AGENTS.md 模式

- `CLAUDE.md` 写核心规则（简短）
- `AGENTS.md` 写项目背景、技术决策、第三方服务说明
- `CLAUDE.md` 里用 `@AGENTS.md` 引用

---

## 八、AI 协作效率法则

### 8.1 每次对话必须给的上下文

| 信息 | 示例 |
|------|------|
| 当前在哪一步 | "已完成上传/分章，现在要做转换引擎" |
| 这一轮要完成什么 | "实现 POST /api/scripts/convert 端点" |
| 相关文件路径 | "NovelService.java、ScriptConverter.java" |
| 约束条件 | "不要动前端代码"、"兼容已有的 DTO" |

### 8.2 一次只做一件事

- **一个 Prompt = 一个功能**：不要同时写后端 + 前端 + 测试
- **一个 Commit = 一个子功能**：大功能拆成小 commit
- **先 Plan 再 Code**：复杂功能先让 AI 说出理解的方案，确认后再写

### 8.3 让 AI 自查

每次写完代码后追加：

> 检查：安全漏洞、错误处理、边界情况、是否引入新依赖

### 8.4 外部依赖降级法则

```
✅ API Key 未配 → 提示用户配置，不白屏
✅ 服务超时 → 返回降级结果，标记异常
✅ 功能不可用 → 隐藏入口
❌ 未配 Key 直接报 500
❌ 超时不处理，页面一直转圈
```

实现要点：对外部服务封装 `isConfigured()` 方法，调用方先判断再使用。

### 8.5 外部配置集中管理

字符串常量、模板、第三方服务配置抽到独立文件，不要散落在业务逻辑里。

### 8.6 数据模型先行

先定 Schema/Entity/DTO → 序列化测试 → 业务逻辑。数据模型确定后接口契约、校验规则、前端类型都跟着确定。

### 8.7 错误信息可读化

- 对用户：返回中文描述，不是异常栈
- 对开发者：log.error 打印完整堆栈
- 外部输入解析失败：截取关键报错字段，不输出完整框架异常链

### 8.8 CWD（工作目录）陷阱

AI 工具不会自动记住当前目录。每次命令前加上 `cd`：

```bash
cd /path/to/subdir && mvn test    # ✅
mvn test                           # ❌
```

给 AI 时明确说明各子目录位置。

---

## 九、提交前最终检查

- [ ] `git log --oneline` 所有 commit message 清晰一致
- [ ] 没有提交 `.env` 或含密钥的文件
- [ ] 没有提交 `node_modules`、`target`、`dist` 等构建产物
- [ ] 没有残留的 `console.log` / `print` 调试语句
- [ ] README 中的快速开始步骤能从头到尾跑通
- [ ] 线上 demo 可访问
- [ ] 端口号、数据库名等没有使用脚手架默认值
- [ ] 所有第三方 API Key 走环境变量，前端不暴露
- [ ] License 文件存在
- [ ] 第三方依赖在 README 中列明

---

## @AGENTS.md

详细项目背景、运维命令、常见错误排查见 [AGENTS.md](AGENTS.md)。

## 十、服务器运维命令

### 启动顺序（必须按此顺序）

```bash
# 1. 基础设施（Docker 容器）
cd /home/ubantu/llm/docker/anythingllm/knowledge/docker
docker compose up -d

# 2. AI 服务（FastAPI，端口 8000）
cd /home/ubantu/llm/docker/anythingllm/knowledge/ai-service
source venv/bin/activate
kill $(ss -tlnp | grep 8000 | grep -oP 'pid=\K\d+') 2>/dev/null
EMBEDDING_MODEL=./models/bge-large-zh-v1.5-local OLLAMA_MODEL=qwen3:32b nohup uvicorn main:app --host 0.0.0.0 --port 8000 > ai-service.log 2>&1 &
sleep 10 && ss -tlnp | grep 8000

# 3. 后端（Spring Boot，端口 8080）
cd /home/ubantu/llm/docker/anythingllm/knowledge/backend
mvn package -DskipTests -q
kill $(ss -tlnp | grep 8080 | grep -oP 'pid=\K\d+') 2>/dev/null
LOG_FILE="app-$(date +%Y%m%d-%H).log"
nohup java -jar target/knowledge-base-0.1.0.jar > "$LOG_FILE" 2>&1 &
ln -sf "$LOG_FILE" app.log
sleep 3 && ss -tlnp | grep 8080

# 4. 前端（Vue，端口 3000）
cd /home/ubantu/llm/docker/anythingllm/knowledge/frontend
kill $(ss -tlnp | grep 3000 | grep -oP 'pid=\K\d+') 2>/dev/null
nohup npm run dev -- --host > frontend.log 2>&1 &
```

### 查看状态

```bash
ss -tlnp | grep -E '8000|8080|3000'
tail -50 /home/ubantu/llm/docker/anythingllm/knowledge/backend/app.log
tail -20 /home/ubantu/llm/docker/anythingllm/knowledge/ai-service/ai-service.log
```

### 拉取更新

```bash
cd /home/ubantu/llm/docker/anythingllm/knowledge && git pull gitee master
```
