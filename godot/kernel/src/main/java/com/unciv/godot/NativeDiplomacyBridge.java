package com.unciv.godot;

import com.unciv.logic.civilization.diplomacy.DiplomacyManager;

/**
 * 仅转发原生 internal 影响力字段的 JVM 访问器，不使用反射或修改 core 可见性。
 * AlertPopup 的保护城邦宣战奖励直接读写此字段，不钳制数值、不重算盟友，
 * 也不使用战争状态下返回修正值的 getInfluence()。
 */
public final class NativeDiplomacyBridge {
    private NativeDiplomacyBridge() {
    }

    public static float rawInfluence(DiplomacyManager manager) {
        return manager.getInfluence$Unciv_core();
    }

    public static void setRawInfluence(DiplomacyManager manager, float value) {
        manager.setInfluence$Unciv_core(value);
    }
}
