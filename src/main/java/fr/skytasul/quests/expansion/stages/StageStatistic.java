package fr.skytasul.quests.expansion.stages;

import com.cryptomorin.xseries.XMaterial;
import fr.skytasul.quests.api.QuestsPlugin;
import fr.skytasul.quests.api.editors.parsers.NumberParser;
import fr.skytasul.quests.api.gui.ItemUtils;
import fr.skytasul.quests.api.gui.templates.StaticPagedGUI;
import fr.skytasul.quests.api.options.QuestOption;
import fr.skytasul.quests.api.players.PlayerQuester;
import fr.skytasul.quests.api.questers.Quester;
import fr.skytasul.quests.api.stages.AbstractStage;
import fr.skytasul.quests.api.stages.StageController;
import fr.skytasul.quests.api.stages.StageDescriptionPlaceholdersContext;
import fr.skytasul.quests.api.stages.creation.StageCreation;
import fr.skytasul.quests.api.stages.creation.StageCreationContext;
import fr.skytasul.quests.api.utils.ComparisonMethod;
import fr.skytasul.quests.api.utils.messaging.PlaceholderRegistry;
import fr.skytasul.quests.api.utils.progress.HasProgress;
import fr.skytasul.quests.api.utils.progress.ProgressPlaceholders;
import fr.skytasul.quests.expansion.BeautyQuestsExpansion;
import fr.skytasul.quests.expansion.utils.LangExpansion;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.Statistic.Type;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class StageStatistic extends AbstractStage implements HasProgress {

	private final StatisticData statistic;
	private final int limit;
	private final ComparisonMethod comparison;
	private final boolean relative;

	private BukkitTask task;
	private List<Player> players;

	private Map<Quester, Integer> lastValues = new HashMap<>();

	public StageStatistic(StageController controller, StatisticData statistic, int limit, ComparisonMethod comparison,
			boolean relative) {
		super(controller);

		this.statistic = statistic;

		this.limit = limit;
		this.comparison = comparison;
		this.relative = relative;
	}

	@Override
	public @NotNull String getDefaultDescription(@NotNull StageDescriptionPlaceholdersContext context) {
		return LangExpansion.Stage_Statistic_Advancement.toString();
	}

	@Override
	protected void createdPlaceholdersRegistry(@NotNull PlaceholderRegistry placeholders) {
		super.createdPlaceholdersRegistry(placeholders);

		String offsetName = statistic.dataName();
		placeholders.registerIndexed("statistic_type_name",
				statistic.statistic().name() + (offsetName == null ? "" : "(" + offsetName + ")"));
		placeholders.registerIndexedContextual("remaining_value", StageDescriptionPlaceholdersContext.class,
				context -> {
					if (context.getQuester() instanceof PlayerQuester quester && quester.isActive())
						return Integer.toString(limit - getPlayerTarget(quester.getPlayer().get(), quester));
					return "error: not a player";
				});
		placeholders.registerIndexed("statistic_name", statistic.statistic().name());
		placeholders.registerIndexed("type_name", offsetName);
		placeholders.register("target_value", limit);
		ProgressPlaceholders.registerProgress(placeholders, "statistic", this);
	}

	@Override
	public long getTotalAmount() {
		return limit;
	}

	@Override
	public long getRemainingAmount(@NotNull Quester quester) {
		if (!(quester instanceof PlayerQuester playerQuester))
			throw new IllegalArgumentException("Not a player");
		return limit - getPlayerTarget(playerQuester.getPlayer().get(), quester);
	}

	private int getPlayerTarget(Player player, Quester quester) {
		int stat = statistic.getPlayerStatistic(player);

		if (relative) {
			Integer initial = getData(quester, "initial", Integer.class);
			if (initial != null) stat -= initial.intValue();
		}

		return stat;
	}

	protected void refresh() {
		players.forEach(player -> {
			if (!matchesRequirements(player)) return;

			for (Quester quester : controller.getApplicableQuesters(player)) {
				int playerTarget = getPlayerTarget(player, quester);
				if (lastValues.getOrDefault(quester, Integer.MIN_VALUE) == playerTarget)
					continue;

				if (comparison.test(playerTarget - limit)) {
					lastValues.remove(quester);
					controller.finishStage(quester);
				} else {
					lastValues.put(quester, playerTarget);
					controller.notifyQuesterUpdate(quester);
				}
			}
		});
	}

	@Override
	public void load() {
		super.load();
		players = new ArrayList<>();
		task = Bukkit.getScheduler().runTaskTimerAsynchronously(BeautyQuestsExpansion.getInstance(), this::refresh, 20, 20);
	}

	@Override
	public void unload() {
		super.unload();
		players = null;
		if (task != null) task.cancel();
	}

	@Override
	public void initPlayerDatas(Quester quester, Map<String, Object> datas) {
		super.initPlayerDatas(quester, datas);
		if (relative) {
			int stat = 0;
			if (quester instanceof PlayerQuester playerQuester) {
				if (playerQuester.isActive()) {
					stat = statistic.getPlayerStatistic(playerQuester.getPlayer().get());
				} else {
					BeautyQuestsExpansion.logger.warning(
							"Trying to fetch initial statistic value for quester {0} that is offline (stage {1}).",
							quester.getDetailedName());
				}
			} else {
				BeautyQuestsExpansion.logger.warning(
						"Trying to fetch initial statistic value for quester {0} that is not an actual player (stage {1}).",
						quester.getDetailedName(), controller);
			}

			datas.put("initial", stat);
		}
	}

	@Override
	public void joined(@NotNull Player player, @NotNull Quester quester) {
		super.joined(player, quester);
		players.add(player);
	}

	@Override
	public void left(@NotNull Player player, @NotNull Quester quester) {
		super.left(player, quester);
		players.remove(player);
	}

	@Override
	public void started(@NotNull Quester quester) {
		super.started(quester);
		players.addAll(quester.getOnlinePlayers());
	}

	@Override
	public void ended(@NotNull Quester quester) {
		super.ended(quester);
		players.removeAll(quester.getOnlinePlayers());
	}

	@Override
	protected void serialize(ConfigurationSection section) {
		section.set("statistic", statistic.statistic().name());

		if (statistic instanceof MaterialStatistic materialStatistic)
			section.set("material", materialStatistic.material().name());
		else if (statistic instanceof EntityStatistic entityStatistic)
			section.set("entity", entityStatistic.entityType().name());

		section.set("limit", limit);
		if (relative) section.set("relative", true);
		if (comparison != ComparisonMethod.GREATER_OR_EQUAL) section.set("comparison", comparison.name());
	}

	public static StageStatistic deserialize(ConfigurationSection section, StageController controller) {
		Statistic statistic = Statistic.valueOf(section.getString("statistic"));
		int limit = section.getInt("limit");
		boolean relative = section.getBoolean("relative", false);
		ComparisonMethod comparison = section.contains("comparison") ? ComparisonMethod.valueOf(section.getString("comparison")) : ComparisonMethod.GREATER_OR_EQUAL;

		StatisticData statisticData;
		if (section.contains("material"))
			statisticData = new MaterialStatistic(statistic, Material.valueOf(section.getString("material")));
		else if (section.contains("entity"))
			statisticData = new EntityStatistic(statistic, EntityType.valueOf(section.getString("entity")));
		else
			statisticData = new SimpleStatistic(statistic);

		return new StageStatistic(controller, statisticData, limit, comparison, relative);
	}

	public static class Creator extends StageCreation<StageStatistic> {

		private static Map<Statistic, ItemStack> STATISTIC_ITEMS =
				Stream.of(Statistic.values()).collect(Collectors.toMap(stat -> stat, Creator::getStatisticItem));

		private static ItemStack getStatisticItem(Statistic object) {
			XMaterial material;
			String lore = null;
			switch (object.getType()) {
			case BLOCK:
				material = XMaterial.GRASS_BLOCK;
				lore = LangExpansion.Stage_Statistic_StatList_Gui_Block.toString();
				break;
			case ENTITY:
				material = XMaterial.BLAZE_SPAWN_EGG;
				lore = LangExpansion.Stage_Statistic_StatList_Gui_Entity.toString();
				break;
			case ITEM:
				material = XMaterial.STONE_HOE;
				lore = LangExpansion.Stage_Statistic_StatList_Gui_Item.toString();
				break;
			default:
				material = XMaterial.FEATHER;
				break;
			}
			return ItemUtils.item(material, "§e" + object.name(), QuestOption.formatDescription(lore));
		}

		private static final int SLOT_STAT = 5;
		private static final int SLOT_LIMIT = 6;
		private static final int SLOT_RELATIVE = 7;

		private StatisticData statistic;

		private int limit;
		private ComparisonMethod comparison = ComparisonMethod.GREATER_OR_EQUAL;
		private boolean relative = false;

		public Creator(@NotNull StageCreationContext<StageStatistic> context) {
			super(context);

			getLine().setItem(SLOT_STAT,
					ItemUtils.item(XMaterial.FEATHER, LangExpansion.Stage_Statistic_Item_Stat.toString()), event -> {
						openStatisticGUI(event.getPlayer(), false);
			});
			getLine().setItem(SLOT_LIMIT,
					ItemUtils.item(XMaterial.REDSTONE, LangExpansion.Stage_Statistic_Item_Limit.toString()), event -> {
						openLimitEditor(event.getPlayer(), false);
			});
			getLine().setItem(SLOT_RELATIVE,
					ItemUtils.itemSwitch(LangExpansion.Stage_Statistic_Item_Relative.toString(), relative,
							QuestOption
									.formatDescription(LangExpansion.Stage_Statistic_Item_Relative_Description.toString())),
					event -> {
						relative = ItemUtils.toggleSwitch(event.getClicked());
					});
		}

		public void setStatistic(StatisticData statistic) {
			this.statistic = statistic;

			String dataName = statistic.dataName();
			getLine().refreshItemLoreOptionValue(SLOT_STAT, statistic.statistic().name() + (dataName == null ? "" : " (" + dataName + ")"));
		}

		public void setLimit(int limit) {
			this.limit = limit;
			getLine().refreshItemLoreOptionValue(SLOT_LIMIT, limit);
		}

		public void setRelative(boolean relative) {
			this.relative = relative;
			getLine().refreshItem(SLOT_RELATIVE, item -> ItemUtils.setSwitch(item, relative));
		}

		@Override
		public void start(Player p) {
			super.start(p);
			openStatisticGUI(p, true);
		}

		private void openMaterialStatisticEditor(Player player, Statistic statistic, boolean firstTime, Consumer<StatisticData> callback) {
			boolean isItem = statistic.getType() == Type.ITEM;
			QuestsPlugin.getPlugin().getEditorManager().getFactory().createTextEditorBuilderParser(player,
					QuestsPlugin.getPlugin().getEditorManager().getFactory().getMaterialParser(isItem, !isItem),
					firstTime ? context::removeAndReopenGui : context::reopenGui, material -> {
						callback.accept(new MaterialStatistic(statistic, material.get()));
					})
					.build().start();
		}

		private void openEntityStatisticEditor(Player player, Statistic statistic, boolean firstTime, Consumer<StatisticData> callback) {
			QuestsPlugin.getPlugin().getGuiManager().getFactory()
					.createEntityTypeSelection(entityType -> {
						callback.accept(new EntityStatistic(statistic, entityType));
					}, null).open(player);
		}

		private void openStatisticGUI(Player p, boolean firstTime) {
			new StaticPagedGUI<>(LangExpansion.Stage_Statistic_StatList_Gui_Name.toString(), DyeColor.LIGHT_BLUE,
					STATISTIC_ITEMS, stat -> {
						if (stat == null) {
							if (firstTime)
								context.removeAndReopenGui();
							else
								context.reopenGui();
						} else {
							Consumer<StatisticData> callback = statistic -> {
								setStatistic(statistic);
								if (firstTime)
									openLimitEditor(p, firstTime);
								else
									context.reopenGui();
							};
							switch (stat.getType()) {
								case BLOCK:
								case ITEM:
									openMaterialStatisticEditor(p, stat, firstTime, callback);
									break;
								case ENTITY:
									openEntityStatisticEditor(p, stat, firstTime, callback);
									break;
								case UNTYPED:
									callback.accept(new SimpleStatistic(stat));
									break;
							}
						}
					}).addSearchButton(Statistic::name, true).open(p);
		}

		private void openLimitEditor(Player p, boolean firstTime) {
			QuestsPlugin.getPlugin().getEditorManager().getFactory()
					.createTextEditorBuilderParser(p, NumberParser.INTEGER_PARSER_POSITIVE,
							firstTime ? context::removeAndReopenGui : context::reopenGui, value -> {
								setLimit(value);
								context.reopenGui();
							})
					.setIndication(LangExpansion.Stage_Statistic_EDITOR_LIMIT.toString())
					.setInitialValue(firstTime ? null : limit)
					.build().start();
		}

		@Override
		public void edit(StageStatistic stage) {
			super.edit(stage);
			setStatistic(stage.statistic);
			setLimit(stage.limit);
			setRelative(stage.relative);
		}

		@Override
		protected StageStatistic finishStage(StageController controller) {
			return new StageStatistic(controller, statistic, limit, comparison, relative);
		}

	}

}

sealed interface StatisticData {
	@NotNull
	Statistic statistic();

	@Nullable
	String dataName();

	int getPlayerStatistic(@NotNull Player player);
}

record SimpleStatistic(@NotNull Statistic statistic) implements StatisticData {
	@Override
	public @Nullable String dataName() {
		return null;
	}

	@Override
	public int getPlayerStatistic(@NotNull Player player) {
		return player.getStatistic(statistic);
	}
}

record EntityStatistic(@NotNull Statistic statistic, @NotNull EntityType entityType) implements StatisticData {
	@Override
	public @Nullable String dataName() {
		return entityType.name();
	}

	@Override
	public int getPlayerStatistic(@NotNull Player player) {
		return player.getStatistic(statistic, entityType);
	}
}

record MaterialStatistic(@NotNull Statistic statistic, @NotNull Material material) implements StatisticData {
	@Override
	public @Nullable String dataName() {
		return material.name();
	}

	@Override
	public int getPlayerStatistic(@NotNull Player player) {
		return player.getStatistic(statistic, material);
	}
}
