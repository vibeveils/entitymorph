package io.github.sharewarehud;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Only loaded when Mod Menu is installed; adds the "Configure" button for this mod. */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return SharewareConfigScreen::new;
	}
}
