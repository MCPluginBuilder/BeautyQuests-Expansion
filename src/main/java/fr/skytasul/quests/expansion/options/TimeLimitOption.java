package fr.skytasul.quests.expansion.options;

import com.cryptomorin.xseries.XMaterial;

import fr.skytasul.quests.api.QuestsPlugin;
import fr.skytasul.quests.api.editors.parsers.DurationParser.MinecraftTimeUnit;
import fr.skytasul.quests.api.gui.ItemUtils;
import fr.skytasul.quests.api.options.OptionSet;
import fr.skytasul.quests.api.options.QuestOption;
import fr.skytasul.quests.api.options.description.QuestDescriptionContext;
import fr.skytasul.quests.api.options.description.QuestDescriptionProvider;
import fr.skytasul.quests.api.questers.Quester;
import fr.skytasul.quests.api.questers.data.QuesterQuestData;
import fr.skytasul.quests.api.questers.events.QuesterJoinEvent;
import fr.skytasul.quests.api.questers.events.QuesterLeaveEvent;
import fr.skytasul.quests.api.quests.Quest;
import fr.skytasul.quests.api.quests.creation.QuestCreationGuiClickEvent;
import fr.skytasul.quests.api.quests.events.questers.QuesterQuestFinishEvent;
import fr.skytasul.quests.api.quests.events.questers.QuesterQuestLaunchEvent;
import fr.skytasul.quests.api.quests.events.questers.QuesterQuestResetEvent;
import fr.skytasul.quests.api.utils.PlayerListCategory;
import fr.skytasul.quests.api.utils.Utils;
import fr.skytasul.quests.expansion.BeautyQuestsExpansion;
import fr.skytasul.quests.expansion.utils.LangExpansion;
import fr.skytasul.quests.utils.QuestUtils;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.*;

public class TimeLimitOption extends QuestOption<Integer> implements Listener, QuestDescriptionProvider {

	private Map<Quester, BukkitTask> tasks;

	@Override
	public Object save() {
		return getValue();
	}

	@Override
	public void load(ConfigurationSection config, String key) {
		setValue(config.getInt(key));
	}

	@Override
	public Integer cloneValue(Integer value) {
		return value;
	}

	@Override
	public ItemStack getItemStack(OptionSet options) {
		return ItemUtils.item(XMaterial.CLOCK, "§d" + LangExpansion.TimeLimit_Name.toString(), getLore());
	}

	@Override
	public @Nullable String getValueString() {
		return Utils.millisToHumanString(getValue() * 1000L);
	}

	private String[] getLore() {
		return new String[] {formatDescription(LangExpansion.TimeLimit_Description.toString()), "", formatValue(), "",
				LangExpansion.Expansion_Label.toString()};
	}

	@Override
	public void click(@NotNull QuestCreationGuiClickEvent event) {
		QuestsPlugin.getPlugin().getEditorManager().getFactory().createTextEditorBuilderParser(event.getPlayer(),
				MinecraftTimeUnit.SECOND.getParser(), event::reopen, time -> {
					setValue(time.intValue());
					event.reopen();
				}).addReset(() -> {
					resetValue();
					event.reopen();
				}, "null")
				.setInitialValue(getValue().longValue())
				.setIndication(LangExpansion.TimeLimit_EDITOR.toString())
				.build().start();
	}

	@Override
	public void attach(Quest quest) {
		super.attach(quest);

		tasks = new HashMap<>();
	}

	@Override
	public Quest detach() {
		if (tasks != null) {
			tasks.forEach((acc, task) -> task.cancel());
			tasks = null;
		}

		return super.detach();
	}

	private OptionalLong getRemainingTime(@NotNull Quester quester) {
		Optional<QuesterQuestData> data = quester.getDataHolder().getQuestDataIfPresent(getAttachedQuest());
		if (data.isEmpty()) {
			BeautyQuestsExpansion.logger.warning("Cannot find data of {0} for quest {1}",
					quester.getDetailedName(), getAttachedQuest().getId());
			return OptionalLong.empty();
		}
		OptionalLong startingTime = data.get().getStartingTime();
		if (startingTime.isEmpty())
			return OptionalLong.empty(); // outdated datas

		return OptionalLong.of(startingTime.getAsLong() + getValue() * 1000 - System.currentTimeMillis());
	}

	private void startTask(Quester quester) {
		if (tasks.containsKey(quester))
			return;

		OptionalLong timeToWait = getRemainingTime(quester);
		if (timeToWait.isEmpty()) {
			return;
		} else if (timeToWait.getAsLong() <= 0) {
			QuestUtils.runSync(() -> getAttachedQuest().cancelQuester(quester));
		}else {
			tasks.put(quester, Bukkit.getScheduler().runTaskLater(BeautyQuestsExpansion.getInstance(),
					() -> {
						tasks.remove(quester);
						getAttachedQuest().cancelQuester(quester);
					}, timeToWait.getAsLong() / 50));
		}
	}

	private void cancelTask(Quester quester) {
		BukkitTask task = tasks.remove(quester);
		if (task != null)
			task.cancel();
	}

	@EventHandler (priority = EventPriority.HIGHEST)
	public void onAccountJoin(QuesterJoinEvent event) {
		if (getAttachedQuest().hasStarted(event.getQuester()))
			startTask(event.getQuester());
	}

	@EventHandler
	public void onQuestStart(QuesterQuestLaunchEvent event) {
		if (event.getQuest() == getAttachedQuest())
			startTask(event.getQuester());
	}

	@EventHandler
	public void onAccountLeave(QuesterLeaveEvent event) {
		if (event.isLastOnlinePlayer())
			cancelTask(event.getQuester());
	}

	@EventHandler
	public void onQuestFinish(QuesterQuestFinishEvent event) {
		if (event.getQuest() == getAttachedQuest())
			cancelTask(event.getQuester());
	}

	@EventHandler
	public void onQuestCancel(QuesterQuestResetEvent event) {
		if (event.getQuest() == getAttachedQuest())
			cancelTask(event.getQuester());
	}

	@Override
	public List<String> provideDescription(QuestDescriptionContext context) {
		if (context.getCategory() != PlayerListCategory.IN_PROGRESS)
			return null;

		OptionalLong timeToWait = getRemainingTime(context.getQuester());
		if (timeToWait.isEmpty())
			return null;

		return Arrays.asList(
				LangExpansion.TimeLimit_Left.quickFormat("time_left", Utils.millisToHumanString(timeToWait.getAsLong())));
	}

	@Override
	public String getDescriptionId() {
		return "time_left";
	}

	@Override
	public double getDescriptionPriority() {
		return 50;
	}

}
