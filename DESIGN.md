# PR Agent 设计文档（实现用）

> v1.2，2026-10-02。由与用户的设计讨论整理而成，是本工程的需求来源。
>
> v1.1 变更：① 第 7 节明确一个任务只涉及一个 PR（用户确认没有多 PR 场景），删去"按 prUrl 分别生成报告"；② 第 8 节配置改为与 pr-reviewer 相同的 `llm.*` 方式，修正原文"密钥从环境变量读"与"密钥放 config/application.yml"前后不一致的问题。
>
> v1.2 变更：第 9 节外层调用失败时，对话历史退回到本条指令之前。原因：中途失败会留下有工具调用但没有工具结果的 AssistantMessage，下一次请求会被 API 拒绝（用户确认）。

---

## 0. 给实现者（AI 助手）的说明

- 按第 12 节的里程碑实现，**不加本文档没要求的功能**。第 13 节列了明确不做的事。
- 文档与实际环境（库的 API、版本、行为）冲突时先提出来，不要自行替换。
- 依赖版本先查 Maven Central，**不要凭记忆写版本号**；Spring AI 的 API 写代码时按实际版本核对。
- 修改设计要同步更新本文档并说明原因。
- 用户有 C# / .NET 背景，代码注释沿用 pr-reviewer 的「C# 对照」风格。
- **密钥**只放 `config/application.yml`（被 `.gitignore` 排除），不打印、不提交、不写进其他文件。

---

## 1. 目标与范围

### 背景
姊妹工程 `pr-reviewer`（`/Users/lee/Documents/spring/pr-reviewer`）用 **Workflow** 方式审查 GitHub PR：拉 PR → 每个文件一次 LLM 调用（map）→ 代码去重汇总（reduce），流程由 Java 代码固定。它同时以 MCP server 方式暴露了 `list_pr_files` / `review_file` / `aggregate_findings` / `review_pr` 四个 tool。

### 目标
做一个 **由 DeepSeek 自主决策的 Agent**：用户在终端里下自然语言指令（如"review 一下这个 PR"），Agent 自己决定调用哪些 MCP tool、按什么顺序、审哪些文件，最后给出结论。

同时满足两点：
1. **过程可见**：每一轮 Agent 决策、每次工具调用、外层 / 内层 token 和耗时都实时打印在终端。
2. **可评估**：Agent 的结果生成与 pr-reviewer `ReviewReport` 兼容的报告 JSON，能直接用 pr-reviewer 的 `eval` 算召回，与 `mapreduce` / `single` 两种 Workflow 模式对比；并量化 Agent 编排本身带来的损失（漏审文件、汇总时漏传 / 篡改 findings）。

### 与 Claude Code 调 MCP 的区别
原理相同（Agent + MCP tools）。区别在于 Agent 循环是自己的代码：每一步可打印、可记录、可限制步数，能自动生成评估报告，能换模型。

### v1 范围内
- 终端 REPL，多轮对话
- 通过 MCP stdio 调用 pr-reviewer 的三个 tool（不开放 `review_pr`）
- 手写工具调用循环，步数上限
- 实时打印每一步；任务结束自动保存报告
- pr-reviewer 的少量兼容改动（第 11 节）

---

## 2. 技术栈

| 项 | 选择 |
|---|---|
| 语言 | Java 21 |
| 构建 | Maven |
| 框架 | Spring Boot 3.5.16 + Spring AI 1.1.8（与 pr-reviewer 一致；实现前到 Maven Central 核对） |
| 外层 LLM | DeepSeek，OpenAI 兼容接口（`spring-ai-starter-model-openai`，与 pr-reviewer 同样的接入方式） |
| MCP | `spring-ai-starter-mcp-client`，stdio 传输 |
| JSON | Jackson |
| 测试 | JUnit 5 |

**为什么不用 LangChain4j**：两者都能做；选 Spring AI 是为了与 pr-reviewer 用同一套技术栈。**为什么手写循环**：实验需要每一轮的 token、工具参数和原始返回，并在循环中插入步数上限；框架自动执行工具时这些细节不好拆开。

---

## 3. 架构

```
你（终端 REPL）
   │  "review 一下 https://github.com/.../pull/1"
   ▼
┌─────────────── pr-agent ──────────────────────────────┐
│ ReplRunner      读输入；/save /clear /exit            │
│      ▼                                                │
│ AgentLoop       DeepSeek 决定 → 执行工具 → 结果放回    │
│      │          对话 → 下一轮（最多 max-steps 轮）     │
│      ├──► TaskRecorder   记录每一轮和每次工具调用      │
│      ├──► ConsolePrinter 实时打印                     │
│      ▼                                                │
│ McpToolGateway  过滤掉 review_pr，只开放 3 个工具      │
│                                                       │
│ ReportBuilder   任务结束后根据记录生成报告 JSON         │
└──────┼────────────────────────────────────────────────┘
       │ MCP stdio（子进程）
       ▼
┌─────────────── pr-reviewer.jar mcp ───────────────────┐
│ list_pr_files / review_file / aggregate_findings      │
│ review_file 内部调用 DeepSeek 审查单个文件             │
└───────────────────────────────────────────────────────┘
```

**两层 DeepSeek**：
- **外层（Agent，本工程）**：看到系统提示、用户指令、工具返回值；决定下一步。看不到 diff 正文。
- **内层（审查员，pr-reviewer）**：`review_file` 内部的单文件审查，与现有实现完全相同。

**启动**：`java -jar pr-agent.jar` 进入 REPL，同时按配置拉起 pr-reviewer jar 作为 MCP 子进程。

---

## 4. 模块（包 `com.lee.pragent`）

| 模块 | 职责 |
|---|---|
| `ReplRunner` | 循环读取终端输入；`/save [路径]`、`/clear`、`/exit` 三个命令，其余输入交给 `AgentLoop` |
| `AgentLoop` | 手写工具调用循环；持有对话历史（系统提示 + 多轮对话）；单次任务最多 `agent.max-steps` 轮 |
| `McpToolGateway` | 封装 Spring AI MCP 客户端；按工具名过滤掉 `review_pr`，对外提供 3 个工具；`ReportBuilder` 也通过它直接调用 `aggregate_findings` |
| `TaskRecorder` | 记录一次任务（一条用户指令 → Agent 最终回答）的全过程 |
| `ConsolePrinter` | 把每一轮、每次工具调用实时打印到终端（格式见第 5 节） |
| `ReportBuilder` | 根据任务记录生成报告 JSON（第 7 节） |

**任务**的边界：从用户发出一条指令到 Agent 给出纯文本回答（或被强制停止）。多轮对话里每条指令是一个独立任务，各自记录；对话历史跨任务保留，直到 `/clear`。

---

## 5. 一次任务的流程

```
用户输入 → 加入对话历史（UserMessage）
循环（第 n 轮，n ≤ max-steps）：
  1. 调用外层 DeepSeek：带上 3 个工具的定义，关闭框架自动执行工具
     → 记录本轮外层 input / output token、耗时
  2. 回复含工具调用 → 按顺序逐个通过 McpToolGateway 执行
     → 记录工具名、参数、原始返回、是否出错、耗时；实时打印
     → AssistantMessage + 工具结果消息加入对话历史，进入下一轮
  3. 回复是纯文本 → 打印 Agent 回答，任务结束
超过 max-steps → 强制停止，打印并记录"达到步数上限"
任务结束：若本任务调用过 aggregate_findings → 自动生成报告，保存到 reports/
打印任务汇总行
```

终端输出示意：

```
> review 一下 https://github.com/DavidLee617/bookmarket/pull/1

[Agent 第 1 轮] (外层 in 1,820 / out 45 token)
  → 调用 list_pr_files(prUrl=…/pull/1)          0.8s
  ← 11 个待审文件，1 个跳过（application.yml：不匹配过滤规则）
[Agent 第 2 轮] (外层 in 2,410 / out 120 token)
  → 调用 review_file(OrderService.java)         3.1s  内层 in 2,650 / out 310 token
  ← 4 个 findings
  …
[Agent 第 5 轮]
  → 调用 aggregate_findings(25 条)
  ← 去重后 25 条

Agent：本次共审查 11 个文件，发现 25 个问题……

── 本次任务：5 轮 · 14 次工具调用 · 外层 in 18,300 / out 2,900 · 内层 in 29,067 / out 2,201 · 41.2s
   报告已自动保存：reports/agent-20261002-153012.json
```

- 内层 token 取自 `review_file` 返回值里的 `calls`。
- 每一轮打印外层累计 input token，便于观察上下文增长。
- 工具结果的打印只给摘要（文件数、findings 条数、错误信息），不打印完整 JSON。

---

## 6. Agent 系统提示（`src/main/resources/prompts/agent-system.md`）

工具各自的说明由 MCP 自动提供，系统提示只补充：
- **角色**：GitHub PR（Java 仓库）代码审查助手，用中文回答。
- **能力边界**：你看不到 diff 正文；审查一个文件 = 调用 `review_file`，真正的审查在工具里完成。
- **汇总**：调用 `aggregate_findings` 时，必须把 `review_file` 返回的 findings **原样**传入，不修改、不删减、不新增；findings 很多时可以分几批调用。
- **最终回答**：按严重程度总结；说明审查失败、被跳过、没有审的文件；不得自己编造 findings。
- **部分审查**：用户只要求部分内容时（如"只看 OrderService"），只审需要的文件。

**不写固定步骤**（如"第一步必须调 list_pr_files"）。调用顺序由 Agent 自己决定。

---

## 7. 报告格式

保存为 JSON，字段与 pr-reviewer 的 `ReviewReport` 一致，另加一个 `agent` 对象。

### 与 ReviewReport 一致的字段

| 字段 | 来源 |
|---|---|
| `mode` | 固定 `AGENT` |
| `prUrl` / `headSha` / `prFiles` / `skippedFiles` | `list_pr_files` 的返回值 |
| `findings` | 本任务所有 `aggregate_findings` 调用结果的并集，再由代码调用一次 `aggregate_findings` 去重排序 —— 代表 **Agent 实际交出来的结果**，`eval` 按它算召回 |
| `failedFiles` | `review_file` 返回带 error 的文件 |
| `errors` | 各次 `review_file` 返回的 errors，加上工具调用层面的错误（工具不存在、参数非法、MCP 返回 isError） |
| `calls` | 外层每一轮一条（label `agent#1`、`agent#2`……），加上每次 `review_file` 内部的 calls |
| `totalLatencyMs` | 任务墙钟时间 |
| `totalInputTokens` / `totalOutputTokens` | 外层 + 内层合计（分开的数值在 `agent` 里） |

分批汇总时取并集是安全的：去重键是 `file + line + category`，同一键只来自同一个文件。

### `agent` 对象

| 字段 | 含义 |
|---|---|
| `steps` | 每一轮：外层 token、耗时、本轮调用的工具（名、参数、是否出错、耗时） |
| `stoppedByStepLimit` | 是否因达到 max-steps 被强制停止 |
| `outerInputTokens` / `outerOutputTokens` / `innerInputTokens` / `innerOutputTokens` | 外层、内层分别统计 |
| `rawFindings` | 代码把所有 `review_file` 原始 findings 直接交给 `aggregate_findings` 的结果（不经过 Agent） |
| `droppedFindings` | 在 `rawFindings` 中、不在 `findings` 中（Agent 漏传） |
| `fabricatedFindings` | 在 `findings` 中、不在 `rawFindings` 中（Agent 编造或改了位置 / 类别） |
| `alteredFindings` | 按 `file + line + category` 匹配上，但 severity 不同（Agent 改了严重程度） |
| `unreviewedFiles` | `list_pr_files` 列出的待审文件中，Agent 一次 `review_file` 都没调用的 |
| `repeatedReviews` | 被 `review_file` 调用超过一次的文件及次数 |

- 本任务没有调用过 `aggregate_findings` 时不自动生成报告；`/save` 仍可手动保存（`findings` 为空，`rawFindings` 照常计算）。
- **一个任务只涉及一个 PR**（可以是其中多个文件），因此每个任务至多一份报告。v1 不支持一条指令里审多个 PR。
- `/save` 不带路径时保存最近一个任务的报告到 `reports/`；带路径时保存到指定路径。

---

## 8. 配置

沿用 pr-reviewer 的方式：`src/main/resources/application.yml` 打进 jar，`spring.ai.openai.*` 引用 `llm.*`，`llm.*` 默认从环境变量 `LLM_*` 读；本地的 `config/application.yml`（Spring Boot 自动加载工作目录下的 `./config/`，被 `.gitignore` 排除）直接写 `llm.*` 覆盖。两种方式任选一种。

```yaml
# src/main/resources/application.yml（打进 jar，不含真实值）
spring.ai.openai:                 # 外层 Agent 的 DeepSeek
  base-url: ${llm.base-url}
  api-key: ${llm.api-key}
  chat.options:
    model: ${llm.model}
    temperature: 0
llm:
  base-url: ${LLM_BASE_URL:https://api.deepseek.com}
  api-key: ${LLM_API_KEY:}
  model: ${LLM_MODEL:deepseek-chat}
spring.ai.retry.max-attempts: 3   # 外层 API 出错最多尝试 3 次
spring.ai.mcp.client.stdio.connections.pr-reviewer:
  command: java
  args: [-jar, <pr-reviewer.jar 路径>, mcp, --spring.config.additional-location=file:<pr-reviewer 的 config/>]
agent:
  max-steps: 50
  reports-dir: reports
```

```yaml
# config/application.yml（本地，不提交）
llm:
  api-key: <DeepSeek key>
```

- 本工程只需要 DeepSeek key；GitHub token 由 pr-reviewer 自己读取，**不要**写进本工程配置。
- jar 路径、配置路径都写在配置里，不写死在代码中。
- 日志写 stderr（和文件），stdout 只给 REPL 交互输出。

---

## 9. 护栏与错误处理

| 情况 | 处理 |
|---|---|
| 超过 `max-steps` | 强制结束任务，打印提示，写进报告（`stoppedByStepLimit`）；已有结果照常生成报告 |
| 工具返回错误（MCP `isError=true`，如文件不在 PR 中） | 错误原文作为工具结果返回给 Agent，由它决定重试或跳过；记录 |
| 调用不存在的工具（含被过滤的 `review_pr`）或参数不是合法 JSON | 同上：错误返回给 Agent；记录 |
| 外层 DeepSeek 调用失败（3 次后仍失败） | 本次任务失败，打印原因；对话历史**退回到本条指令之前**（丢弃本条指令及其中途消息），之前的历史保留，可重发指令 |
| 上下文超长 | DeepSeek 返回超长错误 → 任务失败，提示 `/clear`；不做自动压缩 |
| MCP 子进程退出 | 打印错误，需重启 pr-agent；不做自动重连 |
| 启动时连不上 MCP / 缺 DeepSeek key | 启动失败，报出缺什么 |

**已知性能差异**：同一轮的多个工具调用**按顺序逐个执行**（pr-reviewer 的 MCP server 同步处理请求，并行发送也会被逐个处理）。Agent 模式墙钟时间会明显长于 mapreduce 的 4 并发，这是对比实验的结果之一，如实记录，v1 不优化。

---

## 10. 测试

单元测试不调用真实 DeepSeek、GitHub 或 MCP 子进程。

| 被测模块 | 内容 |
|---|---|
| `AgentLoop` | 假 ChatModel 按剧本返回：正常结束；超过 max-steps 强制停止；工具出错原文回传给 Agent；调用不存在的工具；参数非法 JSON |
| `McpToolGateway` | `review_pr` 被过滤，另外 3 个保留 |
| `ReportBuilder` | 手工构造任务记录：漏传 / 编造 / 改 severity / 未审 / 重复审都能识别；分批汇总取并集；没调用 aggregate 时不自动生成 |
| 报告格式 | JSON 字段名与 `ReviewReport` 一致 |

**验收**：真实运行 PR #1（https://github.com/DavidLee617/bookmarket/pull/1），pr-reviewer 的 `eval` 能读新报告并算出召回。

---

## 11. pr-reviewer 需要的改动

只加字段，不改变现有行为：
1. `list_pr_files` 返回值加 `prFiles`（PR 全部文件，GitHub 原始顺序；eval 分段需要）。
2. `ReviewMode` 加 `AGENT`（否则 eval 反序列化失败）。
3. 确认 `eval` 读报告时忽略未知字段（`agent`）；不忽略则放开。
4. 同步更新 pr-reviewer 的 `config/DESIGN.md`（7.2 节补新字段、注明 pr-agent 是使用方）和 `SESSION.md` 决定表。pr-reviewer 第 13 节"不做 Agent 自主决策"保留：pr-reviewer 本身仍是 Workflow。

---

## 12. 里程碑

| # | 内容 | 验收 |
|---|---|---|
| A1 | 工程骨架、配置、MCP 客户端连上 pr-reviewer | 启动后列出 3 个工具，`review_pr` 已过滤 |
| A2 | `AgentLoop` + `ConsolePrinter` + REPL | "review 一下 PR #1" 从头跑到尾，终端每一步可见 |
| A3 | `TaskRecorder` + `ReportBuilder` + `/save` + pr-reviewer 改动 | 自动生成报告，`eval` 算出召回 |
| A4 | PR #1 三种模式对比 | 召回、token、耗时、Agent 编排损失填进对比表 |

---

## 13. 明确不做（v1）

- Web UI、HTTP 接口、登录、多用户
- 数据库、对话持久化（重启后对话丢失）
- 对话历史自动压缩
- 工具并行执行
- MCP 子进程自动重连
- 让 Agent 直接读 diff 正文的工具
- 开放 `review_pr` 给 Agent

---

## 14. 写代码时需要核实的点

- ~~Spring AI 1.1.8 关闭自动执行工具的方式及工具调用消息的构造方式~~ **已核实（A2）**：运行时传 `ToolCallingChatOptions.builder().toolCallbacks(..).internalToolExecutionEnabled(false)`，`OpenAiChatModel.buildRequestPrompt` 会与默认 options（model、temperature）合并；工具调用取 `AssistantMessage.getToolCalls()`（`ToolCall(id, type, name, arguments)`）；工具结果用 `ToolResponseMessage.builder().responses(List<ToolResponse(id, name, responseData)>)`；token 取 `ChatResponse.getMetadata().getUsage()`。
- ~~Spring AI MCP 客户端是否给工具名加前缀~~ **已核实（A1）**：自动配置注册 `DefaultMcpToolNamePrefixGenerator`，只去掉非法字符，仅在重名时加 `alt_N_`；实际工具名为 `list_pr_files` / `review_file` / `aggregate_findings` / `review_pr`，无前缀。
- ~~MCP 客户端 starter 是否自动注册 `ToolCallbackProvider` bean~~ **已核实（A1）**：自动注册 `SyncMcpToolCallbackProvider`（内部缓存 listTools 结果）。本工程直接用 ChatModel、显式传入 3 个工具，不经过 ChatClient 的自动收集。
- ~~pr-reviewer `eval` 对未知字段的处理~~ **已核实（A3）**：eval 用 Spring Boot 默认的 `ObjectMapper`，忽略未知字段 `agent`，无需放开；pr-reviewer 加了测试 `AgentReportCompatibilityTest`。另外 pr-reviewer 的流水线只区分 SINGLE / 其他，所以 `review` 命令与 `review_pr` 显式拒绝 `AGENT`（否则会实际跑 mapreduce 却标成 AGENT）。
- 报告 `errors` 中工具调用层面的错误用 `stage=INTERNAL`、`callLabel=agent#n`：eval 会把 stage 反序列化成 pr-reviewer 的 `ReviewStage` 枚举，不能自造取值。

A1 期间发现、A2 要用的事实（来自 `spring-ai-mcp` 1.1.8 源码）：
- `SyncMcpToolCallback.call`：MCP 返回 `isError=true` 或调用异常时抛 `ToolExecutionException`；参数不是合法 JSON 时 `jsonToMap` 直接抛异常；正常时返回 MCP `content` 列表的 JSON（需取出其中 text）。
- MCP 客户端 `request-timeout` 默认 20s，`review_file` 内部 LLM 调用最长 120s 且会重试，已在 `application.yml` 调为 10m。
- 启动失败时 MCP stdio 传输的读线程不是守护线程，JVM 不退出；`main` 中捕获启动异常后 `System.exit(1)`。
- 子进程命令用 `${java.home}/bin/java`，保证与 pr-agent 用同一个 JDK（PATH 上的 `java` 不一定是 21）。
