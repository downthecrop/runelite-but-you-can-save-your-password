/*
 * Copyright (c) 2016-2017, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Paths;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Locale;
import javax.annotation.Nullable;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.swing.SwingUtilities;
import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.ValueConversionException;
import joptsimple.ValueConverter;
import joptsimple.util.EnumConverter;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.account.SessionManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.discord.DiscordService;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.rs.ClientLoader;
import net.runelite.client.rs.ClientUpdateCheckMode;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.FatalErrorDialog;
import net.runelite.client.ui.SplashScreen;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.WidgetOverlay;
import net.runelite.client.ui.overlay.tooltip.TooltipOverlay;
import net.runelite.client.ui.overlay.worldmap.WorldMapOverlay;
import net.runelite.http.api.RuneLiteAPI;
import okhttp3.Cache;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.slf4j.LoggerFactory;

@Singleton
@Slf4j
public class RuneLite
{
	public static final File RUNELITE_DIR = new File(System.getProperty("user.home"), ".runelite");
	public static final File CACHE_DIR = new File(RUNELITE_DIR, "cache");
	public static final File PLUGINS_DIR = new File(RUNELITE_DIR, "plugins");
	public static final File PROFILES_DIR = new File(RUNELITE_DIR, "profiles");
	public static final File SCREENSHOT_DIR = new File(RUNELITE_DIR, "screenshots");
	public static final File LOGS_DIR = new File(RUNELITE_DIR, "logs");
	public static final File DEFAULT_SESSION_FILE = new File(RUNELITE_DIR, "session");
	public static final File DEFAULT_CONFIG_FILE = new File(RUNELITE_DIR, "settings.properties");

	private static final int MAX_OKHTTP_CACHE_SIZE = 20 * 1024 * 1024; // 20mb

	@Getter
	private static Injector injector;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private ExternalPluginManager externalPluginManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private ConfigManager configManager;

	@Inject
	private SessionManager sessionManager;

	@Inject
	private DiscordService discordService;

	@Inject
	private ClientSessionManager clientSessionManager;

	@Inject
	private ClientUI clientUI;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private Provider<TooltipOverlay> tooltipOverlay;

	@Inject
	private Provider<WorldMapOverlay> worldMapOverlay;

	@Inject
	@Nullable
	private Client client;

	public static void main(String[] args) throws Exception
	{
		Locale.setDefault(Locale.ENGLISH);

		final OptionParser parser = new OptionParser();
		parser.accepts("developer-mode", "Enable developer tools");
		parser.accepts("debug", "Show extra debugging output");
		parser.accepts("safe-mode", "Disables external plugins and the GPU plugin");
		parser.accepts("insecure-skip-tls-verification", "Disables TLS verification");
		parser.accepts("jav_config", "jav_config url")
			.withRequiredArg()
			.defaultsTo(RuneLiteProperties.getJavConfig());

		final ArgumentAcceptingOptionSpec<File> sessionfile = parser.accepts("sessionfile", "Use a specified session file")
			.withRequiredArg()
			.withValuesConvertedBy(new ConfigFileConverter())
			.defaultsTo(DEFAULT_SESSION_FILE);

		final ArgumentAcceptingOptionSpec<File> configfile = parser.accepts("config", "Use a specified config file")
			.withRequiredArg()
			.withValuesConvertedBy(new ConfigFileConverter())
			.defaultsTo(DEFAULT_CONFIG_FILE);

		final ArgumentAcceptingOptionSpec<ClientUpdateCheckMode> updateMode = parser
			.accepts("rs", "Select client type")
			.withRequiredArg()
			.ofType(ClientUpdateCheckMode.class)
			.defaultsTo(ClientUpdateCheckMode.AUTO)
			.withValuesConvertedBy(new EnumConverter<ClientUpdateCheckMode>(ClientUpdateCheckMode.class)
			{
				@Override
				public ClientUpdateCheckMode convert(String v)
				{
					return super.convert(v.toUpperCase());
				}
			});

		parser.accepts("help", "Show this text").forHelp();
		OptionSet options = parser.parse(args);

		if (options.has("help"))
		{
			parser.printHelpOn(System.out);
			System.exit(0);
		}

		if (options.has("debug"))
		{
			final Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
			logger.setLevel(Level.DEBUG);
		}

		Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
		{
			log.error("Uncaught exception:", throwable);
			if (throwable instanceof AbstractMethodError)
			{
				log.error("Classes are out of date; Build with maven again.");
			}
		});

		OkHttpClient.Builder okHttpClientBuilder = RuneLiteAPI.CLIENT.newBuilder();
		setupCache(okHttpClientBuilder, new File(CACHE_DIR, "okhttp"));

		final boolean insecureSkipTlsVerification = options.has("insecure-skip-tls-verification");
		if (insecureSkipTlsVerification || RuneLiteProperties.isInsecureSkipTlsVerification())
		{
			setupInsecureTrustManager(okHttpClientBuilder);
		}

		final OkHttpClient okHttpClient = okHttpClientBuilder.build();

		SplashScreen.init();
		SplashScreen.stage(0, "Retrieving client", "");

		try
		{
			final ClientLoader clientLoader = new ClientLoader(okHttpClient, options.valueOf(updateMode), (String) options.valueOf("jav_config"));

			new Thread(() ->
			{
				clientLoader.get();
				ClassPreloader.preload();
			}, "Preloader").start();

			final boolean developerMode = options.has("developer-mode") && RuneLiteProperties.getLauncherVersion() == null;

			if (developerMode)
			{
				boolean assertions = false;
				assert assertions = true;
				if (!assertions)
				{
					SwingUtilities.invokeLater(() ->
						new FatalErrorDialog("Developers should enable assertions; Add `-ea` to your JVM arguments`")
							.addBuildingGuide()
							.open());
					return;
				}
			}

			PROFILES_DIR.mkdirs();

			log.info("RuneLite {} (launcher version {}) starting up, args: {}",
				RuneLiteProperties.getVersion(), RuneLiteProperties.getLauncherVersion() == null ? "unknown" : RuneLiteProperties.getLauncherVersion(),
				args.length == 0 ? "none" : String.join(" ", args));

			final long start = System.currentTimeMillis();

			injector = Guice.createInjector(new RuneLiteModule(
				okHttpClient,
				clientLoader,
				developerMode,
				options.has("safe-mode"),
				options.valueOf(sessionfile),
				options.valueOf(configfile)));

			injector.getInstance(RuneLite.class).start();

			final long end = System.currentTimeMillis();
			final RuntimeMXBean rb = ManagementFactory.getRuntimeMXBean();
			final long uptime = rb.getUptime();
			log.info("Client initialization took {}ms. Uptime: {}ms", end - start, uptime);
		}
		catch (Exception e)
		{
			log.error("Failure during startup", e);
			SwingUtilities.invokeLater(() ->
				new FatalErrorDialog("RuneLite has encountered an unexpected error during startup.")
					.open());
		}
		finally
		{
			SplashScreen.stop();
		}
	}

	public void start() throws Exception
	{
		// Load RuneLite or Vanilla client
		final boolean isOutdated = client == null;

		if (!isOutdated)
		{
			// Inject members into client
			injector.injectMembers(client);
		}

		SplashScreen.stage(.57, null, "Loading configuration");

		// Load user configuration
		configManager.load();

		// Load the session, including saved configuration
		sessionManager.loadSession();

		// Tell the plugin manager if client is outdated or not
		pluginManager.setOutdated(isOutdated);

		// Load the plugins, but does not start them yet.
		// This will initialize configuration
		pluginManager.loadCorePlugins();
		externalPluginManager.loadExternalPlugins();

		SplashScreen.stage(.70, null, "Finalizing configuration");

		// Plugins have provided their config, so set default config
		// to main settings
		pluginManager.loadDefaultPluginConfiguration(null);

		// Start client session
		clientSessionManager.start();
		eventBus.register(clientSessionManager);

		SplashScreen.stage(.75, null, "Starting core interface");

		// Initialize UI
		clientUI.init();

		// Initialize Discord service
		discordService.init();

		// Register event listeners
		eventBus.register(clientUI);
		eventBus.register(pluginManager);
		eventBus.register(externalPluginManager);
		eventBus.register(overlayManager);
		eventBus.register(configManager);
		eventBus.register(discordService);

		if (!isOutdated)
		{
			// Add core overlays
			WidgetOverlay.createOverlays(overlayManager, client).forEach(overlayManager::add);
			overlayManager.add(worldMapOverlay.get());
			overlayManager.add(tooltipOverlay.get());
		}

		// Start plugins
		pluginManager.startPlugins();

		SplashScreen.stop();

		clientUI.show();
	}

	@VisibleForTesting
	public static void setInjector(Injector injector)
	{
		RuneLite.injector = injector;
	}

	private static class ConfigFileConverter implements ValueConverter<File>
	{
		@Override
		public File convert(String fileName)
		{
			final File file;

			if (Paths.get(fileName).isAbsolute()
				|| fileName.startsWith("./")
				|| fileName.startsWith(".\\"))
			{
				file = new File(fileName);
			}
			else
			{
				file = new File(RuneLite.RUNELITE_DIR, fileName);
			}

			if (file.exists() && (!file.isFile() || !file.canWrite()))
			{
				throw new ValueConversionException(String.format("File %s is not accessible", file.getAbsolutePath()));
			}

			return file;
		}

		@Override
		public Class<? extends File> valueType()
		{
			return File.class;
		}

		@Override
		public String valuePattern()
		{
			return null;
		}
	}

	@VisibleForTesting
	static void setupCache(OkHttpClient.Builder builder, File cacheDir)
	{
		builder.cache(new Cache(cacheDir, MAX_OKHTTP_CACHE_SIZE))
			.addNetworkInterceptor(chain ->
			{
				// This has to be a network interceptor so it gets hit before the cache tries to store stuff
				Response res = chain.proceed(chain.request());
				if (res.code() >= 400 && "GET".equals(res.request().method()))
				{
					// if the request 404'd we don't want to cache it because its probably temporary
					res = res.newBuilder()
						.header("Cache-Control", "no-store")
						.build();
				}
				return res;
			});
	}

	private static void setupInsecureTrustManager(OkHttpClient.Builder okHttpClientBuilder)
	{
		try
		{
			X509TrustManager trustManager = new X509TrustManager()
			{
				@Override
				public void checkClientTrusted(X509Certificate[] chain, String authType)
				{
				}

				@Override
				public void checkServerTrusted(X509Certificate[] chain, String authType)
				{
				}

				@Override
				public X509Certificate[] getAcceptedIssuers()
				{
					return new X509Certificate[0];
				}
			};

			SSLContext sc = SSLContext.getInstance("SSL");
			sc.init(null, new TrustManager[]{trustManager}, new SecureRandom());
			okHttpClientBuilder.sslSocketFactory(sc.getSocketFactory(), trustManager);
		}
		catch (NoSuchAlgorithmException | KeyManagementException ex)
		{
			log.warn("unable to setup insecure trust manager", ex);
		}
	}
}
