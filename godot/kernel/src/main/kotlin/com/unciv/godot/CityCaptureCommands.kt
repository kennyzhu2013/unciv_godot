package com.unciv.godot

import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.models.ruleset.unique.UniqueType
import kotlinx.serialization.json.JsonObject

/** 授权来自当前提示，而非城市归属；占城弹窗关闭前城市可能仍在原文明名下。 */
internal class CityCaptureCommands(private val game: GameInfo) {
    private val player = game.currentPlayerCiv
    private fun city(alert: PopupAlert): City? = game.getCities().firstOrNull {
        it.id == alert.value && it.hasJustBeenConquered
    }
    private fun mayAnnex() = !player.hasUnique(UniqueType.MayNotAnnexCities)
    private fun mayLiberate(city: City) = city.foundingCivObject != null && city.civ != city.foundingCivObject && player != city.foundingCivObject

    private fun choices(city: City): List<JsonObject> = buildList {
        fun option(id: String, enabled: Boolean, reason: String) = dto("id" to id, "enabled" to enabled, "reason" to if (enabled) "" else reason)
        add(option("annex", mayAnnex(), "此文明不能吞并城市"))
        add(option("puppet", true, ""))
        add(option("raze", city.canBeDestroyed(justCaptured = true), "首都、圣城或规则限制，不能焚毁此城"))
        if (mayLiberate(city)) add(option("liberate", true, ""))
    }

    fun pending(alert: PopupAlert): JsonObject {
        val city = city(alert)
        return dto("kind" to "cityCapture", "target" to alert.value, "supported" to (city != null),
            "message" to if (city != null) "请选择如何处置 ${city.name}" else "占城提示与城市状态不一致，请保存后用原客户端处理",
            "choices" to if (city != null) choices(city) else emptyList<JsonObject>())
    }

    fun prepare(id: String, choice: String): () -> Unit {
        val alert = player.popupAlerts.firstOrNull()
        if (alert == null || alert.type != AlertType.CityConquered || alert.value != id)
            throw GatewayError("CITY_DECISION", "没有对应的当前占城决策")
        val city = city(alert) ?: throw GatewayError("CITY_DECISION", "城市不处于待处置状态")
        if (choices(city).none { it.text("id") == choice && it["enabled"].toString() == "true" })
            throw GatewayError("CITY_DECISION", "此处置选项不可用")
        val annex = mayAnnex()
        return {
            // 与 AlertPopup 的操作顺序完全一致，成功后只移除此提示。
            when (choice) {
                "puppet" -> city.puppetCity(player)
                "annex" -> { city.puppetCity(player); city.annexCity() }
                "raze" -> { city.puppetCity(player); if (annex) city.annexCity(); city.isBeingRazed = true }
                "liberate" -> city.liberateCity(player)
            }
            player.popupAlerts.remove(alert)
        }
    }
}
