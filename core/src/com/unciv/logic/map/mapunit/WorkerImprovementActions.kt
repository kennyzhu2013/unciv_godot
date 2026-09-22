package com.unciv.logic.map.mapunit

import com.unciv.Constants
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.tile.TileImprovement

/**
 * 无 GUI 的共享施工入口：[ImprovementPickerScreen][com.unciv.ui.screens.pickerscreens.ImprovementPickerScreen]
 * 与 Godot 网关共同调用；只包含状态操作，不包含开关屏幕或界面回调。
 */
object WorkerImprovementActions {
    /**
     * 与原客户端 ImprovementPickerScreen.accept 的状态操作逐行一致：
     * 同名在建项目不重新开始、不重置工期或重复扣库存资源；改选只调用 [Tile.startWorkingOnImprovement]；
     * 取消只停止工程，不唤醒单位、不回调 [onAccept]；普通接受把 [MapUnit.action] 置空以唤醒休眠工人。
     *
     * @return `false` 表示没有提交任何操作（无项目，或地块已被 CreatesOneImprovement 保留），调用方应保持现状。
     */
    fun accept(
        tile: Tile,
        unit: MapUnit,
        improvement: TileImprovement?,
        secondImprovement: TileImprovement? = null,
        onAccept: () -> Unit = {}
    ): Boolean {
        if (improvement == null || tile.isMarkedForCreatesOneImprovement()) return false
        if (improvement.name == Constants.cancelImprovementOrder) {
            tile.stopWorkingOnImprovement()
            // no onAccept() - Worker can stay selected
        } else {
            if (improvement.name != tile.improvementInProgress) {
                tile.startWorkingOnImprovement(improvement, unit.civ, unit)
                if (secondImprovement != null)
                    tile.queueImprovement(secondImprovement, unit.civ, unit)
            }
            unit.action = null // this is to "wake up" the worker if it's sleeping
            onAccept()
        }
        return true
    }
}
