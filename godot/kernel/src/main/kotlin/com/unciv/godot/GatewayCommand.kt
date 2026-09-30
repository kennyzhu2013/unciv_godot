package com.unciv.godot

import com.unciv.logic.GameInfo
import kotlinx.serialization.json.JsonObject

/** 效果命令如何取得候选对局：就地使用当前局、克隆后推进回合、或从存档载入。 */
internal enum class CandidateSource { CURRENT, CLONE_TURN, LOADED }

/**
 * 命令执行上下文：请求、会话标识、候选对局与输出槽。
 * 命令以 GameSession 内 lambda 形式定义，直接捕获 this 调用其私有助手，故此处只需携带数据。
 */
internal class CommandContext(
    val request: JsonObject,
    val sessionId: String,
    val revision: Int,
) {
    lateinit var candidate: GameInfo
    lateinit var snapshot: PlayerSnapshot
    var savedPath: String? = null
    var battleResult: JsonObject? = null
    var greatPersonResult: JsonObject? = null
}

internal sealed interface GatewayCommand

/** 只读查询：直接产出 data；不推进 revision、不重建 snapshot、不备份。 */
internal interface QueryCommand : GatewayCommand {
    fun data(ctx: CommandContext): JsonObject
}

/** 推进 revision 并回传 snapshot 的命令（mutation 与 lifecycle）。 */
internal interface EffectCommand : GatewayCommand {
    /** 候选对局来源。 */
    val source: CandidateSource get() = CandidateSource.CURRENT
    /** 执行前是否 clone 备份以便失败回滚（就地修改类为 true）。 */
    val inPlace: Boolean get() = false
    /** 无副作用校验（作用于 current，在备份前执行）；返回执行闭包。 */
    fun prepare(ctx: CommandContext): () -> Unit = { }
    /** 执行阶段（作用于 candidate）。默认调用 prepare 返回的闭包。 */
    fun execute(ctx: CommandContext, prepared: () -> Unit) { prepared() }
}
