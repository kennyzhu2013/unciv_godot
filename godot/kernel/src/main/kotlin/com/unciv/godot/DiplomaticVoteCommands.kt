package com.unciv.godot

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.CivFlags
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.translations.tr
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** 外交选举适配：资格、候选和计票委托原生；查询绝不推进回合或检查并写入胜利。 */
internal class DiplomaticVoteCommands(private val game: GameInfo, private val session: String = "", private val revision: Int = -1) {
    private val player = game.currentPlayerCiv
    private val votes get() = game.diplomaticVictoryVotesCast
    private fun hasVoted() = votes.containsKey(player.civID)
    private fun resultFlag() = player.flagsCountdown[CivFlags.ShowDiplomaticVotingResults.name]
    private fun resultPending() = player.shouldShowDiplomaticVotingResults()
    private fun resultsReadable() = resultPending() || resultFlag() == -1
    private fun candidates() = player.diplomacyFunctions.getKnownCivsSorted(false).sortedBy { it.civID }.toList()
    private fun ensure(ok: Boolean, code: String, message: String) { if (!ok) throw GatewayError(code, message) }
    private fun checked(block: () -> Unit): GatewayError? = try { block(); null } catch (error: GatewayError) { error }
    private fun scope() {
        GameSession.validateGame(game)
        ensure(BaseRuleset.entries.any { it.fullName == game.gameParameters.baseRuleset }, "UNSUPPORTED", "外交投票仅验证 Vanilla 与 G&K 基础规则")
        ensure(player.isMajorCiv() && !player.isDefeated(), "UNSUPPORTED", "外交投票仅支持存活的主要文明玩家")
    }
    private fun castCheck() {
        scope()
        ensure(!resultPending(), "DIPLOMATIC_VOTE_RESULT_PENDING", "请先查看并确认本轮外交投票结果")
        ensure(!hasVoted(), "DIPLOMATIC_VOTE_ALREADY_CAST", "本轮已经投票或弃权，不能改票")
        ensure(player.mayVoteForDiplomaticVictory(), "NO_DIPLOMATIC_VOTE", "当前没有可提交的外交投票")
        CombatCommands(game).ensureNoBlockingBattleDecision()
    }
    /** 只检查引用，不构造票数表；异常数据不能使轻量快照不可用。 */
    private fun resultReferences() {
        val ids = game.civilizations.map { it.civID }.toSet()
        ensure(votes.all { (voter, target) -> voter in ids && (target == null || target in ids) },
            "UNSUPPORTED", "外交投票结果含无法解析的文明引用，请保存后用原客户端处理")
    }
    private fun phase(): String = when {
        resultPending() -> "results"
        player.mayVoteForDiplomaticVictory() -> "vote"
        hasVoted() && (player.getTurnsTillNextDiplomaticVote() == 0 || (resultFlag() ?: -1) >= 0) -> "waitingResults"
        (player.getTurnsTillNextDiplomaticVote() ?: 0) > 0 -> "countdown"
        else -> "idle"
    }
    private fun myVote(): JsonObject = dto("hasVoted" to hasVoted(),
        "choice" to if (!hasVoted()) null else if (votes[player.civID] == null) "abstain" else "civilization",
        "civId" to if (hasVoted()) votes[player.civID] else null)
    private fun victory(): JsonObject? = game.victoryData?.let { recorded ->
        dto("civId" to recorded.winningCiv, "name" to game.civilizations.firstOrNull { it.civID == recorded.winningCiv }?.civName,
            "type" to recorded.victoryType, "turn" to recorded.victoryTurn)
    }
    private fun ownFlags() = dto(*voteFlags.map { it.name to player.flagsCountdown[it.name] }.toTypedArray())
    private fun token(mode: String): String {
        val contents = dto("mode" to mode, "session" to session, "revision" to revision, "gameId" to game.gameId,
            "turn" to game.turns, "player" to player.civID, "flags" to ownFlags(), "myVote" to myVote(),
            "state" to if (mode == "cast") {
                // 未公开的其他选票绝不能进入可枚举的哈希输入。
                dto("mayVote" to player.mayVoteForDiplomaticVictory(), "candidates" to candidates().map { it.civID })
            } else {
                val (un, owner) = player.victoryManager.getUNBuildingAndOwnerNames()
                dto("votes" to votes.toSortedMap().map { (voter, target) -> dto("voter" to voter, "target" to target) },
                    "livingCivs" to game.civilizations.filter { !it.isBarbarian && !it.isSpectator() && !it.isDefeated() }
                        .map { it.civID }.sorted(), "unBuilding" to un, "unOwnerCivId" to owner, "victory" to victory())
            })
        return MessageDigest.getInstance("SHA-256").digest(contents.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    private fun mode(): String? = when {
        resultPending() -> "acknowledge"
        player.mayVoteForDiplomaticVictory() -> "cast"
        else -> null
    }
    private fun decisionProblem(mode: String): GatewayError? = checked {
        if (mode == "cast") castCheck() else { scope(); resultReferences() }
    }
    private fun decision(withToken: Boolean): JsonObject? {
        val mode = mode() ?: return null
        val problem = decisionProblem(mode)
        val supported = problem == null || problem.code == "PENDING_DECISION"
        return dto("mode" to mode, "supported" to supported, "enabled" to (problem == null),
            "code" to (problem?.code ?: ""), "reason" to (problem?.message ?: ""),
            "token" to if (withToken && supported) token(mode) else null)
    }
    private fun results(): JsonObject? {
        if (!resultsReadable()) return null
        resultReferences()
        val (un, owner) = player.victoryManager.getUNBuildingAndOwnerNames()
        val (totals, winnerText) = player.victoryManager.getDiplomaticVictoryVoteBreakdown()
        val rows = game.getCivsSorted().sortedWith(compareBy<Civilization> { it != player }
            .thenBy { !it.isMajorCiv() }.thenBy { it.civID }).map { civ ->
            val target = votes[civ.civID]?.let { id -> game.civilizations.first { it.civID == id } }?.takeUnless { it.isDefeated() }
            dto("civId" to civ.civID, "name" to civ.civName, "type" to if (civ.isMajorCiv()) "major" else "cityState",
                "voteWeight" to if (civ.civID == owner) 2 else 1,
                "choice" to if (target == null) "abstain" else "civilization",
                "votedForCivId" to target?.civID, "votedForName" to target?.civName,
                "votesReceived" to if (civ.isMajorCiv()) totals[civ.civID] else null)
        }.toList()
        return dto("acknowledged" to (resultFlag() == -1), "totals" to dto(*totals.toSortedMap().map { it.key to it.value }.toTypedArray()),
            "rows" to rows, "winnerText" to winnerText.tr(hideIcons = true), "unBuilding" to un, "unOwnerCivId" to owner)
    }
    private fun checkKeys(request: JsonObject, keys: Set<String>) {
        ensure((request.keys - envelope - keys).isEmpty(), "INVALID_ARGUMENT", "含未支持的外交投票参数")
    }
    private fun text(request: JsonObject, key: String): String = (request[key] as? JsonPrimitive)
        ?.takeIf { it.isString && it.content.isNotBlank() }?.content
        ?: throw GatewayError("INVALID_ARGUMENT", "缺少非空字符串参数或类型错误：$key")

    fun options(request: JsonObject): JsonObject {
        checkKeys(request, emptySet())
        scope()
        val referenceProblem = if (resultsReadable()) checked { resultReferences() } else null
        val currentDecision = if (referenceProblem != null) dto("mode" to "acknowledge", "supported" to false,
            "enabled" to false, "code" to referenceProblem.code, "reason" to referenceProblem.message, "token" to null)
            else decision(true)
        return dto("phase" to phase(), "turnsUntilVote" to player.getTurnsTillNextDiplomaticVote(), "myVote" to myVote(),
            "decision" to currentDecision, "canAbstain" to (mode() == "cast" && decisionProblem("cast") == null),
            "candidates" to if (mode() == "cast") candidates().map { dto("civId" to it.civID, "name" to it.civName) } else emptyList<JsonObject>(),
            "results" to if (referenceProblem == null) results() else null, "victory" to victory())
    }
    fun snapshotSummary(): JsonObject = dto("phase" to phase(), "turnsUntilVote" to player.getTurnsTillNextDiplomaticVote(),
        "myVote" to myVote(), "resultsReadable" to resultsReadable(), "victory" to victory())
    fun pending(): JsonObject? {
        val mode = mode() ?: return null
        val status = decision(false)!!
        val reason = status.text("reason")
        return dto("kind" to if (mode == "acknowledge") "voteResult" else "vote", "target" to mode,
            "supported" to status.boolean("supported"), "enabled" to status.boolean("enabled"), "reason" to reason,
            "message" to reason.ifEmpty { if (mode == "acknowledge") "请到事项页查看并确认外交投票结果" else "请到事项页投票或明确弃权" })
    }
    fun prepare(request: JsonObject): () -> Unit = when (request.text("action")) {
        "diplomaticVoteCast" -> {
            val choice = text(request, "choice")
            ensure(choice == "civilization" || choice == "abstain", "INVALID_ARGUMENT", "choice 必须为 civilization 或 abstain")
            checkKeys(request, if (choice == "civilization") setOf("choice", "decisionToken", "civId") else setOf("choice", "decisionToken"))
            val target = if (choice == "civilization") text(request, "civId") else null
            val suppliedToken = text(request, "decisionToken")
            castCheck()
            ensure(suppliedToken == token("cast"), "DIPLOMATIC_VOTE_DECISION", "投票事项已变化，请刷新后重新确认")
            ensure(target == null || candidates().any { it.civID == target }, "DIPLOMATIC_VOTE_CHOICE", "此目标不是当前可选文明")
            ({ player.diplomaticVoteForCiv(target) })
        }
        "diplomaticVoteAcknowledge" -> {
            checkKeys(request, setOf("resultToken"))
            val suppliedToken = text(request, "resultToken")
            scope()
            if (resultsReadable()) results()
            ensure(resultPending(), "NO_DIPLOMATIC_VOTE_RESULT", "当前没有待确认的外交投票结果")
            ensure(suppliedToken == token("acknowledge"), "DIPLOMATIC_VOTE_RESULT", "投票结果已变化，请刷新后重新确认")
            ({ player.addFlag(CivFlags.ShowDiplomaticVotingResults.name, -1) })
        }
        else -> throw GatewayError("UNKNOWN_COMMAND", "未实现的外交投票命令")
    }
    companion object {
        private val envelope = setOf("protocol", "session", "revision", "requestId", "action")
        private val voteFlags = listOf(CivFlags.TurnsTillNextDiplomaticVote, CivFlags.ShowDiplomaticVotingResults, CivFlags.ShouldResetDiplomaticVotes)
    }
}
