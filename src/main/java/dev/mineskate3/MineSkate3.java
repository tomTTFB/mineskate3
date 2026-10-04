package dev.mineskate3;

import dev.mineskate3.network.SkateNetwork;
import dev.mineskate3.server.SkateServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Common entry point: networking and the server's view of who is skating. */
@Mod(MineSkate3.MODID)
public final class MineSkate3 {
    public static final String MODID = "mineskate3";
    public static final Logger LOGGER = LoggerFactory.getLogger("MineSkate3");

    public MineSkate3(IEventBus modBus) {
        modBus.addListener(SkateNetwork::register);
        SkateSounds.register(modBus);
        SkateServer.register();
    }
}
