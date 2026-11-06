package de.valtumy.meowchest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Miau + Katzenohren + eine volle Drehung.
 *
 * Bewusst OHNE Render-Mixins: Die Render-Pipeline hat sich in 26.x mehrfach geaendert.
 * Stattdessen:
 *  - Drehung: der FACING-Wert der Kiste wird in vier 90-Grad-Schritten weitergedreht
 *  - Ohren:   Block-Display-Entities (vanilla), die sich weich mitdrehen
 *  - Sound/Partikel/Displays laufen ueber Vanilla-Befehle -> kaum API-Abhaengigkeit
 * Funktioniert serverseitig; Spieler ohne Mod sehen alles.
 */
public final class ChestSpin {
	private ChestSpin() {}

	// ---------------------------------------------------------------- Einstellungen
	private static final String EAR_TAG = "meowchest_ear";
	private static final int POP_IN_TICKS = 5;           // Ohren "poppen" auf
	private static final int[] STEP_GAPS = {3, 3, 4, 6}; // Ticks pro Vierteldrehung (wird langsamer)
	private static final int EARS_STAY_TICKS = 50;       // so lange bleiben die Ohren nach der Drehung
	private static final int POP_OUT_TICKS = 6;          // Ohren schrumpfen wieder weg

	/** Ohr-Teile, relativ zur Kistenmitte (Grundrichtung: Kiste schaut nach Norden, hinten = +z). */
	private record EarPart(String block, float x, float y, float z, float sx, float sy, float sz) {}

	private static final EarPart[] EARS = {
		// aussen (schwarz)
		new EarPart("minecraft:black_wool", -0.30f, 0.86f, 0.30f, 0.17f, 0.26f, 0.09f),
		new EarPart("minecraft:black_wool",  0.30f, 0.86f, 0.30f, 0.17f, 0.26f, 0.09f),
		// innen (rosa), etwas nach vorn versetzt
		new EarPart("minecraft:pink_wool",  -0.30f, 0.875f, 0.27f, 0.09f, 0.15f, 0.10f),
		new EarPart("minecraft:pink_wool",   0.30f, 0.875f, 0.27f, 0.09f, 0.15f, 0.10f),
	};

	// ---------------------------------------------------------------- Zustand
	private record Task(long due, Runnable run) {}

	private static final List<Task> TASKS = new ArrayList<>();
	private static final Set<String> RUNNING = new HashSet<>();
	private static final Set<String> ACTIVE_TAGS = new HashSet<>();
	private static long now = 0;
	private static long nextId = 0;

	// ---------------------------------------------------------------- Oeffentliche API
	public static void start(ServerLevel level, BlockPos pos) {
		String key = level.dimension() + "|" + pos.asLong();
		if (!RUNNING.add(key)) {
			return; // laeuft schon
		}
		now = level.getServer().getTickCount();
		String id = "meowchest_" + (nextId++);
		ACTIVE_TAGS.add(id);
		new Spin(level, pos.immutable(), key, id).begin();
	}

	public static void meow(ServerLevel level, BlockPos pos) {
		float pitch = 0.9f + ThreadLocalRandom.current().nextFloat() * 0.4f;
		run(level, "playsound minecraft:entity.cat.ambient block @a "
				+ f(pos.getX() + 0.5) + " " + f(pos.getY() + 0.5) + " " + f(pos.getZ() + 0.5)
				+ " 1.0 " + f(pitch));
	}

	public static void tick(MinecraftServer server) {
		now = server.getTickCount();
		if (TASKS.isEmpty()) {
			return;
		}
		List<Task> ready = new ArrayList<>();
		for (Iterator<Task> it = TASKS.iterator(); it.hasNext(); ) {
			Task t = it.next();
			if (t.due() <= now) {
				ready.add(t);
				it.remove();
			}
		}
		for (Task t : ready) {
			t.run().run();
		}
	}

	/** Raeumt Ohren auf, die z.B. nach einem Servercrash/Stop in der Welt haengen geblieben sind. */
	public static void cleanupOrphan(Entity entity, ServerLevel level) {
		Set<String> tags = entity.getTags();
		if (!tags.contains(EAR_TAG)) {
			return;
		}
		for (String tag : tags) {
			if (ACTIVE_TAGS.contains(tag)) {
				return; // gehoert zu einer laufenden Animation
			}
		}
		entity.discard();
	}

	public static void reset() {
		TASKS.clear();
		RUNNING.clear();
		ACTIVE_TAGS.clear();
	}

	// ---------------------------------------------------------------- Animation
	private static final class Spin {
		private final ServerLevel level;
		private final BlockPos pos;
		private final String key;
		private final String id;
		private final double baseYaw;
		private boolean over = false;

		Spin(ServerLevel level, BlockPos pos, String key, String id) {
			this.level = level;
			this.pos = pos;
			this.key = key;
			this.id = id;
			Direction facing = level.getBlockState(pos).getValue(ChestBlock.FACING);
			// Drehwinkel um die Y-Achse, bei dem "vorne" (-z) auf die Blickrichtung zeigt
			this.baseYaw = Math.toRadians(180.0 - facing.toYRot());
		}

		void begin() {
			double cx = pos.getX() + 0.5;
			double cy = pos.getY();
			double cz = pos.getZ() + 0.5;

			// 1) Miau
			meow(level, pos);

			// 2) Ohren spawnen (winzig), dann aufpoppen lassen
			for (int i = 0; i < EARS.length; i++) {
				EarPart p = EARS[i];
				run(level, "summon minecraft:block_display " + f(cx) + " " + f(cy) + " " + f(cz)
						+ " {block_state:{Name:\"" + p.block() + "\"}"
						+ ",Tags:[\"" + EAR_TAG + "\",\"" + id + "\",\"" + id + "_" + i + "\"]"
						+ ",transformation:" + transformation(p, baseYaw, 0.01f)
						+ ",interpolation_duration:0}");
			}
			later(1, () -> earsTo(baseYaw, 1.0f, POP_IN_TICKS));

			// 3) Vier Vierteldrehungen. Die Ohren laufen der Kiste jeweils leicht voraus.
			int t = 1 + POP_IN_TICKS;
			for (int k = 0; k < STEP_GAPS.length; k++) {
				final int step = k + 1;
				final int gap = STEP_GAPS[k];
				later(t, () -> earsTo(baseYaw - step * (Math.PI / 2.0), 1.0f, gap));
				t += gap;
				final boolean last = (step == STEP_GAPS.length);
				later(t, () -> turnChest(last));
			}

			// 4) Ohren bleiben kurz, dann schrumpfen sie weg
			final int popOutAt = t + EARS_STAY_TICKS;
			later(popOutAt, () -> earsTo(baseYaw, 0.01f, POP_OUT_TICKS));
			later(popOutAt + POP_OUT_TICKS + 1, this::finish);
		}

		private void turnChest(boolean last) {
			if (over) {
				return;
			}
			BlockState s = level.getBlockState(pos);
			if (!level.isLoaded(pos)
					|| !(s.getBlock() instanceof ChestBlock)
					|| s.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
				finish(); // Kiste weg/veraendert -> abbrechen
				return;
			}
			Direction next = s.getValue(ChestBlock.FACING).getClockWise();
			level.setBlock(pos, s.setValue(ChestBlock.FACING, next),
					Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);

			double cx = pos.getX() + 0.5;
			double cy = pos.getY() + 0.5;
			double cz = pos.getZ() + 0.5;
			run(level, "particle minecraft:poof " + f(cx) + " " + f(cy) + " " + f(cz)
					+ " 0.35 0.15 0.35 0.02 6");
			if (last) {
				run(level, "particle minecraft:heart " + f(cx) + " " + f(cy + 0.7) + " " + f(cz)
						+ " 0.3 0.2 0.3 0 5");
			}
		}

		private void earsTo(double yaw, float scaleFactor, int duration) {
			if (over) {
				return;
			}
			for (int i = 0; i < EARS.length; i++) {
				run(level, "data merge entity @e[tag=" + id + "_" + i + ",limit=1] {transformation:"
						+ transformation(EARS[i], yaw, scaleFactor)
						+ ",start_interpolation:0,interpolation_duration:" + duration + "}");
			}
		}

		private void finish() {
			if (over) {
				return;
			}
			over = true;
			run(level, "kill @e[tag=" + id + "]");
			ACTIVE_TAGS.remove(id);
			RUNNING.remove(key);
		}
	}

	// ---------------------------------------------------------------- Helfer
	private static void later(int ticks, Runnable r) {
		TASKS.add(new Task(now + ticks, r));
	}

	private static void run(ServerLevel level, String command) {
		try {
			MinecraftServer server = level.getServer();
			CommandSourceStack source = server.createCommandSourceStack()
					.withLevel(level)
					.withSuppressedOutput();
			server.getCommands().performPrefixedCommand(source, command);
		} catch (Exception e) {
			MeowChest.LOGGER.warn("Meow Chest: command failed: {}", command, e);
		}
	}

	private static String f(double v) {
		return String.format(Locale.ROOT, "%.4f", v);
	}

	private static String fl(float v) {
		return f(v) + "f";
	}

	/**
	 * Display-Transformation fuer ein Ohr-Teil. Der Wuerfel (0..1) wird um 45 Grad gekippt (Raute),
	 * skaliert und so verschoben, dass sein Mittelpunkt auf der um {@code yaw} gedrehten Position liegt.
	 * Die untere Haelfte steckt im Deckel -> sieht aus wie ein Dreieck.
	 */
	private static String transformation(EarPart p, double yaw, float scaleFactor) {
		Quaternionf left = new Quaternionf().rotationY((float) yaw);
		Quaternionf right = new Quaternionf().rotationZ((float) Math.toRadians(45.0));
		float sx = p.sx() * scaleFactor;
		float sy = p.sy() * scaleFactor;
		float sz = p.sz() * scaleFactor;

		// Wuerfelmitte nach Kippen, Skalieren und Drehen
		Vector3f centre = new Vector3f(0.5f, 0.5f, 0.5f).rotate(right).mul(sx, sy, sz).rotate(left);
		// Wunschposition der Mitte (relativ zur Block-Unterkante-Mitte), mit der Kiste mitgedreht
		Vector3f wanted = new Vector3f(p.x(), p.y(), p.z()).rotate(left);
		Vector3f t = wanted.sub(centre);

		return "{left_rotation:[" + fl(left.x) + "," + fl(left.y) + "," + fl(left.z) + "," + fl(left.w) + "]"
				+ ",right_rotation:[" + fl(right.x) + "," + fl(right.y) + "," + fl(right.z) + "," + fl(right.w) + "]"
				+ ",scale:[" + fl(sx) + "," + fl(sy) + "," + fl(sz) + "]"
				+ ",translation:[" + fl(t.x) + "," + fl(t.y) + "," + fl(t.z) + "]}";
	}
}
