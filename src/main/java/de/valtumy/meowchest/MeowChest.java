package de.valtumy.meowchest;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MeowChest implements ModInitializer {
	public static final String MOD_ID = "meowchest";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Rechtsklick auf eine Kiste (nur Server-Seite, nur Haupthand, nicht beim Schleichen)
		UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
			if (hand != InteractionHand.MAIN_HAND || player.isShiftKeyDown()) {
				return InteractionResult.PASS;
			}
			if (level instanceof ServerLevel serverLevel) {
				BlockPos pos = hitResult.getBlockPos();
				BlockState state = serverLevel.getBlockState(pos);
				if (state.getBlock() instanceof ChestBlock) {
					if (state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
						ChestSpin.start(serverLevel, pos);
					} else {
						// Doppelkisten wuerden beim Drehen auseinanderfallen -> nur miauen
						ChestSpin.meow(serverLevel, pos);
					}
				}
			}
			// PASS: die Kiste oeffnet sich ganz normal
			return InteractionResult.PASS;
		});

		ServerTickEvents.END_SERVER_TICK.register(ChestSpin::tick);
		ServerEntityEvents.ENTITY_LOAD.register(ChestSpin::cleanupOrphan);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> ChestSpin.reset());
	}
}
