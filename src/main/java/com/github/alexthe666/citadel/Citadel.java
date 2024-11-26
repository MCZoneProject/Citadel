package com.github.alexthe666.citadel;

import com.github.alexthe666.citadel.config.ConfigHolder;
import com.github.alexthe666.citadel.config.ServerConfig;
import com.github.alexthe666.citadel.item.ItemCitadelBook;
import com.github.alexthe666.citadel.item.ItemCitadelDebug;
import com.github.alexthe666.citadel.item.ItemCustomRender;
import com.github.alexthe666.citadel.server.CitadelEvents;
import com.github.alexthe666.citadel.server.block.CitadelLecternBlock;
import com.github.alexthe666.citadel.server.block.CitadelLecternBlockEntity;
import com.github.alexthe666.citadel.server.block.LecternBooks;
import com.github.alexthe666.citadel.server.generation.SpawnProbabilityModifier;
import com.github.alexthe666.citadel.server.generation.VillageHouseManager;
import com.github.alexthe666.citadel.server.message.*;
import com.github.alexthe666.citadel.web.WebHelper;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.InterModEnqueueEvent;
import net.neoforged.fml.event.lifecycle.InterModProcessEvent;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

;

@Mod("citadel")
public class Citadel {
    public static final Logger LOGGER = LogManager.getLogger("citadel");
    private static final String PROTOCOL_VERSION = Integer.toString(1);
    private static final ResourceLocation PACKET_NETWORK_NAME = new ResourceLocation("citadel:main_channel");
    public static final SimpleChannel NETWORK_WRAPPER = NetworkRegistry.ChannelBuilder
            .named(PACKET_NETWORK_NAME)
            .clientAcceptedVersions(PROTOCOL_VERSION::equals)
            .serverAcceptedVersions(PROTOCOL_VERSION::equals)
            .networkProtocolVersion(() -> PROTOCOL_VERSION)
            .simpleChannel();
    public static ServerProxy PROXY = DistExecutor.runForDist(() -> ClientProxy::new, () -> ServerProxy::new);
    public static List<String> PATREONS = new ArrayList<>();
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, "citadel");
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, "citadel");
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, "citadel");

    public static final Supplier<Item> DEBUG_ITEM = ITEMS.register("debug", () -> new ItemCitadelDebug(new Item.Properties()));
    public static final Supplier<Item> CITADEL_BOOK = ITEMS.register("citadel_book", () -> new ItemCitadelBook(new Item.Properties().stacksTo(1)));
    public static final Supplier<Item> EFFECT_ITEM = ITEMS.register("effect_item", () -> new ItemCustomRender(new Item.Properties().stacksTo(1)));
    public static final Supplier<Item> FANCY_ITEM = ITEMS.register("fancy_item", () -> new ItemCustomRender(new Item.Properties().stacksTo(1)));
    public static final Supplier<Item> ICON_ITEM = ITEMS.register("icon_item", () -> new ItemCustomRender(new Item.Properties().stacksTo(1)));

    public static final Supplier<Block> LECTERN = BLOCKS.register("lectern", () -> new CitadelLecternBlock(BlockBehaviour.Properties.copy(Blocks.LECTERN)));

    public static final Supplier<BlockEntityType<CitadelLecternBlockEntity>> LECTERN_BE = BLOCK_ENTITIES.register("lectern", () -> BlockEntityType.Builder.of(CitadelLecternBlockEntity::new, LECTERN.get()).build(null));


    public Citadel() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        bus.addListener(this::setup);
        bus.addListener(this::enqueueIMC);
        bus.addListener(this::processIMC);
        bus.addListener(this::doClientStuff);
        bus.addListener(this::onModConfigEvent);
        ITEMS.register(bus);
        BLOCKS.register(bus);
        BLOCK_ENTITIES.register(bus);
        final DeferredRegister<Codec<? extends BiomeModifier>> serializers = DeferredRegister.create(ForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, "citadel");
        serializers.register(bus);
        serializers.register("mob_spawn_probability", SpawnProbabilityModifier::makeCodec);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(PROXY);
        final ModLoadingContext modLoadingContext = ModLoadingContext.get();
        modLoadingContext.registerConfig(ModConfig.Type.COMMON, ConfigHolder.SERVER_SPEC);
        MinecraftForge.EVENT_BUS.register(new CitadelEvents());
    }

    public static <MSG> void sendMSGToServer(MSG message) {
        NETWORK_WRAPPER.sendToServer(message);
    }

    public static <MSG> void sendMSGToAll(MSG message) {
        for (ServerPlayer player : ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayers()) {
            sendNonLocal(message, player);
        }
    }

    public static <MSG> void sendNonLocal(MSG msg, ServerPlayer player) {
        NETWORK_WRAPPER.sendTo(msg, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

    private void setup(final FMLCommonSetupEvent event) {
        PROXY.onPreInit();
        LecternBooks.init();
        int packetsRegistered = 0;
        NETWORK_WRAPPER.registerMessage(packetsRegistered++, PropertiesMessage.class, PropertiesMessage::write, PropertiesMessage::read, PropertiesMessage.Handler::handle);
        NETWORK_WRAPPER.registerMessage(packetsRegistered++, AnimationMessage.class, AnimationMessage::write, AnimationMessage::read, AnimationMessage.Handler::handle);
        NETWORK_WRAPPER.registerMessage(packetsRegistered++, SyncClientTickRateMessage.class, SyncClientTickRateMessage::write, SyncClientTickRateMessage::read, SyncClientTickRateMessage.Handler::handle);
        NETWORK_WRAPPER.registerMessage(packetsRegistered++, DanceJukeboxMessage.class, DanceJukeboxMessage::write, DanceJukeboxMessage::read, DanceJukeboxMessage.Handler::handle);
        NETWORK_WRAPPER.registerMessage(packetsRegistered++, MessageSyncPath.class, MessageSyncPath::write, MessageSyncPath::read, MessageSyncPath.Handler::handle);
        NETWORK_WRAPPER.registerMessage(packetsRegistered++, MessageSyncPathReached.class, MessageSyncPathReached::write, MessageSyncPathReached::read, MessageSyncPathReached.Handler::handle);
        BufferedReader urlContents = WebHelper.getURLContents("https://raw.githubusercontent.com/Alex-the-666/Citadel/master/src/main/resources/assets/citadel/patreon.txt", "assets/citadel/patreon.txt");
        if (urlContents != null) {
            try {
                String line;
                while ((line = urlContents.readLine()) != null) {
                    PATREONS.add(line);
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to load patreon contributor perks");
            }
        } else LOGGER.warn("Failed to load patreon contributor perks");
    }

    @SubscribeEvent
    public void onModConfigEvent(final ModConfigEvent event) {
        final ModConfig config = event.getConfig();
        // Rebake the configs when they change
        ServerConfig.skipWarnings = ConfigHolder.SERVER.skipDatapackWarnings.get();
        if (config.getSpec() == ConfigHolder.SERVER_SPEC) {
            ServerConfig.citadelEntityTrack = ConfigHolder.SERVER.citadelEntityTracker.get();
            ServerConfig.chunkGenSpawnModifierVal = ConfigHolder.SERVER.chunkGenSpawnModifier.get();
            ServerConfig.aprilFools = ConfigHolder.SERVER.aprilFoolsContent.get();
            //citadelTestBiomeData = SpawnBiomeConfig.create(new ResourceLocation("citadel:config_biome"), CitadelBiomeDefinitions.TERRALITH_TEST);
        }
    }

    private void doClientStuff(final FMLClientSetupEvent event) {
        event.enqueueWork(() -> PROXY.onClientInit());
    }

    private void enqueueIMC(final InterModEnqueueEvent event) {

    }

    private void processIMC(final InterModProcessEvent event) {

    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        RegistryAccess registryAccess = event.getServer().registryAccess();
        VillageHouseManager.addAllHouses(registryAccess);
    }

}