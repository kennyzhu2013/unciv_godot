package com.unciv.godot

import com.unciv.logic.GameInfo
import kotlinx.serialization.json.JsonObject

/** 效果命令如何取得候选对局：就地使用当前局、克隆后推进回合、或从存档载入。 */
internal enum class CandidateSource { CURRENT, CLONE_TURN, LOADED }

/**
 * 命令执行上下文：请求、会话标识、候选对局与输出槽。
 * 领域命令在各自的 contribute 里登记；仍留在 GameSession 的少数命令会捕获会话私有助手。
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

/**
 * 推进 revision 并回传 snapshot 的命令（mutation 与 lifecycle）。
 *
 * prepare 在取备份之前运行，必须无副作用：只校验当前局，并返回稍后要执行的闭包。
 * execute 在候选对局上运行，默认调用该闭包，所以多数命令只写 prepare。
 * nextTurn 与 acknowledge 同时实现两段，因为校验必须发生在克隆或备份之前，
 * 而推进回合、移除弹窗必须发生在候选对局上。这是唯一有意保留的双阶段。
 */
internal interface EffectCommand : GatewayCommand {
    /** 同一事务内补齐原生画面更新的胜利检查；存档命令不检查。 */
    val checksVictory: Boolean get() = true
    /** 结算期间只允许生命周期、投票结果确认与继续游戏。 */
    val allowedDuringVictory: Boolean get() = false
    /** 候选对局来源。 */
    val source: CandidateSource get() = CandidateSource.CURRENT
    /** 执行前是否 clone 备份以便失败回滚（就地修改类为 true）。 */
    val inPlace: Boolean get() = false
    /** 无副作用校验（作用于 current，在备份前执行）；返回执行闭包。 */
    fun prepare(ctx: CommandContext): () -> Unit = { }
    /** 执行阶段（作用于 candidate）。默认调用 prepare 返回的闭包。 */
    fun execute(ctx: CommandContext, prepared: () -> Unit) { prepared() }
    /**
     * 仅 [CandidateSource.LOADED] 覆盖：在备份决策之后、validateGame 之前打开候选对局。
     * 返回 null 时由 GameSession 按 [source] 取当前局或回合副本。
     * load／demo 因此不再在注册表之外按 action 字符串二次分发。
     */
    fun openCandidate(ctx: CommandContext): GameInfo? = null
}
