package com.unciv.godot;

import com.unciv.logic.civilization.managers.ReligionManager;

/**
 * 仅作可见性转发：core 的 {@code ReligionManager.foundReligion(displayName, name)} 是 Kotlin
 * {@code internal}，编译后 JVM 名为 {@code foundReligion$Unciv_core(String, String)}，Kotlin 侧
 * 无法从本模块直接调用。此桥接提供一层静态直接委托，供网关完成最终创立；不含任何逻辑、不使用反射、
 * 不调整 core 可见性、不使用 friend 编译参数。
 */
public final class NativeReligionBridge {

    private NativeReligionBridge() {
    }

    /** 委托原生最终创立：先建立 Religion、迁移旧万神殿信条、设定圣城与压力，再清空待决创立字段。 */
    public static void foundReligion(ReligionManager manager, String displayName, String name) {
        manager.foundReligion$Unciv_core(displayName, name);
    }
}
