package com.unciv.godot

import com.unciv.logic.GameInfo
import com.unciv.logic.battle.BattleUnitCapture
import com.unciv.logic.city.City
import com.unciv.logic.civilization.*
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.unique.UniqueType
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** 仅处理队首已有的资产处置事件，不提供转移任意单位、城市或主动联姻的入口。 */
internal class AssetDecisionCommands(private val game: GameInfo, private val session: String = "", private val revision: Int = -1) {
    private val player = game.currentPlayerCiv
    private data class Subject(val unit: MapUnit? = null, val city: City? = null, val original: Civilization? = null,
        val expired: Boolean = false, val problem: String = "")
    private data class Choice(val id: String, val label: String, val description: String, val reason: String = "") {
        fun dto() = dto("id" to id, "label" to label, "description" to description,
            "enabled" to reason.isEmpty(), "reason" to reason)
    }

    private fun subject(alert: PopupAlert): Subject {
        if (alert.type == AlertType.RecapturedCivilian) {
            val position = try { HexCoord.fromString(alert.value) } catch (_: NumberFormatException) {
                return Subject(problem = "平民处置引用格式无效，请保存后用原客户端核对")
            }
            val tile = game.tileMap.getOrNull(position.x, position.y)
            if (tile == null || tile !in player.viewableTiles)
                return Subject(problem = "处置位置不可用或当前不可见")
            val unit = tile.civilianUnit ?: return Subject(expired = true)
            if (unit.civ != player || !unit.isCivilian()) return Subject(problem = "找不到属于当前玩家的待处置平民")
            val original = game.civilizations.firstOrNull { it.civID == unit.originalOwner }
            if (original == null || original == player || original.isBarbarian || original.isSpectator())
                return Subject(problem = "平民原主引用无效")
            return Subject(unit = unit, original = original, expired = original.isDefeated())
        }
        val city = game.getCities().firstOrNull { it.id == alert.value && it.civ == player }
            ?: return Subject(problem = "找不到属于当前玩家的待处置城市")
        if (city.hasJustBeenConquered) return Subject(problem = "此城市仍处于占领流程，事件状态不一致")
        if (alert.type == AlertType.CityTraded && (city.foundingCivObject == null || city.foundingCivObject == player))
            return Subject(problem = "交易城市的建立者引用无效")
        return Subject(city = city)
    }

    private fun choices(alert: PopupAlert, subject: Subject): List<Choice> {
        if (subject.problem.isNotEmpty()) return emptyList()
        if (subject.expired) return listOf(Choice("dismiss", "清理过期事件", "单位已消失或原主已灭亡；仅关闭此事件，不转移资产。"))
        return when (alert.type) {
            AlertType.RecapturedCivilian -> listOf(
                Choice("return", "归还原主", "移除当前单位，由原生规则尝试在原主最近城市附近重新生成；原主为城邦时影响力 +45，为主要文明时归还评价设为 +20。"),
                Choice("keep", "自行保留", "按原生俘获规则保留单位；开拓者转换为工人，转换与落位由原生规则处理。"))
            AlertType.CityTraded -> buildList {
                if (!player.isAtWarWith(subject.city!!.foundingCivObject!!))
                    add(Choice("liberate", "解放城市", "将城市归还建立者，外交、复国及相关副作用按原生解放规则处理。"))
                add(Choice("keep", "保留城市", "保持交易后已有的城市归属和状态，仅关闭此事件。"))
            }
            AlertType.DiplomaticMarriage -> if (player.isOneCityChallenger())
                listOf(Choice("destroy", "立即销毁城市", "单城挑战：立即销毁该城市，而非逐回合焚城；此操作不可撤销。"))
            else listOf(
                Choice("annex", "吞并城市", "将已接收城市转为可直接管理的城市；不再次支付联姻费用或重复转移资产。",
                    if (player.hasUnique(UniqueType.MayNotAnnexCities)) "此文明不能吞并城市" else ""),
                Choice("puppet", "设为傀儡", "将已接收城市设为傀儡并刷新城市统计，不重复执行征服结算。"))
            else -> emptyList()
        }
    }

    private fun message(alert: PopupAlert, subject: Subject): String = when {
        subject.problem.isNotEmpty() -> subject.problem
        subject.expired -> "夺回平民事件已过期，请明确清理"
        alert.type == AlertType.RecapturedCivilian -> "如何处置夺回的 ${subject.unit!!.name}？"
        alert.type == AlertType.CityTraded -> "如何处置交易获得的 ${subject.city!!.name}？"
        else -> "如何处置联姻获得的 ${subject.city!!.name}？"
    }

    /** 除版本外还绑定实际对象身份，防止同一坐标换成另一单位后复用旧确认。 */
    private fun token(alert: PopupAlert, subject: Subject, choices: List<Choice>): String {
        val unit = subject.unit
        val city = subject.city
        val identity = dto("session" to session, "revision" to revision, "gameId" to game.gameId,
            "player" to player.civID, "type" to alert.type.name, "value" to alert.value,
            "unitId" to unit?.id, "unitName" to unit?.baseUnit?.name, "owner" to unit?.civ?.civID,
            "originalOwner" to subject.original?.civID, "cityId" to city?.id,
            "cityOwner" to city?.civ?.civID, "founder" to city?.foundingCivObject?.civID,
            "puppet" to city?.isPuppet, "expired" to subject.expired, "problem" to subject.problem,
            "choices" to choices.map { it.dto() })
        return MessageDigest.getInstance("SHA-256").digest(identity.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun decision(alert: PopupAlert, withToken: Boolean): JsonObject {
        val subject = subject(alert)
        val choices = choices(alert, subject)
        val original = subject.original ?: subject.city?.foundingCivObject
        val fields = dto("kind" to "assetDecision", "type" to alert.type.name,
            "queueHead" to (player.popupAlerts.firstOrNull() === alert),
            "target" to (subject.city?.id ?: subject.unit?.id?.toString() ?: ""),
            "supported" to choices.any { it.reason.isEmpty() }, "message" to message(alert, subject),
            "choices" to choices.map { it.dto() },
            "originalOwner" to original?.takeIf { player.knows(it) }?.let { dto("civId" to it.civID, "name" to it.civName) })
        return if (withToken) JsonObject(fields + ("token" to JsonPrimitive(token(alert, subject, choices)))) else fields
    }

    fun pending(alert: PopupAlert): JsonObject = decision(alert, false)

    fun options(request: JsonObject): JsonObject {
        checkKeys(request, emptySet())
        GameSession.validateGame(game)
        val alert = player.popupAlerts.firstOrNull()?.takeIf { it.type in alertTypes }
        return dto("decision" to alert?.let { decision(it, true) })
    }

    fun prepare(request: JsonObject): () -> Unit {
        checkKeys(request, setOf("decisionToken", "choice"))
        val ticket = text(request, "decisionToken")
        val choice = text(request, "choice")
        GameSession.validateGame(game)
        val alert = player.popupAlerts.firstOrNull()?.takeIf { it.type in alertTypes }
            ?: throw GatewayError("ASSET_DECISION", "没有对应的队首资产处置事件")
        val subject = subject(alert)
        val choices = choices(alert, subject)
        if (ticket != token(alert, subject, choices)) throw GatewayError("ASSET_DECISION", "处置对象或事件已变化，请刷新后重新确认")
        if (subject.problem.isNotEmpty()) throw GatewayError("ASSET_TARGET", subject.problem)
        val selected = choices.firstOrNull { it.id == choice } ?: throw GatewayError("INVALID_ARGUMENT", "此事件没有该处置选项")
        if (selected.reason.isNotEmpty()) throw GatewayError("ASSET_CHOICE", selected.reason)
        return {
            when (alert.type) {
                AlertType.RecapturedCivilian -> when (choice) {
                    "return" -> returnCivilian(subject.unit!!, subject.original!!)
                    "keep" -> BattleUnitCapture.captureOrConvertToWorker(subject.unit!!, player)
                }
                AlertType.CityTraded -> if (choice == "liberate") subject.city!!.liberateCity(player)
                AlertType.DiplomaticMarriage -> when (choice) {
                    "annex" -> subject.city!!.annexCity()
                    "puppet" -> { subject.city!!.isPuppet = true; subject.city.cityStats.update() }
                    "destroy" -> subject.city!!.destroyCity(overrideSafeties = true)
                }
                else -> Unit
            }
            player.popupAlerts.remove(alert)
        }
    }

    /** 对齐 AlertPopup 的归还回调：不是 gift，也不保留原单位的损伤或晋升。 */
    private fun returnCivilian(unit: MapUnit, original: Civilization) {
        val tile = unit.currentTile
        val name = unit.baseUnit.name
        unit.destroy()
        val closest = original.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }
        if (closest != null) original.units.placeUnitNearTile(closest.location.toHexCoord(), name)
        if (original.isCityState) original.getDiplomacyManagerOrMeet(player).addInfluence(45f)
        else if (original.isMajorCiv()) original.getDiplomacyManagerOrMeet(player).setModifier(DiplomaticModifiers.ReturnedCapturedUnits, 20f)
        val actions = sequence {
            yield(LocationAction(tile.position))
            if (closest != null) yield(LocationAction(closest.location))
            yield(DiplomacyAction(player))
            yield(CivilopediaAction("Tutorial/Barbarians"))
        }
        original.addNotification("Your captured [${name}] has been returned by [${player.civName}]", actions,
            NotificationCategory.Diplomacy, NotificationIcon.Trade, name, player.civName)
    }

    private fun checkKeys(request: JsonObject, keys: Set<String>) {
        if ((request.keys - setOf("protocol", "session", "revision", "requestId", "action") - keys).isNotEmpty())
            throw GatewayError("INVALID_ARGUMENT", "含未支持的资产处置参数")
    }
    private fun text(request: JsonObject, key: String): String = (request[key] as? JsonPrimitive)
        ?.takeIf { it.isString && it.content.isNotBlank() }?.content
        ?: throw GatewayError("INVALID_ARGUMENT", "缺少非空字符串参数或类型错误：$key")

    companion object {
        val alertTypes = setOf(AlertType.RecapturedCivilian, AlertType.CityTraded, AlertType.DiplomaticMarriage)
    }
}
