package com.unciv.godot

import com.unciv.logic.GameInfo
import com.unciv.view.GameView
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** 免费伟人选择适配：只读资格与票据同源；生成、额度及池变更只委托原生管理器。 */
internal class GreatPersonCommands(private val game: GameInfo, private val session: String = "", private val revision: Int = -1) {
    private val player = game.currentPlayerCiv
    private val manager = player.greatPeople
    private fun abnormal() = manager.freeGreatPeople < 0 || manager.mayaLimitedFreeGP < 0 || manager.mayaLimitedFreeGP > manager.freeGreatPeople
    private fun mode() = when { abnormal() -> "unsupported"; manager.mayaLimitedFreeGP > 0 -> "maya"; else -> "ordinary" }
    private fun candidates() = manager.getFreeGreatPersonOptions().map { it.name }.sorted()
    private fun problem(): GatewayError? = when {
        abnormal() -> GatewayError("UNSUPPORTED", "免费伟人额度异常，请保存后用原客户端处理")
        manager.freeGreatPeople == 0 -> GatewayError("NO_FREE_GREAT_PERSON", "当前没有免费伟人额度")
        player.cities.isEmpty() -> GatewayError("NO_CITY", "没有城市，当前无法领取免费伟人")
        candidates().isEmpty() -> GatewayError("NO_GREAT_PERSON_OPTIONS", "当前没有可选伟人；额度保留，请保存后用原客户端处理")
        else -> null
    }
    private fun token(): String {
        val contents = dto("session" to session, "revision" to revision, "gameId" to game.gameId,
            "player" to player.civID, "mode" to mode(), "freeGreatPeople" to manager.freeGreatPeople,
            "mayaLimitedFreeGP" to manager.mayaLimitedFreeGP, "pool" to manager.longCountGPPool.sorted(),
            "candidates" to candidates(), "religionEnabled" to game.isReligionEnabled(),
            "cities" to player.cities.sortedBy { it.id }.map { dto("id" to it.id, "position" to it.location.dto()) })
        return MessageDigest.getInstance("SHA-256").digest(contents.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    private fun checkKeys(request: JsonObject, keys: Set<String>) {
        if ((request.keys - envelope - keys).isNotEmpty()) throw GatewayError("INVALID_ARGUMENT", "含未支持的免费伟人参数")
    }
    private fun text(request: JsonObject, key: String): String = (request[key] as? JsonPrimitive)
        ?.takeIf { it.isString && it.content.isNotEmpty() }?.content
        ?: throw GatewayError("INVALID_ARGUMENT", "缺少非空字符串参数或类型错误：$key")

    fun options(request: JsonObject): JsonObject {
        checkKeys(request, emptySet())
        GameSession.validateGame(game)
        val problem = problem()
        val currentCandidates = candidates().toSet()
        val pending = abnormal() || manager.freeGreatPeople > 0
        return dto("freeGreatPeople" to manager.freeGreatPeople, "mayaLimitedFreeGP" to manager.mayaLimitedFreeGP,
            "ordinaryFreeGreatPeople" to if (abnormal()) null else manager.freeGreatPeople - manager.mayaLimitedFreeGP,
            "decision" to if (!pending) null else dto("mode" to mode(), "supported" to !abnormal(),
                "enabled" to (problem == null), "reason" to (problem?.message ?: ""), "code" to (problem?.code ?: ""),
                "token" to token()),
            "candidates" to manager.getGreatPeople().sortedBy { it.name }.map { unit ->
                val reason = problem?.message ?: if (unit.name !in currentCandidates) "本轮玛雅长纪历不可选择此类型" else ""
                dto("unitName" to unit.name, "unitType" to unit.unitType, "movement" to unit.movement,
                    "strength" to unit.strength, "rangedStrength" to unit.rangedStrength,
                    "promotions" to unit.promotions.sorted(),
                    "effects" to if (unit.replacementTextForUniques.isNotEmpty()) listOf(unit.replacementTextForUniques)
                        else unit.uniqueObjects.filterNot { it.isHiddenToUsers() }.map { it.getDisplayText() }.sorted(),
                    "enabled" to reason.isEmpty(), "reason" to reason)
            },
            "placementNote" to "一次领取一位，优先消耗玛雅受限额度。生成位置由原生选择；海军伟人需要合适的沿海城市。无法落位时额度保留。")
    }

    /** 快照只构造轻量摘要，不生成票据或完整候选 DTO。 */
    fun pending(): JsonObject? {
        if (!abnormal() && manager.freeGreatPeople == 0) return null
        val problem = problem()
        return dto("kind" to "greatPerson", "target" to mode(), "supported" to !abnormal(),
            "enabled" to (problem == null), "message" to (problem?.message ?: "请到事项页选择免费伟人（剩余 ${manager.freeGreatPeople} 位）"),
            "freeGreatPeople" to manager.freeGreatPeople, "mayaLimitedFreeGP" to manager.mayaLimitedFreeGP)
    }

    fun prepare(request: JsonObject): () -> JsonObject {
        checkKeys(request, setOf("unitName", "decisionToken"))
        val name = text(request, "unitName")
        val suppliedToken = text(request, "decisionToken")
        GameSession.validateGame(game)
        problem()?.let { throw it }
        if (suppliedToken != token()) throw GatewayError("GREAT_PERSON_DECISION", "免费伟人选择已变化，请刷新后重试")
        if (name !in candidates()) throw GatewayError("GREAT_PERSON_CHOICE", "此伟人不是当前可选项")
        return {
            // null 是一次已完成的原生尝试，可能已分配 ID；不能回滚或再调用一次。
            val unit = manager.chooseFreeGreatPerson(name)
            val visibleUnit = unit?.takeIf { placed ->
                player.units.getCivUnits().any { it === placed } && placed.civ == player &&
                    GameView(game, player).getTile(placed.currentTile).isVisible()
            }
            dto("outcome" to if (unit == null) "notPlaced" else "granted", "unitName" to name,
                "unitId" to unit?.id, "position" to visibleUnit?.currentTile?.position?.dto(),
                "freeGreatPeople" to manager.freeGreatPeople, "mayaLimitedFreeGP" to manager.mayaLimitedFreeGP,
                "message" to if (unit == null) "未生成，额度保留。请检查可用位置及沿海城市，返回地图处理后再试。" else "已领取 $name")
        }
    }

    companion object {
        private val envelope = setOf("protocol", "session", "revision", "requestId", "action")
    }
}
