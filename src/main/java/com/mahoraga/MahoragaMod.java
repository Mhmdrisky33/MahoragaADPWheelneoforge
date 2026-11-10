package com.mahoraga;

import com.mahoraga.network.MahoragaNetwork;
import com.mahoraga.registry.ModEffects;
import com.mahoraga.registry.ModEntities;
import com.mahoraga.registry.ModItems;
import com.mahoraga.registry.ModSounds;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * 「魔虚罗」—— 把魔虚罗的能力搬到玩家身上的独立模组。
 * 不依赖任何咒术模组，只需要 GeckoLib。
 */
@Mod(MahoragaMod.MOD_ID)
public class MahoragaMod {
    public static final String MOD_ID = "mahoraga";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MahoragaMod(IEventBus bus) {

        ModItems.register(bus);
        ModEntities.register(bus);
        ModSounds.register(bus);
        ModEffects.register(bus);          // 永恒流血（随机适应库 #1）

        // 网络通道：站液体表面 / 多段跳额度的同步 + 挥空放剑气
        bus.addListener(MahoragaNetwork::register);
    }
}
