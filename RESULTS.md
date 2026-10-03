# PR #1 三种模式对比（A4）

> 2026-10-03。PR：https://github.com/DavidLee617/bookmarket/pull/1（12 个文件，11 个 `.java`，headSha `7fd23c0c`），标准答案 `pr-reviewer/ground_truth.json`（19 个埋点）。
> 内外层都是 DeepSeek `deepseek-chat`，temperature 0。召回由 pr-reviewer 的 `eval` 计算（±3 行，一对一匹配）。

## 1. 总表

| | mapreduce | single | **agent**（3 次：最低～最高） |
|---|---|---|---|
| 召回（全部） | 17 / 19（89.5%） | 14 / 19（73.7%） | **16～17 / 19**（84.2%～89.5%） |
| 审查范围内 | 17 / 18 | 14 / 18 | 16～17 / 18 |
| 单文件雷 / 跨文件雷 | 15/16 · 2/3 | 12/16 · 2/3 | 14～15/16 · 2/3 |
| HIGH / MEDIUM / LOW | 6/8 · 9/9 · 2/2 | 6/8 · 8/9 · 0/2 | 6/8 · 9/9 · 1～2/2 |
| findings 条数 | 25 | 18 | 21～24 |
| LLM 调用 | 11 | 1 | 外层 4 轮 + 内层 11 次 |
| 输入 token | 29,067 | 6,418 | 49,578～49,973（外层 20.5k～20.9k + 内层 29,067） |
| 输出 token | 2,201 | 1,622 | 6,273～6,629（外层 4.2k～4.5k + 内层 2.1k） |
| 墙钟 | 约 9s | 约 8.5s | 57.5s～64.0s |

mapreduce / single 用的是 pr-reviewer `SESSION.md` 第 8 节的基线，没有重跑：A3 对 pr-reviewer 的改动只加了字段，没有改审查逻辑，而且 Agent 模式下内层输入 token 也是 29,067，和 mapreduce 完全一样，说明审查的是同一份 diff。

## 2. Agent 每次运行

| 运行 | 报告 | 召回 | findings | 编排 | 外层 in / out | 内层 out | 墙钟 |
|---|---|---|---|---|---|---|---|
| 1 | `reports/agent-20261003-000515.json` | 17/19 | 24 | 4 轮：list → 11×review → aggregate(1 批) → 回答 | 20,906 / 4,462 | 2,113 | 57.5s |
| 2 | `reports/agent-20261003-000617.json` | 16/19 | 21 | 同上 | 20,511 / 4,203 | 2,070 | 58.6s |
| 3 | `reports/agent-20261003-000725.json` | 17/19 | 22 | 同上 | 20,858 / 4,476 | 2,153 | 64.0s |

`reports/` 被 `.gitignore` 排除，报告文件只在本机。

## 3. Agent 编排损失

| 指标 | 运行 1 | 运行 2 | 运行 3 |
|---|---|---|---|
| 漏审文件（unreviewedFiles） | 0 | 0 | 0 |
| 重复审查（repeatedReviews） | 0 | 0 | 0 |
| 汇总时漏传（droppedFindings） | 0 | 0 | 0 |
| 编造（fabricatedFindings） | 0 | 0 | 0 |
| 改 severity（alteredFindings） | 0 | 0 | 0 |
| `rawFindings` 召回 | 17/19 | 16/19 | 17/19 |

3 次的 `rawFindings` 和 `findings` 完全相同，所以召回的差异都来自内层审查，不是 Agent 编排造成的。运行 2 漏掉的 B16（命名差：`calc`、参数 `a`、`c`）在 `review_file` 的原始结果里就没有。

## 4. 结论

1. **召回**：Agent 模式和 mapreduce 持平（17/19，3 次中有 1 次是 16/19），明显高于 single（14/19）。两种模式都漏了 B03（文件被过滤规则跳过）和 B11（跨文件、在 DTO 里），这是内层审查和过滤规则的限制，和怎么编排无关（mapreduce 漏的也是这两个，见 pr-reviewer `SESSION.md` 第 8 节）。运行 2 的 16/19 在内层自身的波动范围内：pr-reviewer 实测 mapreduce 在 temperature 0 下跑两次，26 条里也只有 24 条一致。
2. **编排损失**：在 PR #1 上 3 次都是 0。Agent 每次都审了全部 11 个文件，把 findings 一批原样交给 `aggregate_findings`。不过 PR #1 只有 21～24 条 findings，一批就能传完。漏传、改写的风险要在 findings 多、需要分批的大 PR 上才看得出来。
3. **成本**：外层 Agent 多花约 2.1 万输入 token（输入总量约为 mapreduce 的 1.7 倍）和约 4.3k 输出 token。外层输出里约一半是把 findings 重新写进 `aggregate_findings` 的参数，另一半是最终回答。
4. **耗时**：大约是 mapreduce 的 6.5～7 倍。11 次 `review_file` 按顺序执行（mapreduce 是 4 并发），再加上 4 轮外层 LLM 调用。这是 DESIGN 第 9 节预期的结果，v1 不做优化。
5. **编排路径很稳定**：3 次都是同样的 4 轮。A3 验收那次分成两批调用 `review_file`（6 + 5，共 5 轮），结果相同。

## 5. 没有被报告覆盖的现象

- **最终回答的文字本身没有评估**。A2 试运行（当时还没有报告）中，Agent 的回答写"24 个问题（HIGH 15、MEDIUM 6、LOW 3）"，实际 `aggregate_findings` 返回的是 23 条，LOW 下也只列了 2 条。A4 的 3 次回答和报告一致。报告的 `findings` 取自工具结果，不受回答文字影响；但如果有人只看回答，可能会被误导。
- Agent 的中间说明有时是英文（如 "I'll start by listing the files…"），最终回答是中文。
