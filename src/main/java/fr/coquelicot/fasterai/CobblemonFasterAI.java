package fr.coquelicot.fasterai;

import fr.coquelicot.fasterai.ai.FarSlowMotion;
import fr.coquelicot.fasterai.command.FasterAICommands;
import fr.coquelicot.fasterai.config.FasterAIConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CobblemonFasterAI implements ModInitializer {
    public static final String MOD_ID = "cobblemon_faster_ai";
    public static final Logger LOGGER = LoggerFactory.getLogger("Cobblemon Faster AI");

    @Override
    public void onInitialize() {
        FasterAIConfig.load();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> FasterAICommands.register(dispatcher));
        ServerTickEvents.START_SERVER_TICK.register(server -> FarSlowMotion.onStartTick());
        ServerTickEvents.END_SERVER_TICK.register(server -> FarSlowMotion.onEndTick());
        LOGGER.info("Cobblemon Faster AI loaded (Cobblemon {}), optimisations {}, far slow motion {}", cobblemonVersion(),
                FasterAIConfig.INSTANCE.enabled ? "enabled" : "disabled",
                !FasterAIConfig.INSTANCE.farSlowMotion ? "off" : FasterAIConfig.INSTANCE.farSlowMotionOnlyWhenOverloaded ? "when overloaded" : "always");
    }

    public static String cobblemonVersion() {
        return FabricLoader.getInstance().getModContainer("cobblemon")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }
}
