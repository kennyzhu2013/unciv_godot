package com.unciv.godot

import com.unciv.Constants
import com.unciv.godot.BattleFixtures.native
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.CityStatePersonality
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import java.io.File

/** 城邦场景生成：期望端只调原生 CityStateFunctions／DiplomacyManager 入口，镜像 CityStateDiplomacyTable 回调，不经网关。 */
internal object CityStateFixtures {
    val root get() = BattleFixtures.root

    /** 标准已接触城邦 Geneva；显式设为 Neutral 性格去除贡品意愿的随机 Hostile 项，清除首次接触弹窗避免遮蔽后续事件。 */
    fun minor(game: GameInfo, influence: Float = 45f): Civilization = native(game) {
        val player = game.currentPlayerCiv
        val cs = DiplomacyFixtures.addCiv(game, "Geneva", -2, 5)
        cs.cityStatePersonality = CityStatePersonality.Neutral
        cs.getDiplomacyManager(player)!!.setInfluence(influence)
        player.popupAlerts.removeAll { it.type == AlertType.FirstContact && it.value == cs.civID }
        cs
    }

    /** 已处于保护状态且承诺冷却已过，可立即撤销。 */
    fun protectedCs(game: GameInfo): Civilization = native(game) {
        val player = game.currentPlayerCiv
        minor(game).also {
            it.cityStateFunctions.addProtectorCiv(player)
            it.getDiplomacyManager(player)!!.removeFlag(DiplomacyFlags.RecentlyPledgedProtection)
        }
    }

    /** 在城邦首都附近部署玩家军事力量抬高贡品意愿；check 确保目标类型意愿不低于 0，否则场景不成立。 */
    fun bullyable(game: GameInfo, worker: Boolean): Civilization = native(game) {
        val player = game.currentPlayerCiv
        val cs = minor(game)
        cs.getCapital()!!.population.setPopulation(5)
        val center = cs.getCapital()!!.getCenterTile().position
        repeat(6) { game.tileMap.placeUnitNearTile(center, "Swordsman", player) }
        check(cs.cityStateFunctions.getTributeWillingness(player, worker) >= 0) {
            "贡品意愿不足（worker=$worker）：${cs.cityStateFunctions.getTributeModifiers(player, worker, requireWholeList = true)}"
        }
        cs
    }

    /** 与城邦交战中且宣战议和冷却已过，可立即议和。 */
    fun atWar(game: GameInfo): Civilization = native(game) {
        val player = game.currentPlayerCiv
        minor(game).also {
            player.getDiplomacyManager(it)!!.declareWar()
            it.getDiplomacyManager(player)!!.removeFlag(DiplomacyFlags.DeclaredWar)
            player.popupAlerts.clear()
            it.popupAlerts.clear()
            player.notifications.clear()
            it.notifications.clear()
        }
    }

    /** 玩家改为奥地利（civName 持久，重载后 setNationTransient 绑定），城邦为盟友且联姻冷却已过、金币充足。 */
    fun austria(game: GameInfo): Civilization = native(game) {
        val player = game.currentPlayerCiv
        player.setNameForUnitTests("Austria")
        player.setNationTransient()
        val cs = minor(game, 100f)
        player.getDiplomacyManager(cs)!!.removeFlag(DiplomacyFlags.MarriageCooldown)
        player.addGold(2000)
        check(cs.cityStateFunctions.canBeMarriedBy(player)) { "联姻资格不成立" }
        cs
    }

    fun file(name: String, configure: (GameInfo) -> Unit): File {
        val game = DiplomacyFixtures.game()
        native(game) { configure(game) }
        return File(root, "godot/.local/tests/citystate-$name.json").apply {
            parentFile.mkdirs()
            writeText(UncivFiles.gameInfoToString(game, true))
        }
    }

    // 期望端原生动作，逐一对应网关七命令的执行体。
    fun gift(game: GameInfo, amount: Int) = native(game) {
        cityState(game).cityStateFunctions.receiveGoldGift(game.currentPlayerCiv, amount)
    }
    fun pledge(game: GameInfo) = native(game) { cityState(game).cityStateFunctions.addProtectorCiv(game.currentPlayerCiv) }
    fun revoke(game: GameInfo) = native(game) { cityState(game).cityStateFunctions.removeProtectorCiv(game.currentPlayerCiv) }
    fun tribute(game: GameInfo, worker: Boolean) = native(game) {
        val cs = cityState(game)
        if (worker) cs.cityStateFunctions.tributeWorker(game.currentPlayerCiv) else cs.cityStateFunctions.tributeGold(game.currentPlayerCiv)
    }
    fun declareWar(game: GameInfo) = native(game) {
        game.currentPlayerCiv.getDiplomacyManager(cityState(game))!!.declareWar()
    }
    fun negotiatePeace(game: GameInfo) = native(game) {
        val player = game.currentPlayerCiv
        TradeLogic(player, cityState(game)).apply {
            currentTrade.ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = game.speed))
            currentTrade.theirOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = game.speed))
        }.acceptTrade()
    }
    /** 与原客户端一致：先取城市列表再执行联姻，随后逐城弹出吞并／傀儡处置。 */
    fun marry(game: GameInfo) = native(game) {
        val player = game.currentPlayerCiv
        val cs = cityState(game)
        val cities = cs.cities.toList()
        cs.cityStateFunctions.diplomaticMarriage(player)
        cities.forEach { player.popupAlerts.add(PopupAlert(AlertType.DiplomaticMarriage, it.id)) }
    }

    fun cityState(game: GameInfo): Civilization = game.civilizations.first { it.isCityState }

    /** 场景表：名称 → 布置；动作在 emit 内按名称分派，保存文件始终为动作前的初始态。 */
    private val scenarios = linkedMapOf<String, (GameInfo) -> Civilization>(
        "gift250" to { minor(it) }, "gift500" to { minor(it) }, "gift1000" to { minor(it) },
        "pledge" to { minor(it) }, "revoke" to { protectedCs(it) },
        "tribute-gold" to { bullyable(it, false) }, "tribute-worker" to { bullyable(it, true) },
        "war" to { minor(it) }, "peace" to { atWar(it) }, "marriage" to { austria(it) })

    private fun act(game: GameInfo, name: String) {
        when (name) {
            "gift250" -> gift(game, 250)
            "gift500" -> gift(game, 500)
            "gift1000" -> gift(game, 1000)
            "pledge" -> pledge(game)
            "revoke" -> revoke(game)
            "tribute-gold" -> tribute(game, false)
            "tribute-worker" -> tribute(game, true)
            "war" -> declareWar(game)
            "peace" -> negotiatePeace(game)
            "marriage" -> marry(game)
            else -> error(name)
        }
    }

    fun emit() {
        val states = linkedMapOf<String, Any?>()
        fun record(name: String, game: GameInfo) {
            check(game.unitNamesTaken.isEmpty())
            states[name] = GameplayAssertions.gameplay(game)
        }
        for (name in scenarios.keys) {
            val saved = file(name) { scenarios[name]!!(it) }
            val game = UncivFiles.gameInfoFromString(saved.readText())
            record("$name-initial", game)
            native(game) { act(game, name) }
            record("$name-done", game)
            if (name == "marriage") {
                // 联姻后衔接已接入的资产处置：吞并接管城市。
                native(game) { AssetDecisionFixtures.decide(game, "annex") }
                record("marriage-annex", game)
            }
        }
        File(root, "godot/.local/tests/citystate-expected.json").writeText(dto(
            "scenarios" to dto(*scenarios.keys.toList().map { it to true }.toTypedArray()),
            "states" to dto(*states.toList().toTypedArray()),
            "setFields" to GameplayAssertions.setFields.toList(),
            "mapOfSetFields" to GameplayAssertions.mapOfSetFields.toList()).toString())
    }
}
