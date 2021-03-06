/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 games647 and contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.github.games647.flexiblelogin;

import com.github.games647.flexiblelogin.commands.ChangePasswordCommand;
import com.github.games647.flexiblelogin.commands.LoginCommand;
import com.github.games647.flexiblelogin.commands.LogoutCommand;
import com.github.games647.flexiblelogin.commands.RegisterCommand;
import com.github.games647.flexiblelogin.commands.ResetPasswordCommand;
import com.github.games647.flexiblelogin.commands.SetEmailCommand;
import com.github.games647.flexiblelogin.commands.UnregisterCommand;
import com.github.games647.flexiblelogin.config.Settings;
import com.github.games647.flexiblelogin.hasher.BcryptHasher;
import com.github.games647.flexiblelogin.hasher.Hasher;
import com.github.games647.flexiblelogin.hasher.TOTP;
import com.github.games647.flexiblelogin.listener.ConnectionListener;
import com.github.games647.flexiblelogin.listener.PreventListener;
import com.google.common.collect.Maps;
import com.google.inject.Inject;

import java.io.File;
import java.util.Map;

import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.loader.ConfigurationLoader;

import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.spongepowered.api.Game;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandManager;
import org.spongepowered.api.command.args.GenericArguments;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.config.DefaultConfig;
import org.spongepowered.api.event.EventListener;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.game.state.GameInitializationEvent;
import org.spongepowered.api.event.game.state.GamePreInitializationEvent;
import org.spongepowered.api.event.game.state.GameStoppedServerEvent;
import org.spongepowered.api.network.PlayerConnection;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.text.Text;

@Plugin(id = "flexiblelogin", name = "FlexibleLogin", version = "0.8", description = "A Sponge minecraft server plugin for second authentication.")
public class FlexibleLogin {

	private static FlexibleLogin instance;

	public static FlexibleLogin getInstance() {
		return instance;
	}

	private final PluginContainer pluginContainer;
	private final Logger logger;
	private final Game game;

	@Inject
	@DefaultConfig(sharedRoot = false)
	// We will place more than one config there (i.e. H2/SQLite database)
	private File defaultConfigFile;

	@Inject
	@DefaultConfig(sharedRoot = false)
	private ConfigurationLoader<CommentedConfigurationNode> configManager;

	private Map<PlayerConnection, Integer> attempts = Maps.newConcurrentMap();

	private Settings configuration;
	private Database database;
	private ProtectionManager protectionManager;

	private Hasher hasher;

	@Inject
	public FlexibleLogin(Logger logger, PluginContainer pluginContainer, Game game) {
		instance = this;

		this.logger = logger;
		this.pluginContainer = pluginContainer;
		this.game = game;
	}

	@Listener // During this state, the plugin gets ready for initialization.
				// Logger and config
	public void onPreInit(GamePreInitializationEvent preInitEvent) {
		configuration = new Settings(configManager, defaultConfigFile);
		configuration.load();

		database = new Database();
		database.createTable();

		protectionManager = new ProtectionManager();

		if (configuration.getConfig().getHashAlgo().equalsIgnoreCase("totp")) {
			hasher = new TOTP();
		} else {
			// use bcrypt as fallback for now
			logger.info("BcryptHasher");
			hasher = new BcryptHasher();
		}
	}

	@Listener // Commands register + events
	public void onInit(GameInitializationEvent initEvent) {
		// register commands
		logger.info("Hm.....");
		CommandManager commandDispatcher = game.getCommandManager();

		commandDispatcher.register(this,
				CommandSpec.builder().executor(new LoginCommand())
						.arguments(GenericArguments.onlyOne(GenericArguments.string(Text.of("password")))).build(),
				"login");

		commandDispatcher.register(this,
				CommandSpec.builder().executor(new RegisterCommand())
						.arguments(GenericArguments
								.optional(GenericArguments.repeated(GenericArguments.string(Text.of("password")), 2)))
				.build(), "register");

		commandDispatcher.register(this,
				CommandSpec.builder().executor(new ChangePasswordCommand())
						.arguments(GenericArguments.repeated(GenericArguments.string(Text.of("password")), 2)).build(),
				"changepassword", "changepw");

		commandDispatcher.register(this, CommandSpec.builder().executor(new LogoutCommand()).build(), "logout");

		commandDispatcher.register(this,
				CommandSpec.builder().executor(new UnregisterCommand())
						.arguments(GenericArguments.onlyOne(GenericArguments.string(Text.of("account"))))
						.permission(pluginContainer.getName() + ".admin").build(),
				"unregister");

		commandDispatcher.register(this,
				CommandSpec.builder().executor(new ResetPasswordCommand())
						.arguments(GenericArguments.onlyOne(GenericArguments.string(Text.of("account"))),
								GenericArguments.string(Text.of("password")))
				.permission(pluginContainer.getName() + ".admin").build(), "resetpassword", "resetpw");

		commandDispatcher.register(this,
				CommandSpec.builder().executor(new SetEmailCommand())
						.arguments(GenericArguments.onlyOne(GenericArguments.string(Text.of("email")))).build(),
				"setemail", "email");

		commandDispatcher.register(this, CommandSpec.builder().executor(new UnregisterCommand()).build(),
				"forgotpassword", "forgot");

		// register events
		game.getEventManager().registerListeners(this, new ConnectionListener());
		game.getEventManager().registerListeners(this, new PreventListener());
	}

	@Listener
	public void onDisable(GameStoppedServerEvent gameStoppedEvent) {
		// run this task sync in order let it finish before the process ends
		database.close();

		game.getServer().getOnlinePlayers().stream().forEach(protectionManager::unprotect);
	}

	public Settings getConfigManager() {
		return configuration;
	}

	public PluginContainer getContainer() {
		return pluginContainer;
	}

	public Logger getLogger() {
		return logger;
	}

	public Game getGame() {
		return game;
	}

	public Database getDatabase() {
		return database;
	}

	public Map<PlayerConnection, Integer> getAttempts() {
		return attempts;
	}

	public ProtectionManager getProtectionManager() {
		return protectionManager;
	}

	public Hasher getHasher() {
		// this is thread-safe because it won't change after config load
		return hasher;
	}
}
