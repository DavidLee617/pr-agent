package com.lee.pragent.agent;

import com.lee.pragent.agent.TaskRecords.Step;
import com.lee.pragent.agent.TaskRecords.TaskResult;
import com.lee.pragent.agent.TaskRecords.ToolInvocation;

/**
 * AgentLoop 的进度通知，用于实时打印（ConsolePrinter）。AgentLoop 本身不依赖打印。
 * C# 对照：≈ IProgress&lt;T&gt; / 一组事件（event Action&lt;...&gt;），这里用接口 + 默认空实现。
 */
public interface AgentListener {

    /** 开始第 index 轮（即将调用外层 LLM）。 */
    default void stepStarted(int index) {
    }

    /** 外层 LLM 已返回（工具尚未执行）。step.tools() 此时为空。 */
    default void llmResponded(Step step, int toolCallCount) {
    }

    default void toolStarted(String name, String arguments) {
    }

    default void toolFinished(ToolInvocation invocation) {
    }

    default void taskFinished(TaskResult result) {
    }
}
