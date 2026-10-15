package com.unciv.godot

import com.unciv.logic.GameInfo
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Victory
import com.unciv.models.translations.tr
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** 胜利记录由原生写入；查询只投影己方进度，继续游戏只设置原生持久化标志。 */
internal class VictoryCommands(private val game: GameInfo, private val session: String = "", private val revision: Int = -1) {
    private val player = game.currentPlayerCiv
    private fun ensure(ok: Boolean, code: String, message: String) { if (!ok) throw GatewayError(code, message) }
    private fun scope() {
        GameSession.validateGame(game)
        ensure(player.isMajorCiv() && BaseRuleset.entries.any { it.fullName == game.gameParameters.baseRuleset },
            "UNSUPPORTED", "胜利结算仅支持基础规则的单人主要文明")
        ensure(game.victoryData == null || game.civilizations.any { it.civID == game.victoryData!!.winningCiv },
            "UNSUPPORTED", "胜利记录中的文明无法解析，请保存后用原客户端处理")
    }
    fun requiresAcknowledgement() = !game.oneMoreTurnMode && (game.victoryData != null || player.isDefeated())
    private fun outcome() = when {
        game.victoryData != null -> if (game.victoryData!!.winningCiv == player.civID) "won" else "lost"
        player.isDefeated() -> "defeated"
        else -> "ongoing"
    }
    private fun recorded(): JsonObject? = game.victoryData?.let {
        dto("civId" to it.winningCiv, "name" to game.civilizations.firstOrNull { civ -> civ.civID == it.winningCiv }?.civName,
            "type" to it.victoryType, "turn" to it.victoryTurn)
    }
    fun snapshotSummary(): JsonObject = dto("outcome" to outcome(), "playerDefeated" to player.isDefeated(),
        "oneMoreTurnMode" to game.oneMoreTurnMode, "requiresAcknowledgement" to requiresAcknowledgement(),
        "recorded" to recorded())

    private fun continuationProblem(): GatewayError? = try {
        scope()
        ensure(requiresAcknowledgement(), "NO_VICTORY_DECISION", "当前没有待确认的胜负结算，或已经进入继续游戏模式")
        ensure(!player.shouldShowDiplomaticVotingResults(), "PENDING_DECISION", "请先查看并确认外交投票结果")
        null
    } catch (error: GatewayError) { error }

    fun pending(): JsonObject? {
        if (!requiresAcknowledgement()) return null
        val problem = continuationProblem()
        return dto("kind" to "victory", "target" to outcome(), "supported" to (problem?.code != "UNSUPPORTED"),
            "enabled" to (problem == null), "message" to (problem?.message ?: "请到事项页查看胜负结算，选择是否继续游戏"))
    }
    private fun token(): String {
        val contents = dto("session" to session, "revision" to revision, "gameId" to game.gameId, "player" to player.civID,
            "recorded" to recorded(), "playerDefeated" to player.isDefeated(), "oneMoreTurnMode" to game.oneMoreTurnMode)
        return MessageDigest.getInstance("SHA-256").digest(contents.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    private fun checkKeys(request: JsonObject, keys: Set<String>) {
        ensure((request.keys - envelope - keys).isEmpty(), "INVALID_ARGUMENT", "含未支持的胜利参数")
    }
    fun options(request: JsonObject): JsonObject {
        checkKeys(request, emptySet())
        scope()
        val problem = continuationProblem()
        val victory = game.victoryData?.let { game.ruleset.victories[it.victoryType] } ?: Victory()
        val description = when (outcome()) {
            "won" -> victory.victoryString
            "lost", "defeated" -> victory.defeatString
            else -> ""
        }
        val progress = player.victoryManager.getVictoriesShownInVictoryScreen().map { item ->
            var firstIncomplete = true
            dto("name" to item.name, "header" to item.victoryScreenHeader.tr(hideIcons = true),
                "milestones" to item.milestoneObjects.map { milestone ->
                    ensure(milestone.type != null, "UNSUPPORTED", "存在无法解释的胜利里程碑")
                    val completed = milestone.hasBeenCompletedBy(player)
                    val status = when {
                        completed -> Victory.CompletionStatus.Completed
                        firstIncomplete -> { firstIncomplete = false; Victory.CompletionStatus.Partially }
                        else -> Victory.CompletionStatus.Incomplete
                    }
                    dto("status" to status.name, "text" to milestone.getVictoryScreenButtonHeaderText(completed, player).tr(hideIcons = true))
                })
        }
        return JsonObject(snapshotSummary() + dto("description" to description.tr(hideIcons = true),
            "score" to player.calculateTotalScore(), "difficulty" to game.difficulty, "speed" to game.gameParameters.speed,
            "maxTurns" to if (player.victoryManager.getAvailableVictories().any { it.enablesMaxTurns() }) game.gameParameters.maxTurns else null,
            "progress" to progress, "continue" to dto("enabled" to (problem == null), "code" to (problem?.code ?: ""),
                "reason" to (problem?.message ?: ""), "token" to if (problem == null) token() else null)))
    }
    fun prepare(request: JsonObject): () -> Unit {
        checkKeys(request, setOf("resultToken"))
        val supplied = (request["resultToken"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
            ?: throw GatewayError("INVALID_ARGUMENT", "缺少非空字符串参数：resultToken")
        continuationProblem()?.let { throw it }
        ensure(supplied == token(), "VICTORY_RESULT", "胜负结算已变化，请刷新后重新确认")
        return { game.oneMoreTurnMode = true }
    }
    companion object {
        private val envelope = setOf("protocol", "session", "revision", "requestId", "action")
        /** 对应 WorldScreen.update 的短路顺序；只能在有回滚保护的效果事务内调用。 */
        fun updateAfterAction(game: GameInfo) {
            if (!game.oneMoreTurnMode && !game.currentPlayerCiv.isDefeated() && !game.currentPlayerCiv.shouldShowDiplomaticVotingResults())
                game.checkForVictory()
        }
        fun contribute(registry: CommandRegistry, session: GameSession) {
            registry.query("victoryOptions") { VictoryCommands(session.requireGame(), it.sessionId, it.revision).options(it.request) }
            registry.effect("victoryContinue", allowedDuringVictory = true) {
                VictoryCommands(session.requireGame(), it.sessionId, it.revision).prepare(it.request)
            }
        }
    }
}
