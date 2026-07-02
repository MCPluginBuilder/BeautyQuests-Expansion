package fr.skytasul.quests.expansion.tracking;

import fr.skytasul.glowingentities.GlowingBlocks;
import fr.skytasul.glowingentities.GlowingEntities;
import fr.skytasul.quests.BeautyQuests;
import fr.skytasul.quests.api.editors.TextEditor;
import fr.skytasul.quests.api.editors.parsers.EnumParser;
import fr.skytasul.quests.api.gui.LoreBuilder;
import fr.skytasul.quests.api.localization.Lang;
import fr.skytasul.quests.api.objects.QuestObjectClickEvent;
import fr.skytasul.quests.api.stages.AbstractStage;
import fr.skytasul.quests.api.stages.StageType;
import fr.skytasul.quests.api.stages.types.Locatable;
import fr.skytasul.quests.api.stages.types.Locatable.Located;
import fr.skytasul.quests.api.stages.types.Locatable.Located.LocatedBlock;
import fr.skytasul.quests.api.stages.types.Locatable.Located.LocatedEntity;
import fr.skytasul.quests.api.stages.types.Locatable.LocatedType;
import fr.skytasul.quests.api.stages.types.Locatable.MultipleLocatable;
import fr.skytasul.quests.api.stages.types.Locatable.MultipleLocatable.NearbyFetcher;
import fr.skytasul.quests.api.stages.types.Locatable.PreciseLocatable;
import fr.skytasul.quests.expansion.BeautyQuestsExpansion;
import fr.skytasul.quests.expansion.api.tracking.Tracker;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class GlowingTracker extends AbstractTaskTracker {

	private static final ChatColor DEFAULT_COLOR = ChatColor.GREEN;
	private static final double DEFAULT_MAX_DISTANCE = 40;
	private static final boolean DEFAULT_BLOCK_UNDER_ENTITY = false;
	private static final int DEFAULT_MAX_AMOUNT = 10;

	private static final long UPDATE_RATE = 50L;

	private static GlowingEntities ENTITIES_API;
	private static GlowingBlocks BLOCKS_API;

	private ChatColor color;
	private double maxDistance;
	private int maxAmount;
	private boolean blockUnderEntity;

	private Map<Player, Set<Glowing>> shown;

	public GlowingTracker() {
		this(DEFAULT_COLOR, DEFAULT_MAX_DISTANCE, DEFAULT_MAX_AMOUNT, DEFAULT_BLOCK_UNDER_ENTITY);
	}

	public GlowingTracker(ChatColor color, double maxDistance, int maxAmount, boolean blockUnderEntity) {
		super(UPDATE_RATE);
		this.color = color;
		this.maxDistance = maxDistance;
		this.maxAmount = maxAmount;
		this.blockUnderEntity = blockUnderEntity;

		initializeUtils();
	}

	@Override
	public void start(Locatable locatable) {
		super.start(locatable);
		if (locatable.canBeFetchedAsynchronously()) {
			shown = new ConcurrentHashMap<>();
		}else {
			shown = new HashMap<>();
		}
	}

	@Override
	public void stop() {
		super.stop();
		if (shown != null && !shown.isEmpty()) shown.forEach((__, set) -> set.forEach(Glowing::remove));
	}

	@Override
	public synchronized void run() {
		shown.values().forEach(set -> set.forEach(glowing -> glowing.found = false));

		if (locatable instanceof PreciseLocatable) {
			PreciseLocatable precise = (PreciseLocatable) locatable;
			Located located = precise.getLocated();
			if (!isRunning()) return;

			if (located instanceof LocatedEntity) {
				Entity entity = ((LocatedEntity) located).getEntity();
				if (entity != null) {
					shown.forEach((player, set) -> {
						foundLocatedEntity(player, set, entity);
					});
				}
			} else if (located instanceof LocatedBlock) {
				Location block = located.getLocation();
				if (block != null) {
					shown.forEach((player, set) -> {
						foundLocatedBlock(player, set, block);
					});
				}
			}
		}

		if (locatable instanceof MultipleLocatable) {
			MultipleLocatable multiple = (MultipleLocatable) locatable;
			shown.forEach((player, set) -> {
				Spliterator<Located> locateds = multiple.getNearbyLocated(NearbyFetcher.create(player.getLocation(), maxDistance, LocatedType.ENTITY));
				int i;
				for (i = 0; i < maxAmount; i++) {
					if (!locateds
							.tryAdvance(located -> foundLocatedEntity(player, set, ((LocatedEntity) located).getEntity())))
						break;
				}

				locateds = multiple
						.getNearbyLocated(NearbyFetcher.create(player.getLocation(), maxDistance, LocatedType.BLOCK));
				for (; i < maxAmount; i++) {
					if (!locateds
							.tryAdvance(located -> foundLocatedBlock(player, set, located.getLocation())))
						break;
				}
			});
		}

		shown.values().forEach(set -> {
			for (Iterator<Glowing> iterator = set.iterator(); iterator.hasNext();) {
				Glowing glowing = iterator.next();
				if (!glowing.found) {
					glowing.remove();
					iterator.remove();
				}
			}
		});
	}

	private void foundLocatedEntity(Player player, Set<Glowing> playerSet, Entity located) {
		foundLocated(player, playerSet, GlowingEntity.class, glowing -> glowing.entity.equals(located),
				() -> new GlowingEntity(player, located));
	}

	private void foundLocatedBlock(Player player, Set<Glowing> playerSet, Location located) {
		foundLocated(player, playerSet, GlowingBlock.class, glowing -> glowing.block.equals(located),
				() -> new GlowingBlock(player, located));
	}

	private <T extends Glowing> void foundLocated(Player player, Set<Glowing> playerSet, Class<T> glowingType,
			Predicate<T> filter, Supplier<T> supplier) {
		Optional<T> glowingOpt = playerSet.stream()
				.filter(glowingType::isInstance)
				.map(glowingType::cast)
				.filter(filter).findAny();
		if (glowingOpt.isPresent()) {
			glowingOpt.get().found = true;
		}else {
			Glowing glowing = supplier.get();
			playerSet.add(glowing);
			glowing.display();
		}
	}

	@Override
	public Tracker clone() {
		return new GlowingTracker(color, maxDistance, maxAmount, blockUnderEntity);
	}

	@Override
	public void show(Player player) {
		shown.put(player, new HashSet<>());
	}

	@Override
	public void hide(Player player) {
		Set<Glowing> glowing = shown.remove(player);
		if (glowing != null && !glowing.isEmpty()) glowing.forEach(Glowing::remove);
	}

	@Override
	protected void addLore(LoreBuilder loreBuilder) {
		super.addLore(loreBuilder);
		loreBuilder.addDescriptionAsValue(color.name().toLowerCase().replace('_', ' '));
	}

	@Override
	public void itemClick(QuestObjectClickEvent event) {
		if (event.isInCreation()) return;
		Lang.COLOR_NAMED_EDITOR.send(event.getPlayer());
		new TextEditor<>(event.getPlayer(), event::reopenGUI, newColor -> {
			this.color = newColor;
			event.reopenGUI();
		}, new EnumParser<>(ChatColor.class, ChatColor::isColor)).start();
	}

	@Override
	public void save(ConfigurationSection section) {
		if (color != DEFAULT_COLOR)
			section.set("color", color.name());
		if (maxDistance != DEFAULT_MAX_DISTANCE)
			section.set("max distance", maxDistance);
		if (maxAmount != DEFAULT_MAX_AMOUNT)
			section.set("max amount", maxAmount);
		if (blockUnderEntity != DEFAULT_BLOCK_UNDER_ENTITY)
			section.set("block under entity", blockUnderEntity);
	}

	@Override
	public void load(ConfigurationSection section) {
		if (section.contains("color"))
			color = ChatColor.valueOf(section.getString("color"));
		if (section.contains("max distance"))
			maxDistance = section.getDouble("max distance");
		if (section.contains("max amount"))
			maxAmount = section.getInt("max amount");
		if (section.contains("block under entity"))
			blockUnderEntity = section.getBoolean("block under entity");
	}

	abstract class Glowing {
		protected final Player player;
		protected boolean found = true;

		protected Glowing(Player player) {
			this.player = player;
		}

		public abstract void display();

		public abstract void remove();

	}

	class GlowingEntity extends Glowing {
		private final Entity entity;

		public GlowingEntity(Player player, Entity entity) {
			super(player);
			this.entity = entity;
		}

		@Override
		public void display() {
			try {
				ENTITIES_API.setGlowing(entity, player, color);

				if (blockUnderEntity)
					BLOCKS_API.setGlowing(entity.getLocation().subtract(0, 1, 0), player, color);
			}catch (ReflectiveOperationException e) {
				e.printStackTrace();
			}
		}

		@Override
		public void remove() {
			try {
				ENTITIES_API.unsetGlowing(entity, player);

				if (blockUnderEntity)
					BLOCKS_API.unsetGlowing(entity.getLocation().subtract(0, 1, 0), player);
			}catch (ReflectiveOperationException e) {
				e.printStackTrace();
			}
		}

		@Override
		public int hashCode() {
			return entity.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			if (obj instanceof GlowingEntity) {
				return ((GlowingEntity) obj).entity.equals(entity);
			}
			return false;
		}

	}

	class GlowingBlock extends Glowing {
		private final Location block;

		public GlowingBlock(Player player, Location block) {
			super(player);
			this.block = block;
		}

		@Override
		public void display() {
			try {
				BLOCKS_API.setGlowing(block, player, color);
			} catch (ReflectiveOperationException e) {
				e.printStackTrace();
			}
		}

		@Override
		public void remove() {
			try {
				BLOCKS_API.unsetGlowing(block, player);
			} catch (ReflectiveOperationException e) {
				e.printStackTrace();
			}
		}

		@Override
		public int hashCode() {
			return block.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			if (obj instanceof GlowingBlock) {
				return ((GlowingBlock) obj).block.equals(block);
			}
			return false;
		}

	}

	private static synchronized void initializeUtils() {
		if (ENTITIES_API == null)
			ENTITIES_API = new GlowingEntities(BeautyQuestsExpansion.getInstance());
		if (BLOCKS_API == null)
			BLOCKS_API = new GlowingBlocks(BeautyQuestsExpansion.getInstance());
	}

	public static <T extends AbstractStage & Locatable> boolean isStageEnabled(StageType<T> type) {
		if (Locatable.hasLocatedTypes(type.getStageClass(), LocatedType.ENTITY))
			return true;

		return Locatable.hasLocatedTypes(type.getStageClass(), LocatedType.BLOCK);
	}

}
