package cc.sighs.rewind.server;

import cc.sighs.rewind.Rewind;
//? if fabric {
/*import net.fabricmc.loader.api.FabricLoader;
*///?} elif forge {
/*import net.minecraftforge.fml.ModList;
*///?} else {
import net.neoforged.fml.ModList;
//?}

/** 从 mod 容器读取版本号，失败时退化为 unknown（只用于元数据展示）。 */
final class RewindVersion {
    private RewindVersion() {
    }

    static String of() {
        try {
            //? if fabric {
            /*return FabricLoader.getInstance()
                    .getModContainer(Rewind.MOD_ID)
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown");
            *///?} else {
            return ModList.get()
                    .getModContainerById(Rewind.MOD_ID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("unknown");
            //?}
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
}
