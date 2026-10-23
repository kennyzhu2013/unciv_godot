package com.unciv.godot

import com.badlogic.gdx.files.FileHandle
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.files.UncivFiles
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** 与导入源隔离的原格式存档仓库；预览不加载完整对局、不触发 setTransients。 */
internal class GameSaves(val directory: File) {
    private val automatic get() = File(directory, "autosaves")

    fun list(): JsonObject {
        val orderedFiles = files()
        val entries = orderedFiles.map { file ->
            val info = try {
                validateFile(file)
                val preview = UncivGame.Current.files.loadGamePreviewFromFile(FileHandle(file))
                val player = preview.civilizations.singleOrNull { it.isPlayerCivilization() }
                val valid = preview.gameId.isNotEmpty() && player != null && preview.currentPlayer == player.civID &&
                    !preview.gameParameters.isOnlineMultiplayer && preview.gameParameters.mods.isEmpty()
                dto("loadable" to valid, "nation" to player?.civName, "turn" to preview.turns,
                    "gameId" to preview.gameId, "error" to if (valid) "" else "不是受支持的单人离线存档")
            } catch (_: Exception) {
                dto("loadable" to false, "error" to "存档损坏、为空或超过 32 MiB")
            }
            JsonObject(info + dto("name" to file.nameWithoutExtension, "path" to file.absolutePath,
                "modifiedAt" to file.lastModified(), "automatic" to (file.parentFile == automatic)))
        }
        return dto("saves" to entries, "latestPath" to orderedFiles.firstOrNull()?.absolutePath,
            "directory" to directory.absolutePath, "autosaveSlots" to 5)
    }

    private fun files(): List<File> = (directory.listFiles().orEmpty().asList() + automatic.listFiles().orEmpty().asList())
        .filter { it.isFile && it.extension == "json" && it.name != "demo.json" }
        .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.absolutePath })

    fun latest(): File = files().firstOrNull() ?: throw GatewayError("NO_SAVE", "没有可继续的存档，请先创建新游戏或读取存档")

    fun load(file: File): GameInfo {
        validateFile(file)
        return UncivFiles.gameInfoFromString(file.readText(Charsets.UTF_8))
    }

    private fun validateFile(file: File) {
        if (!file.isFile || file.length() !in 1..32L * 1024 * 1024)
            throw GatewayError("SAVE_FILE", "存档不存在、为空或超过 32 MiB")
    }

    fun autosave(game: GameInfo): String {
        Files.createDirectories(automatic.toPath())
        // 对局 ID 可能来自外部存档，转为固定安全文件名；同回合重载只更新同一槽，不消耗历史槽。
        val id = UUID.nameUUIDFromBytes(game.gameId.toByteArray(Charsets.UTF_8))
        val target = File(automatic, "$id-${Math.floorMod(game.turns, 5)}.json")
        save(game, target)
        return target.absolutePath
    }

    fun save(game: GameInfo, target: File) {
        Files.createDirectories(directory.toPath())
        val temporary = Files.createTempFile(target.parentFile.toPath(), "save-", ".tmp")
        try {
            Files.writeString(temporary, UncivFiles.gameInfoToString(game, true))
            // 不降级为先删除再写入；原子替换失败时保留上一个可恢复的存档并拒绝提交。
            Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
