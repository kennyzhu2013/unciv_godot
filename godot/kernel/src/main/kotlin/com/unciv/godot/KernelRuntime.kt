package com.unciv.godot

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessFiles
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import java.io.File

/** 单进程仅初始化一个内核；不启动 LibGDX Application、音频或 GL 线程。 */
object KernelRuntime {
    private var initialized = false

    /** 同一进程内重复调用（如多个测试类）不重建 UncivGame，避免已加载对局引用失效的运行时。 */
    @Synchronized
    fun initialize(root: File) {
        if (initialized) return
        check(File("jsons").isDirectory) { "工作目录必须为 android/assets" }
        Gdx.files = HeadlessFiles()
        UncivGame.Current = UncivGame(true).apply {
            settings = GameSettings().apply {
                showTutorials = false
                autoAssignCityProduction = false
                automatedUnitsMoveOnTurnStart = true
            }
            files = UncivFiles(Gdx.files, File(root, "godot/.local/data").absolutePath)
        }
        RulesetCache.loadRulesets(noMods = false)
        initialized = true
    }

    /** 没有用户存档时提供原内核生成的开局，随后用原存档格式落盘再读取。 */
    fun createDemo(): GameInfo {
        val parameters = GameParameters().apply {
            players = arrayListOf(Player("Rome", PlayerType.Human), Player("Greece"))
            numberOfCityStates = 0
            noBarbarians = true
        }
        val map = MapParameters().apply {
            mapSize = MapSize.Tiny
            seed = 4602L
            noRuins = true
        }
        return GameStarter.startNewGame(GameSetupInfo(parameters, map))
    }
}
