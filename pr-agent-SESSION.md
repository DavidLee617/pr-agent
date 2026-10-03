# pr-agent 交接文档

> 截至 2026-10-02。这是新会话的起点：用 VSCode 打开 `/Users/lee/Documents/spring/pr-agent`，让 Claude 先读本文件，再读该目录下的 `DESIGN.md`。

---

## 0. 工作约定（新会话必须遵守）

- **一律用中文回答**。代码标识符、命令、文件名保持原样。
- 用户有 C# / .NET 背景，代码注释写「C# 对照」，风格参考 pr-reviewer 的代码（如 `pr-reviewer/src/main/java/com/lee/prreviewer/llm/LlmClient.java`）。
- `pr-agent/DESIGN.md` 是需求来源：按里程碑实现，**不加设计没要求的功能**；文档与实际环境冲突时先提出来；依赖版本先查 Maven Central，**不要凭记忆写版本号或 Spring AI API**。
- **密钥**：只放 `config/application.yml`（要加进 `.gitignore`），不打印、不提交、不写进其他文件。提交前检查 `git diff --cached | grep -E 'sk-[A-Za-z0-9]{16,}|github_pat_[A-Za-z0-9_]{16,}'`。
- git：提交信息结尾加 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`；推送前先问用户。
- 修改设计要同步更新 `DESIGN.md` 并说明原因。

---

## 1. 现在在哪一步

| 步骤 | 状态 |
|---|---|
| 讨论需求、定方案 | ✅ |
| 写设计文档 `pr-agent/DESIGN.md` 并提交（`03c6e01`） | ✅ |
| 用户审阅设计文档（v1.1：单任务单 PR、配置改 `llm.*`） | ✅ |
| 写实施计划 `PLAN.md` | ✅（3 个待确认点已答复：RESULTS.md、跑 3 次、失败回滚历史） |
| A1 骨架 + 配置 + MCP 客户端 | ✅ 启动列出 3 个工具；核实结果见 DESIGN §14 |
| A2 AgentLoop + ConsolePrinter + REPL | ✅ PR #1 真实跑通：4 轮 · 13 次工具调用 · 外层 in 20,605 / out 4,386 · 内层 in 29,067 / out 2,210 · 63.5s |
| A3 报告 + /save + pr-reviewer 改动 | ✅ PR #1 自动生成报告，eval 召回 17/19（89.5%）；本次无编排损失（dropped / fabricated / altered / unreviewed / repeated 均为 0）。pr-reviewer 改动提交在其 `m4-mapreduce` 分支 `3aa6cab` |
| A4 三种模式对比 | 未开始 |

A1 已完成：pom、配置、`McpToolGateway`、启动打印工具。`config/application.yml` 已建好模板，`llm.api-key` 用户已填写。`McpToolGateway.call` 推迟到 A2 与 AgentLoop 一起实现。

---

## 2. 要做什么（一句话）

把 PR 审查做成**由 DeepSeek 自主决策的 Agent**：用户在终端里说"review 一下这个 PR"，Agent 自己决定调用哪些 MCP tool、审哪些文件、按什么顺序，最后给出结论；每一步的工具调用、token 和耗时实时打印在终端；结果生成能被 pr-reviewer 的 `eval` 读取的报告，用于和 Workflow 模式做对比实验。

---

## 3. 相关的三个目录

| 目录 | 是什么 | 本次要不要动 |
|---|---|---|
| `/Users/lee/Documents/spring/pr-agent` | **新工程**（本次的工作对象），独立 git 仓库，分支 main | 是 |
| `/Users/lee/Documents/spring/pr-reviewer` | 旧工程：Workflow 方式的 PR 审查 + MCP server（四个 tool）。它的交接文档是该目录下的 `SESSION.md`，设计在 `config/DESIGN.md` | 只做 DESIGN 第 11 节的几处小改动（在 A3） |
| `/Users/lee/Documents/spring/pr review agent/agent` | 用户之前用 Spring Initializr 建的 demo（Boot 4.1.1 + Spring AI 2.0.1） | **不用、不动**（用户选择了新建 pr-agent） |

---

## 4. 讨论中做出的决定及原因

| # | 决定 | 原因 / 用户的选择 |
|---|---|---|
| 1 | **新建独立工程**，不在 pr-reviewer 里加 Agent 模式 | 用户要求 |
| 2 | 新工程作为 **MCP 客户端**，把 `pr-reviewer.jar mcp` 当子进程拉起来调用（不复制代码、不做 Maven 依赖） | pr-reviewer 基本不用改；新工程负责决策、旧工程负责执行，职责清楚 |
| 3 | **不给 Agent 开放 `review_pr`**，只开放 `list_pr_files` / `review_file` / `aggregate_findings` | `review_pr` 是一键跑完的 Workflow，开放的话 Agent 多半直接调它，体现不出自主编排 |
| 4 | 交互方式：**终端 REPL**（多轮对话，`/save` `/clear` `/exit`） | 用户中途考虑过 localhost:8080 网页；用户真正关心的是"每一步能看到"，终端前台程序直接打印即可满足，代码更少。以后要网页可在 AgentLoop 外加一层 |
| 5 | 报告**兼容 pr-reviewer 的 `ReviewReport`**，能直接跑 `eval` | 要和 mapreduce / single 做三方对比 |
| 6 | 报告**两份结果都记**：`findings` = Agent 实际交给 `aggregate_findings` 的；`agent.rawFindings` = 代码直接合并 `review_file` 原始结果；并统计漏传 / 编造 / 改 severity / 未审文件 / 重复审 | 能区分"审漏了"和"Agent 汇总时丢了" |
| 7 | 框架用 **Spring AI**（不用 LangChain4j），**手写工具调用循环** | 用户选择。与 pr-reviewer 同一技术栈；手写循环才能拿到每一轮 token、工具参数、原始返回，并加步数上限。用户问过"大企业是不是都用框架"——Spring AI 本身就是框架，与 LangChain4j 同类，手写的只是在它的接口上的几十行循环 |
| 8 | 技术栈 **Spring Boot 3.5.16 + Spring AI 1.1.8**，DeepSeek 走 OpenAI 兼容 starter | 与 pr-reviewer 一致（用户选择不用 demo 里的 Boot 4 / Spring AI 2.0） |
| 9 | `aggregate_findings` **允许分批**调用，报告取并集 | 大 PR 的 findings 全写进一次工具参数可能超出 DeepSeek 单次输出上限；去重键 file+line+category 不跨文件，分批不影响结果 |
| 10 | 同一轮多个工具调用**按顺序执行**，不并行 | pr-reviewer 的 MCP server 同步处理请求；Agent 模式会比 mapreduce 慢，如实记录为实验结果 |

用户问过的一个概念问题（回答时保持一致）：**"外层换成 DeepSeek，跟我在 Claude Code 里调 MCP 有什么区别？"** —— 原理相同。区别是 Agent 循环是自己的代码：每一步可打印、可记录、可限制步数，能自动生成评估报告，能换模型。

---

## 5. 下一步

1. 用户审阅 `pr-agent/DESIGN.md`，有修改先改文档并提交。
2. 写实施计划：按 DESIGN 第 12 节里程碑拆成具体步骤（建议保存为 `pr-agent/PLAN.md`），用户确认后再写代码。
3. 实现时先核对 DESIGN 第 14 节列出的几个点（Spring AI 1.1.8 关闭自动执行工具的方式、MCP 工具名前缀、MCP 客户端 starter 是否自动注册 ToolCallbackProvider、pr-reviewer eval 对未知字段的处理）。

里程碑（详见 DESIGN 第 12 节）：
- **A1** 骨架 + 配置 + MCP 客户端连上 pr-reviewer，列出 3 个工具
- **A2** AgentLoop + ConsolePrinter + REPL，"review 一下 PR #1" 跑通
- **A3** TaskRecorder + ReportBuilder + `/save` + pr-reviewer 小改动，`eval` 能算召回
- **A4** PR #1 上三种模式对比

---

## 6. 环境要点

- macOS（Apple Silicon），zsh。JDK 21 已在 `~/.zshenv` 里设为默认（`JAVA_HOME=/opt/homebrew/opt/openjdk@21/...`），**不要用机器上的 JDK 26**。Maven 3.9.16。
- pr-reviewer 的 jar：`/Users/lee/Documents/spring/pr-reviewer/target/pr-reviewer.jar`（在 pr-reviewer 下 `mvn package` 生成）。以 MCP 方式启动的命令：
  ```
  java -jar /Users/lee/Documents/spring/pr-reviewer/target/pr-reviewer.jar mcp --spring.config.additional-location=file:/Users/lee/Documents/spring/pr-reviewer/config/
  ```
  它自己读 DeepSeek key 和 GitHub token（在 pr-reviewer 的 `config/application.yml`，**不要输出内容**）。
- 外层 DeepSeek：`base-url=https://api.deepseek.com`（不带 `/v1`），`model=deepseek-chat`，temperature 0。pr-agent 需要自己的 `config/application.yml` 放 key（用户自己填，不要让用户在对话里贴 key）。
- 测试 PR：https://github.com/DavidLee617/bookmarket/pull/1 （12 个文件，11 个 `.java`；标准答案在 `pr-reviewer/ground_truth.json`，19 个埋点）。
- 旧模式在 PR #1 上的基线（用于 A4 对比）：mapreduce 召回 17/19、输入/输出 token 29067/2201、约 9s；single 召回 14/19、6418/1622、约 8.5s。

---

## 7. 遗留提醒

- **密钥泄露**：`pr review agent/agent/src/main/resources/application.properties` 里明文写着 DeepSeek key；该 key 也在本次会话输出中出现过。建议用户在 DeepSeek 控制台换新 key，并把 demo 里的 key 改成环境变量。pr-reviewer 的 key 和 GitHub token 之前也在对话中出现过（见 pr-reviewer `SESSION.md` 第 9 节 #7）。
- pr-reviewer 的 `SESSION.md` 第 3 节提交表缺最近两条（`c89aa78`、`d7294a5`），A3 改 pr-reviewer 时顺手补上，并在其决定表记一笔"新增 pr-agent 作为 MCP 使用方"。
