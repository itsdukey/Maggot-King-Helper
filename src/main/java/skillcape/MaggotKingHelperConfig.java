package skillcape;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(MaggotKingHelperConfig.GROUP)
public interface MaggotKingHelperConfig extends Config
{
	String GROUP = "maggotkinghelper";

	@ConfigItem(
		keyName = "hideDarkwoodTrees",
		name = "Hide Darkwood trees",
		description = "Hides Darkwood tree objects in the Maggot King arena."
	)
	default boolean hideDarkwoodTrees()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hideExitDoor",
		name = "Hide exit door",
		description = "Hides the quick-exit door in the Maggot King arena. The Exit option will still appear on hover."
	)
	default boolean hideExitDoor()
	{
		return false;
	}

	@ConfigItem(
		keyName = "hideExitDoorMenuOption",
		name = "Disable exit door left-click",
		description = "Stops the exit door from triggering on a left click in the Maggot King arena. " +
			"Still selectable from the right-click menu."
	)
	default boolean hideExitDoorMenuOption()
	{
		return false;
	}

	@ConfigItem(
		keyName = "hideCarrion",
		name = "Hide Carrion",
		description = "Hides the Carrion object in the Maggot King arena."
	)
	default boolean hideCarrion()
	{
		return true;
	}
}
