package com.anvex.core.agent

/**
 * One event in a turn's stream (see [AgentEngine]). The set is deliberately
 * minimal — exactly what a chat UI needs to render a turn: lifecycle, text
 * and reasoning deltas, the tool-call approval cycle, results, and a
 * terminal failure. New variants require the same review bar as a new tool
 * (ADR-0002).
 */
sealed interface AgentEvent {

    /** The turn has started and the loop is working. */
    data class TurnStarted(val turnId: String) : AgentEvent

    /** An incremental chunk of the assistant's visible reply text. */
    data class TextDelta(val text: String) : AgentEvent

    /** An incremental chunk of model reasoning (shown collapsed in the UI). */
    data class ReasoningDelta(val text: String) : AgentEvent

    /** The planner proposed [call]; approval may still be required. */
    data class ToolCallProposed(val call: ToolCall) : AgentEvent

    /** The user (via [ApprovalGateway]) approved the call [callId]. */
    data class ToolCallApproved(val callId: String) : AgentEvent

    /** The user denied the call [callId]; [reason] is optional user input. */
    data class ToolCallDenied(val callId: String, val reason: String? = null) : AgentEvent

    /** A tool finished; [result] carries its output or error. */
    data class ToolResultReceived(val result: ToolResult) : AgentEvent

    /** Terminal event: the turn finished normally. */
    data class TurnCompleted(val turnId: String) : AgentEvent

    /** Terminal event: the turn failed; [message] is user-presentable. */
    data class TurnFailed(val message: String) : AgentEvent
}
