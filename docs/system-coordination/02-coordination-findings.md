# 协作问题与代码证据

本表基于 `8afa789` 的静态分析。**已确认**表示源码存在相应缺口，不表示已在线上复现；**运行风险**表示影响取决于数据、配置或负载。P0 优先解决权限、跨范围覆盖与核心成功语义；P1 解决可靠协作；P2 解决规模和维护问题。

## 1. 权限、身份与写入范围

### C01 / P0：各读取入口的资源权限不一致（已确认）

证据：[ElasticsearchService.hybridSearch](../../backend/src/main/java/com/knowledge/service/ElasticsearchService.java) 的权限过滤只在 BM25 bool，顶层 knn 没有 filter；[ItemRepository.searchByTitleKeyword](../../backend/src/main/java/com/knowledge/repository/ItemRepository.java) 无部门条件；[BrowseController.getDocument/downloadDocument](../../backend/src/main/java/com/knowledge/controller/BrowseController.java) 未检查文档归属；[GraphController.queryItemGraph](../../backend/src/main/java/com/knowledge/controller/GraphController.java) 未接收授权范围；[ConversationService.addMessage](../../backend/src/main/java/com/knowledge/service/ConversationService.java) 无 userId/所有权校验。

影响：已登录并不等于可访问资源。跨部门向量结果可能进入生成上下文；知道 fileId/itemId 可以请求其他范围内容；会话追加可能写入其他用户会话。ES 的 query 与 knn 以 OR 合并，BM25 限制不能约束独立 KNN。[官方契约](https://www.elastic.co/guide/en/elasticsearch/reference/8.15/knn-search.html)。

修复：从认证用户构建统一 AccessScope，在 SQL、ES 两分支、图详情、正文/下载及会话追加中分别执行；候选进入模型及生成下载地址前再次核对。管理员范围也必须统一：浏览/图概览目前允许全部，搜索目前仍限定本部门/公共区。历史消息权限变化后也要重新处理。

验收：两个部门、一个公共区、同部门管理员/普通用户，直接调用每个接口并检查模型实际收到的证据；未授权会话追加后消息数不变。

### C02 / P0：缓存共享了不同权限结果（已确认）

证据：[BrowseController.tree](../../backend/src/main/java/com/knowledge/controller/BrowseController.java)、[GraphController.query](../../backend/src/main/java/com/knowledge/controller/GraphController.java) 的 key 只有部门，但方法内按 role 返回不同范围。搜索缓存也缺完整权限指纹，未来统一管理员搜索后必须一起修改。

影响：管理员先访问可把全量目录/图谱结果留给同部门普通用户；反向顺序会导致管理员少看数据。方法体的无部门判断还晚于缓存 key 求值。

修复：使用服务端生成的 scopeFingerprint + permissionVersion；规范化 key，禁止拼接歧义。上线新 key 时淘汰旧命名空间；错误结果不缓存。验收必须交换两类用户的访问顺序。

### C03 / P0：全库模糊文件匹配和对象键可能串数据（已确认；覆盖取决于样本）

证据：[ImportService.processDocument](../../backend/src/main/java/com/knowledge/service/ImportService.java) 扫描全库 expected 后 contains + findFirst；找不到后按全库 basename 覆盖，并重设部门/公共属性。原文对象键为 `deptName/fileName`，同部门不同子目录同名文件共用键。

影响：甲部门文件可能占用乙部门的 expected 记录；不同事项的“通知.pdf”可能互相覆盖；SQL 权限字段与 ES 使用本次导入范围不一致。first 没有业务上的确定性。

修复：采用 sourceNamespace + scopeId + sourceFileId；缺 ID 时按规范化相对路径和内容 hash 建立导入条目。模糊匹配只产生同范围候选，冲突进入人工确认，不能自动取首条。对象键改为稳定文件 ID/内容版本；部门迁移作为显式业务操作。

### C04 / P1：签阅重导不幂等（已确认）

证据：[CsvImportService.importOpinionsCsv](../../backend/src/main/java/com/knowledge/service/CsvImportService.java) 每行新建自增 Opinion；batchId 未用于去重。[GraphBuildService.createOpinion](../../backend/src/main/java/com/knowledge/service/GraphBuildService.java) 使用 CREATE，且 SIGNED 按 signer + signTime 匹配而非 Opinion 业务 ID。

影响：重复导入使签阅数量持续增长，SQL 与图也难逐条对账；缺时间时大量记录共享空时间匹配条件。

修复：优先使用来源签阅 ID，来源没有 ID 时设计经业务确认的规范化指纹与重复政策，不能仅凭同人同时间删除合法记录。图以 SQL opinionId MERGE。历史去重先出报告，允许且备份后执行。

### C05 / P1：元数据补录与文档导入的先后关系过于僵硬（已确认）

证据：[CsvImportService.importFileIndexCsv](../../backend/src/main/java/com/knowledge/service/CsvImportService.java) 重导时无条件 setStatus(expected)，保留旧 MinIO 字段；找不到 Item 就保存 null。签阅找不到事项也保存 null；后来 Item 导入没有待关联补偿。扫描接受 .xlsx，但实际 readCsv 仅按 CSV 文本读；初次扫描不含 .txt，恢复扫描却含 .txt。

影响：已可用文件重变“待匹配”；文档先来后补 CSV 可能形成新的 expected，与 orphan 并存；先到映射/签阅不会自动等事项补齐。格式支持在前端/扫描/解析层不一致。

修复：将匹配状态与处理状态分开；元数据 upsert 不降级已有内容。保存原始外部关联 ID 并重试关联；建立统一格式注册表，.xlsx 实现真实解析后才声明支持。多份同类型 CSV 必须确定排序或拒绝并报告冲突。

## 2. 导入、恢复与多存储一致性

### C06 / P0：任务完成不代表内容可用（已确认）

证据：[ImportService.processDocument/doImport/doImportDir](../../backend/src/main/java/com/knowledge/service/ImportService.java)：embedBatch 空列表时不保存 Document，但调用仍正常返回，上层推进进度；单文件异常被捕获，循环后仍 complete。[ElasticsearchService.bulkIndex](../../backend/src/main/java/com/knowledge/service/ElasticsearchService.java) 单项错误只写日志；chunks 为空也按成功保存。

影响：前端绿色完成、实际缺文档或缺块；complete 状态下前端没有重试按钮。失败后下一文件成功会直接把 processedFiles 设为扫描位置，计数也不能代表成功数。目录无 CSV 时还把总量加 1，全部成功仍可能不足 100%。

修复：逐文件/阶段结构化结果，检查向量数量、维度、有效数值，检查 bulk 每一项。区分成功、失败、跳过和不可检索；批次有 completed_with_errors。进度按完成条目而非索引位置，metadata_parsed 应在元数据实际完成后设置。

### C07 / P1：内存排队任务重启后没有恢复来源（已确认）

证据：[ImportService.submitAsyncImport/markOrphanedTasksAsFailed](../../backend/src/main/java/com/knowledge/service/ImportService.java) 先保存 pending 再 submit；拒绝入队未补偿；启动只处理 parsing/metadata_parsed，没有 pending。ImportTask 不记录 sourceType/sourcePath/manifest。上传临时文件正常 finally 删除，解压失败未必有可恢复清单。

影响：队列满时可能留下 pending；进程重启后排队任务留在数据库却不再执行。单纯“启动标失败”也不支持多实例区分任务归属。

修复：持久任务清单、租约、心跳和领取；提交接口与队列接纳状态一致；内存执行器只是执行工具。无可恢复来源的任务返回明确恢复建议，不能一直 pending。

### C08 / P0：重试绕过初次导入的调度与事务规则（已确认；并发损害为运行风险）

证据：[ImportController.retryTask](../../backend/src/main/java/com/knowledge/controller/ImportController.java) 直接同步调用 [ImportService.resumeImport](../../backend/src/main/java/com/knowledge/service/ImportService.java)；允许 parsing/metadata_parsed 重试。resumeImport 的 @Transactional 包住解析及外部调用，未按阶段执行。

影响：正在执行的批次可被再次执行；重试与首次导入争抢推理、对象与索引，并可能同时使用共享 Tesseract。长事务延迟进度可见性，并使外部成功、SQL 回滚或 rollback-only 状态的处理更难判断；具体行为需故障测试。

修复：重试异步接纳，统一 task runner；比较并交换状态、租约与执行 fencing token；活动任务拒绝或返回当前运行；每个 DB 提交短小，禁止网络/OCR 放在大事务内。实际耗时不占重试 HTTP 请求。

### C09 / P1：恢复依据与首次处理清单不一致（已确认）

证据：doImportDir 使用用户原始目录；resumeImport 只找 `workDir/batchId`。恢复按 importBatch + matched/orphan + minioPath 与文件名跳过；expected→matched 分支没更新 importBatch。恢复只处理文档，不补 CSV 或引用阶段。

影响：目录任务无法按现有路径恢复；分开导 CSV/文档后已成功匹配的文件仍可能重复处理；同 basename 不同文件会被误跳过；元数据阶段失败也不一定能恢复。

修复：每批固定 manifest，记录来源与版本、每条条目的阶段状态。恢复按 itemId/manifestEntryId/contentHash/stageVersion 判断；记录参与批次关系，保留来源批次而非用一个 importBatch 同时充当多种含义。

### C10 / P1：重导先删除旧索引，缺少安全发布（已确认）

证据：processDocument 的 existing 分支先 deleteByDocId，再对象上传和 embedding；其他匹配分支未统一删除旧块。ES ID 仅 docId_chunkIndex，内容变短时必须处理旧尾块。原文/Markdown 也原地覆盖，没有版本字段。

影响：新版本处理失败后旧版失去检索；原文、SQL、图和 ES 可能处于不同内容版本；不同入口更新残留尾块的行为不同。

修复：先生成候选版本，核对对象/分块/向量/索引，再发布 activeVersion。查询过滤 activeVersion 并最终复核，失败仍使用旧版；旧版本在保留期和备份政策下再清理。不能把“删除重建”当成正常恢复策略。

### C11 / P1：引用检测的输入对象不正确（已确认）

证据：doImport/doImportDir 传 d.minioPath 给 [MinioService.getMarkdown](../../backend/src/main/java/com/knowledge/service/MinioService.java)，该方法读取 markdown bucket，正确路径应为 minioMdPath；任务 complete 在引用阶段之前；恢复不执行引用检测。

影响：通常读不到对应正文，引用关系缺失但不影响“完成”。即使改路径，全库 findAll + 加载所有正文 + 两层遍历仍有规模风险；新编号加入时也需考虑已有文档对它的引用。

修复：使用明确的对象描述与正文版本；引用作为独立可重试派生任务，先提取规范化编号/候选，再增量匹配。记录引用抽取依据、版本与结果数；无结果、读取失败、尚未计算是三种状态。

### C12 / P1：图谱与主数据的生命周期不同（已确认）

证据：[GraphBuildService](../../backend/src/main/java/com/knowledge/service/GraphBuildService.java) 吞掉写错误；OWNS、CONTAINS、MEMBER_OF 仅 MERGE 新边，没有更新旧边；[UserController](../../backend/src/main/java/com/knowledge/controller/UserController.java) 与 [DepartmentController](../../backend/src/main/java/com/knowledge/controller/DepartmentController.java) CRUD 只写 SQL。

影响：管理界面与图显示不同对象；记录归属改变后旧边继续存在；SQL 事务回滚时先写的图仍可能残留。图详情不返回 REFERENCES，孤立文档也不在事项树中。

修复：把图定位为 PG 的派生视图；业务写入产生持久同步任务，消费者按版本覆盖受影响的关系集，稳定 ID 幂等。提供孤立文档入口，引用详情按权限明确接入；图故障显示滞后和最后同步时间。

### C13 / P1：缓存失效在提交时发生，完成后未发生（已确认）

证据：[ImportController](../../backend/src/main/java/com/knowledge/controller/ImportController.java) CacheEvict 在异步提交返回后执行；后台任务完成未再次失效；retry 只驱逐 importTasks，tasks 接口本身未发现 Cacheable。

影响：导入期间再次读树/搜索，会缓存旧结果；任务完成后继续看到旧内容，最长可能等缓存 TTL。新数据已经入库，页面却没有协同更新。

修复：按已提交的业务版本更新 dataVersion；SQL、ES、图分别以各自完成版本失效。失效失败可重试；公共区更新影响所有可读公共区的范围。提交时可刷新任务列表，但不能替代完成时失效。

## 3. 检索、AI 与前端契约

### C14 / P1：检索筛选与索引字段没有贯通（已确认）

证据：ImportService 的 DocIndex builder 没赋 itemYear；DocIndex/mapping 没 itemType；hybridSearch 接收 itemType 却不使用，year 强制 parseInt；[SearchController](../../backend/src/main/java/com/knowledge/controller/SearchController.java) 的缓存 key 缺 itemType。

影响：有筛选控件却未生效，或年度在 BM25 被过滤但 KNN 返回不符合年度的数据；自然语言年度不一定能整数解析。SQL 事项更新也不会自动更新 ES 元数据。所谓 RRF 实际未配置。

修复：统一 SearchFilter DTO 和字段类型，入口验证与归一化，补元数据投影/回填；两路条件相同。将纯检索和回答分离；如采用 RRF，显式实现并保留排名证据，不沿用不实注释。

### C15 / P1：图谱搜索把文件身份当事项身份（已确认）

证据：[GraphPage.handleSearch/selectItem](../../frontend/src/views/GraphPage.vue) 从 sources 取得 fileId，后续 `graphApi.query(item.id)`；后端该参数是 itemId，SourceDoc 没提供 itemId。搜索还调用带生成模型的 `/search`。

影响：命中文档后点击图谱为空；找图节点也被迫等一次模型回答。

修复：独立的事项检索接口，返回 resourceType、itemId、fileId，文件结果必须先明确其事项；孤立文件走文档子图或说明未关联。图节点 ID 加类型前缀，API 参数仍使用原始业务 ID。

### C16 / P1：同步推理占用异步事件循环（已确认；延迟程度为运行风险）

证据：[ai-service/main.py](../../ai-service/main.py) 的 async 路由直接调用同步 encode 和 chain.invoke；初次导入、查询 embedding、聊天共用 embedder/llm 与服务进程，没有容量区分。

影响：长批量或生成可能拖住交互请求和 health；Java 导入异步不等于 Python 推理并发。[FastAPI 官方说明](https://fastapi.tiangolo.com/async/)区分同步路径操作在线程池执行与直接调用同步函数。

修复：受限推理执行池/队列，在线请求与后台批量配额隔离；生成采用可用的异步客户端并限制 Ollama 并发。模型加载次数、CPU 线程和内存须压测；不能直接增加 uvicorn workers 而忽略模型复制。

### C17 / P1：外层超时没有覆盖整条调用链（已确认配置；影响为运行风险）

证据：[AIClient](../../backend/src/main/java/com/knowledge/service/AIClient.java) 每次读取超时 5 分钟；[前端 API](../../frontend/src/api/index.ts) 整次请求 5 分钟；Nginx 代理读取超时 300 秒。一次聊天依次 embedding、ES、SQL、generation，可能累积超过外层期限；同步重试也占请求。

修复：端到端 deadline，内部按剩余预算分配；纯检索、聊天与上传分别设限。取消/超时要标记 requestId/turnId 并停止接纳迟到结果；不能假设浏览器取消自动取消 Ollama 计算。

### C18 / P1：依赖故障被包装成正常业务结果（已确认）

证据：AIClient.chat/ask 返回“暂时不可用”普通答案；SearchController 仍 ok 并可缓存，ChatController 仍保存 assistant；ES 检查索引的 IOException 被吞成空结果；MinioService 读失败返回 null。

影响：连接失败与资料不足混在一起，失败答案可能存入会话/缓存；对象存储故障被当不存在。故障恢复后用户仍看缓存失败结果。

修复：依赖返回 typed result/error，区分 degraded、no_evidence、failed；embedding 故障可声明 BM25 降级，LLM 故障返回已有检索证据，ES 故障明确依赖异常；只有可接受质量的结果可缓存。

### C19 / P1：历史、结构化上下文与引用没有统一证据模型（已确认）

证据：ChatController 使用客户端 history；SQL 用完整问题匹配标题，非召回 itemId；Python 最后 10 条消息、最多 12 个上下文，正文各 1500 字，结构化上下文不受同样限额；num_ctx=4096。来源按文档去重而上下文按 chunk 编号，两边编号不能一一对应；前后端按文字删除来源/来源后缀。

影响：追问检索缺指代上下文；长签阅可能挤掉正文；引用难回到实际页/块。回答某部分“无法确定”就清空全部来源，会掩盖仍有效的证据。

修复：服务端授权会话历史、检索问题改写、总 token 预算；用召回 itemId 批量取得签阅；统一 evidenceId/docId/itemId/version/chunk/page；区分召回证据与最终引用，验证引用是否属于本次授权证据集。

### C20 / P1：迟到响应可能写入新的页面状态（已确认缺保护；竞态为运行风险）

证据：[ChatPage](../../frontend/src/views/ChatPage.vue) 等待聊天时允许切会话/新建，响应直接写全局 messages/currentConvId；selectConv 加载也不检查返回归属。[BrowsePage](../../frontend/src/views/BrowsePage.vue) 点击文档后直接写 docContent。

修复：每次捕获目标 ID 与 requestId；返回时只有当前上下文仍匹配才更新，或写入各自会话 store。使用 AbortController 和单调请求序号防止倒序覆盖；未完成 turn 在服务端有状态，不重复创建消息。

### C21 / P1：轮询和图表缺少完整生命周期（已确认）

证据：[ImportPage.pollProgress](../../frontend/src/views/ImportPage.vue) 局部 setInterval 无卸载清理、无 catch/退避、可重复创建，读共享 batchId；[GraphPage](../../frontend/src/views/GraphPage.vue) 匿名 resize listener 没移除，chart 没 dispose。

影响：切页仍轮询、慢请求堆叠、旧 timer 跟踪新批次、错误重复弹窗，重复挂载图表消耗资源。

修复：固定 batchId 的 composable，等待本次请求结束后 setTimeout，卸载和终态清理；断线退避、重新进入按任务 ID 恢复。图表统一销毁/移除事件；后台进度长期无变化提示“等待/阻塞”及心跳时间。

### C22 / P0：文档与模型文本直接变成可执行 HTML（已确认缺清洗；利用取决于输入）

证据：ChatPage/BrowsePage 的 MarkdownIt 开启 html；答案/正文/highlight 使用 v-html；ChatPage.renderMd 把 fileName/fileId 拼进 HTML 属性；GraphPage tooltip 拼接属性文本。未发现清洗逻辑。

影响：不可信文档、文件名、模型输出能影响 UI，且页面 JWT 存在 localStorage。前后端协作不仅要传内容，也要明确可信性。

修复：默认关闭原生 HTML，渲染后白名单清洗；highlight 只保留必要 em 标签；文件下载用 Vue 组件与 URL 编码，避免字符串替换插入标签；tooltip 转义文本。CSP 为补充措施，不代替清洗。

## 4. 运行、部署与可验证性

### C23 / P1：全面健康检查可误报正常（已确认）

证据：[HealthController.fullHealth/timedCheck](../../backend/src/main/java/com/knowledge/controller/HealthController.java) timedCheck 参数为 Runnable，this::checkDatabase 返回 false 被忽略；Redis check 固定 true；MinIO bucketExists 返回值未检查；timedCheck 自身没有截止时间。Python health 固定 UP，构建 ChatOllama 对象后即日志“连接成功”，未验证模型可推理。

修复：BooleanSupplier/结构化检查，逐项判 false；短超时并行检查且设总预算。分离 liveness、API readiness、导入/检索/图谱/生成能力状态，Ollama 检查单独返回，公开接口不泄漏细节。

### C24 / P1：部署约定互相冲突（默认值已确认；实际部署需验证）

证据：[Compose](../../docker/docker-compose.yml) 的 PostgreSQL 与 backend DB_PASSWORD 默认值不同，Neo4j 与 backend NEO4J_PASSWORD 默认值也不同；backend 只挂载 `/data/documents`，work-dir 为相对 `data/import`，不在该挂载。Java [Dockerfile](../../backend/Dockerfile) 和 [prebuilt](../../backend/Dockerfile.prebuilt) 未安装 Tesseract 原生依赖/语言数据；AI 模型镜像缓存被同路径 bind mount 遮蔽。depends_on 未配置健康条件；start.sh/deploy.sh 仅以端口判断启动。

影响：缺环境变量时不能连库；容器替换后恢复文件丢失；OCR 路径不可用；空模型卷可能触发启动联网；端口监听早于能力就绪。宿主机实际凭证、OCR 安装情况尚未核实。

修复：统一配置模板并缺值启动失败；work-dir 绝对路径持久化；镜像内安装并验证 OCR，模型缓存预置/校验；能力健康检查。Compose 默认只保证启动顺序，需 healthcheck + service_healthy 才能等待依赖就绪。[Docker 官方说明](https://docs.docker.com/compose/how-tos/startup-order/)。生产 HTML 不采用 deploy.sh 当前长期 immutable 缓存，hash 静态资源再长期缓存。

### C25 / P1：数据/资源入口的限制只写了一部分（已确认）

证据：application.yml 定义 document.import-root-dir，但 ImportService 未使用；browseDir 以 Linux `/` 为根，实际上允许浏览整个服务器。MAX_TOTAL_SIZE/COMPRESSION_RATIO_WARN 常量未用于累计控制；writeEntry 用无计数 Files.copy；7z 一次按条目声明大小分配 byte[]。

影响：管理员误选目录会扫描非业务文件；压缩头信息不可靠时大小控制不足；大条目和长期保留工作目录可能耗尽内存/磁盘，引发整个链路停滞。

修复：所有路径入口统一 realPath 后校验允许根目录，明确符号链接政策；按实际流字节累加限额，控制条目数/单文件/总量/剩余磁盘；7z 流式处理；保留期与可恢复性关联，清理只限合法工作目录且需适用备份政策。

### C26 / P2：全量读取与内容过滤隐藏质量损失（已确认；规模影响需测试）

证据：每文档读取全部 expected/全库 Document；CSV readAllBytes 并保留全部行；浏览树每事项查询文件；引用检测全库正文及双层遍历；getMarkdown 没 try-with-resources。100 chunk 截断和汉字比例过滤没有逐文件损失报告；解析器部分异常返回占位文字。

修复：带范围的匹配查询/索引、批量关联读取、分页/按需树、流式 CSV 与关闭对象流；ParseResult 带页错误和质量，内容覆盖率可核对。长文分批处理全部合法内容，资源限额超出转人工/失败；不以静默丢正文换吞吐量。PDF 阅读顺序/页眉脚阈值另用真实分页样本验证，不能仅凭注释认定坐标实现正确。

### C27 / P2：API 类型、状态和参数验证分散（已确认）

证据：多数入口使用 Map + 强制类型转换，topK/分页缺上限；有 HTTP 200 + 业务错误，也有 ResponseEntity/异常处理的 HTTP 错误。UserController 直接返回 User，User.password 没禁止序列化；前端 sources 是 any，历史来源是 JSON 字符串。

修复：验证 DTO、统一错误码/HTTP 状态、分页/文本/topK/批量上限；用户响应专用 DTO 不暴露密码 hash。OpenAPI/契约测试统一生成前端类型；新增契约先向后兼容，不一次破坏所有页面。

### C28 / P1：缺少跨组件对账与故障验证（已确认仓库覆盖不足）

证据：现有业务测试主要是 DocumentParserTest 和 CsvImportServiceTest，图服务在 CSV 测试中 mock；未发现完整导入恢复、权限隔离、缓存、模型故障和前端竞态的项目测试。现有性能日志按 batch 文本输出，但未贯穿 request/turn/version。ddl-auto:update、DataInitializer 和启动 orphan 修复会写数据，所以不能为“检查”随意启动连接正式库。

修复：独立只读对账报告 + 经允许的补偿执行；traceId/batchId/entryId/docId/version/turnId 串联日志与指标；健康、存储一致性、权限和故障验收按 [04](04-roadmap-and-verification.md) 建立。预签名 URL 不适合永久保存在消息来源中，应保存资源 ID，使用时重新鉴权签发；历史权限撤销及模型版本也要参与缓存策略。

## 5. 需要运行环境补证的事项

- 实际部署走宿主机还是 Compose，环境变量是否覆盖默认值，磁盘/内存/GPU/Ollama 并发情况。
- 文档是否跨部门同名、CSV 外部 ID 是否全局唯一、签阅重复是否存在合法业务含义。
- 空内容、解析占位符、截断文档、ES 缺块、图同步滞后、错误 importBatch 的实际数量。
- 页面竞态发生率、导入时交互 P95、模型上下文溢出、Redis 故障时缓存拦截器表现。
- SQL 懒加载字段在异步线程的实际访问是否失败；开启新事务不等于重新关联已脱离会话的实体，应用显式投影避免依赖。

这些补证不影响先修已确认的契约缺口，也不能用“尚未线上复现”代替可靠性设计。
