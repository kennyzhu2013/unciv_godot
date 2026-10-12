package com.unciv.godot

import com.unciv.logic.GameInfo
import kotlinx.serialization.json.JsonObject

/**
 * 网关命令的唯一登记处。每个命令名只在这里出现一次：查询或效果、候选来源、
 * 是否就地备份、prepare 与 execute 都是这一次登记的参数。
 * 重复登记在构建时失败，避免后写的条目静默覆盖先写的条目。
 *
 * 领域模块通过 contribute 把自己的命令加进来。GameSession 只组合这些贡献，
 * 再加上仍依赖会话私有助手的少量核心命令。hello／snapshot 是会话传输动词，不进本表。
 */
internal class CommandRegistry {
    private val commands = LinkedHashMap<String, GatewayCommand>()

    fun query(name: String, data: (CommandContext) -> JsonObject) {
        put(name, object : QueryCommand {
            override fun data(ctx: CommandContext) = data(ctx)
        })
    }

    fun effect(
        name: String,
        source: CandidateSource = CandidateSource.CURRENT,
        inPlace: Boolean = true,
        openCandidate: ((CommandContext) -> GameInfo)? = null,
        executeFn: (CommandContext, () -> Unit) -> Unit = { _, prepared -> prepared() },
        // 最后一个函数参数，这样 registry.effect("name") { ... } 写的是 prepare，而不是打开候选对局。
        prepareFn: (CommandContext) -> (() -> Unit) = { {} },
    ) {
        put(name, object : EffectCommand {
            override val source: CandidateSource = source
            override val inPlace: Boolean = inPlace
            override fun prepare(ctx: CommandContext): () -> Unit = prepareFn(ctx)
            override fun execute(ctx: CommandContext, prepared: () -> Unit) = executeFn(ctx, prepared)
            override fun openCandidate(ctx: CommandContext): GameInfo? = openCandidate?.invoke(ctx)
        })
    }

    fun build(): Map<String, GatewayCommand> = commands.toMap()

    private fun put(name: String, command: GatewayCommand) {
        require(commands.put(name, command) == null) { "命令重复注册：$name" }
    }
}
