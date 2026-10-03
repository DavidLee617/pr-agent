# PR Agent 实施计划

> 2026-10-02，对应 `DESIGN.md` v1.1。按 A1 → A4 顺序做，每个里程碑验收通过后提交一次，再进入下一个。

---

## 0. 已核实的前提

| 项 | 结论 | 依据 |
|---|---|---|
| Spring Boot 3.5.16 | Maven Central 存在（3.5.x 最新） | 2026-10-02 查 `spring-boot-starter-parent` metadata |
| Spring AI 1.1.8 | Maven Central 存在（1.1.x 最新），`spring-ai-starter-mcp-client` 1.1.8 存在 | 同上，`spring-ai-bom` / `spring-ai-starter-mcp-client` metadata |
| `review_file` 返回内层 token | 返回 `FileReviewResult(file, findings, calls, error, errors)`，`calls` 是 `CallMetrics` 列表 | pr-reviewer `McpTools.java`、`FileReviewResult.java` |
| `list_pr_files` 返回值 | `PrFiles(prUrl, headSha, files, skippedFiles, summary)`，**还没有 `prFiles`**（A3 补） | pr-reviewer `McpTools.java:80` |
| eval 读报告 | 用 Spring 注入的 `ObjectMapper`（Boot 默认不因未知字段报错），A3 用测试确认 | pr-reviewer `CliRunner.java:289` |

### 代码组织

包 `com.lee.pragent`，按职责分子包：

```
com.lee.pragent
├── PrAgentApplication
├── config     LlmProperties、AgentProperties（@Validated，缺 key 启动即失败）
├── mcp        McpToolGateway
├── agent      AgentLoop、TaskRecorder（及记录用的 record）
├── console    ReplRunner、ConsolePrinter
├── report     ReportBuilder、AgentReport
└── model      pr-reviewer 返回值的镜像 record（Finding、CallMetrics、ReviewError、SkippedFile、
               FileReviewResult、PrFiles）
```

**镜像 record**：按决定 #2 不依赖 pr-reviewer 的 jar，所以用自己的 record 反序列化工具返回的 JSON。字段名照抄 pr-reviewer，`Category` / `Severity` 用 String 保存，不做枚举，避免两边枚举改动时反序列化失败。类上加 `@JsonIgnoreProperties(ignoreUnknown = true)`。

---

## A1 工程骨架 + 配置 + MCP 客户端

| # | 步骤 | 说明 |
|---|---|---|
| 1 | `pom.xml` | parent 3.5.16；`spring-ai-bom` 1.1.8；依赖 `spring-ai-starter-model-openai`、`spring-ai-starter-mcp-client`、`spring-boot-starter-validation`、`spring-boot-starter-test`；`finalName=pr-agent`。注释沿用 pr-reviewer 的 C# 对照写法 |
| 2 | `.gitignore` | `target/`、`config/application.yml`、`reports/`、IDE 文件 |
| 3 | `src/main/resources/application.yml` | 按 DESIGN 第 8 节写；另外参照 pr-reviewer 加上：`web-application-type: none`、`banner-mode: off`，关闭 embedding / image 等用不到的模型；`spring.ai.retry.max-attempts: 3`；MCP stdio 连接的 jar 路径、config 路径用 `agent.pr-reviewer.*` 占位，默认值指向本机 pr-reviewer |
| 4 | `logback-spring.xml` | 日志只写 stderr 和 `~/.pr-agent/pr-agent.log`，stdout 只留给 REPL |
| 5 | `config/application.yml` 模板 | 只写 `llm.api-key: ` 一行，**留空，由你自己填** |
| 6 | 配置类 | `LlmProperties`（`@NotBlank apiKey`）、`AgentProperties`（`maxSteps`、`reportsDir`） |
| 7 | **核实** DESIGN §14 第 2、3 条 | 写一个临时启动日志，打印 MCP 客户端实际给的工具名（有没有前缀），以及 starter 是否注册了 `ToolCallbackProvider` bean。结论写进 DESIGN §14 |
| 8 | `McpToolGateway` | 从 MCP 客户端拿全部工具，按核实后的实际名称过滤掉 `review_pr`；提供 `tools()`（交给 ChatModel 的 3 个工具定义）和 `call(name, jsonArgs)` |
| 9 | 启动输出 | 启动后打印"已连接 pr-reviewer，可用工具：…"（REPL 在 A2 才写，A1 打印完就退出） |
| 10 | 测试 | `McpToolGatewayTest`：假的工具列表里有 4 个工具，过滤后剩 3 个，`review_pr` 被去掉 |

**验收**：`mvn package` 通过；`java -jar target/pr-agent.jar` 打印出 3 个工具名，没有 `review_pr`；`config/application.yml` 不填 key 时启动失败，报错信息说明缺 `llm.api-key`。

---

## A2 AgentLoop + ConsolePrinter + REPL

| # | 步骤 | 说明 |
|---|---|---|
| 1 | **核实** DESIGN §14 第 1 条 | 查 Spring AI 1.1.8 源码或文档，确认：`ToolCallingChatOptions` 关闭自动执行的方法；`AssistantMessage.getToolCalls()` 的结构；工具结果消息 `ToolResponseMessage` 怎么构造；`ChatResponse` 里 usage 从哪取。结论写进 DESIGN §14 |
| 2 | **核实** MCP 工具出错的表现 | MCP 返回 `isError=true` 时，Spring AI 的 MCP ToolCallback 是抛异常还是返回文本。`McpToolGateway.call` 统一成"返回 (结果文本, 是否出错)" |
| 3 | `prompts/agent-system.md` | 按 DESIGN 第 6 节写，不写固定步骤 |
| 4 | `AgentLoop` | 持有对话历史（系统提示 + 消息列表）；`runTask(userInput)`：每轮带上 3 个工具调用 ChatModel，关闭自动执行，遇到工具调用就按顺序逐个执行，纯文本则结束，超过 `max-steps` 强制停止。工具不存在、参数不是合法 JSON、MCP 出错时，错误原文作为工具结果交回给 Agent。外层调用失败时，任务失败，对话历史保留（**回滚到本条指令之前**，避免留下没有工具结果的 AssistantMessage，导致下一次请求被 API 拒绝）。`clear()` 清空历史 |
| 5 | 事件回调 | AgentLoop 通过一个监听接口（≈ C# 的事件 / `IProgress<T>`）通知"一轮开始 / 结束、工具调用开始 / 结束、任务结束"。ConsolePrinter 实现这个接口，A3 的 TaskRecorder 也实现它，AgentLoop 本身不依赖打印和记录 |
| 6 | `ConsolePrinter` | 按 DESIGN 第 5 节的格式打印：每轮的外层 token；工具调用的参数摘要、耗时；`review_file` 的内层 token；结果只给摘要（文件数、findings 条数、错误）；最后打印任务汇总行 |
| 7 | `ReplRunner` | `CommandLineRunner`：读 stdin，处理 `/clear`、`/exit`（Ctrl-D 也退出）；`/save` 先占位，提示"A3 实现"；其余输入交给 `AgentLoop` |
| 8 | 测试 | `AgentLoopTest`：用假 ChatModel 按剧本返回，假 Gateway。覆盖：正常结束；超过 max-steps；工具出错原文回传；调用不存在的工具；参数不是合法 JSON；外层调用失败后历史回滚 |

**验收**：真实运行，输入"review 一下 https://github.com/DavidLee617/bookmarket/pull/1"，从头跑到尾，终端每一步可见。之后再试一句"只看 OrderService"，看 Agent 是否只审这个文件（只观察，不作为通过条件）。

---

## A3 TaskRecorder + ReportBuilder + /save + pr-reviewer 改动

### pr-reviewer 部分（先做，pr-agent 的报告依赖 `prFiles`）

| # | 步骤 |
|---|---|
| 1 | `PrFiles` 加 `prFiles`（PR 全部文件，GitHub 原始顺序），先确认 `PreparedPr` 里能取到 |
| 2 | `ReviewMode` 加 `AGENT` |
| 3 | 测试：带 `agent` 字段、`mode=AGENT` 的报告 JSON 能用 eval 同一个 `ObjectMapper` 读出来；不能读就放开未知字段 |
| 4 | `mvn package` 跑全部测试，重新生成 `pr-reviewer.jar` |
| 5 | 更新 pr-reviewer `config/DESIGN.md` 7.2 节、`SESSION.md`：提交表补 `c89aa78`、`d7294a5`；决定表记一笔"新增 pr-agent 作为 MCP 使用方"。在 pr-reviewer 仓库单独提交 |

### pr-agent 部分

| # | 步骤 | 说明 |
|---|---|---|
| 6 | `TaskRecorder` | 实现 A2 的监听接口，记录一个任务：每轮外层 token / 耗时，每次工具调用的名称、参数、原始返回、是否出错、耗时；是否因步数上限停止；墙钟时间 |
| 7 | `AgentReport` | 字段名与 `ReviewReport` 一致，另加 `agent` 对象（DESIGN 第 7 节） |
| 8 | `ReportBuilder` | 从 `list_pr_files` 结果取 prUrl / headSha / prFiles / skippedFiles；`findings` = 各次 aggregate 结果的并集，再调一次 `aggregate_findings` 去重排序；`rawFindings` = 全部 `review_file` 原始 findings 调 `aggregate_findings` 的结果；算出 dropped / fabricated / altered（按 file+line+category 比对）、unreviewedFiles、repeatedReviews；calls 包含外层 `agent#n` 和内层 calls；token 合计 |
| 9 | 自动保存 + `/save` | 任务调用过 aggregate 就自动保存到 `reports/agent-yyyyMMdd-HHmmss.json`；`/save [路径]` 保存最近一个任务的报告（没调过 aggregate 也能保存，`findings` 为空） |
| 10 | 测试 | `ReportBuilderTest`：手工构造任务记录，覆盖 漏传 / 编造 / 改 severity / 未审 / 重复审 / 分批汇总取并集 / 没调 aggregate 时不自动生成；`AgentReportJsonTest`：序列化后的字段名集合包含 `ReviewReport` 的全部字段 |

**验收**：真实运行 PR #1，自动生成报告；在 pr-reviewer 下运行 `eval`，能读这份报告并算出召回。

---

## A4 PR #1 三种模式对比

| # | 步骤 |
|---|---|
| 1 | pr-agent 在 PR #1 上跑 Agent 模式，用 eval 算召回 |
| 2 | 整理对比表：召回（按 eval 的分段）、外层 / 内层 token、墙钟时间、工具调用次数、轮数；Agent 编排损失（dropped / fabricated / altered / unreviewed / repeated）；同时列出 `rawFindings` 的召回，用来区分"审漏了"和"汇总时丢了" |
| 3 | 第 1 步跑 3 次，记录每次结果和范围 |
| 4 | mapreduce / single 用 SESSION 第 6 节记录的基线（17/19、14/19）；如果 A3 改动后 headSha 或 pr-reviewer 行为有变，就重跑 |

**验收**：对比表和结论写进 `RESULTS.md`。

---

## 每个里程碑结束时

- `mvn package` 全部测试通过
- 提交前执行 `git diff --cached | grep -E 'sk-[A-Za-z0-9]{16,}|github_pat_[A-Za-z0-9_]{16,}'`，不能有输出
- 更新 `pr-agent-SESSION.md` 的进度表
- 推送前先问你

---

## 已确认（2026-10-02）

1. **A4 对比表**：新建 `pr-agent/RESULTS.md`，`pr-agent-SESSION.md` 里放链接。
2. **A4 运行次数**：Agent 模式在 PR #1 上跑 3 次，记录每次结果和范围。
3. **外层调用失败时的历史**：退回到本条指令之前（已写进 DESIGN 第 9 节）。
