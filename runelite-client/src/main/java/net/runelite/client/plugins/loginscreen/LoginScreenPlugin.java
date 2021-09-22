/*
 * Copyright (c) 2017, Seth <Sethtroll3@gmail.com>
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
package net.runelite.client.plugins.loginscreen;

import com.google.common.base.Strings;
import com.google.inject.Provides;

import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.SpritePixels;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.SessionOpen;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.OSType;
import net.runelite.client.ui.ClientUI;

@PluginDescriptor(
	name = "Login Screen",
	description = "Provides various enhancements for login screen",
	tags = {"login", "password", "save", "autologin", "pass", "login Screen", "settings"}
)
@Slf4j
public class LoginScreenPlugin extends Plugin implements KeyListener
{
	private static final int MAX_USERNAME_LENGTH = 254;
	private static final String PASSWD_FILE = System.getProperty("user.home") + "/.runelite/.passwd";
	private static final int MAX_PIN_LENGTH = 6;
	private static final File CUSTOM_LOGIN_SCREEN_FILE = new File(RuneLite.RUNELITE_DIR, "login.png");

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private LoginScreenConfig config;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private LoginScreenMouseListener inputListener;

	private String usernameCache;

	public void passwordPopUp()
	{
		JFrame frame = new JFrame();
		JPanel panel = new JPanel();
		JButton button = new JButton("Save");
		frame.setTitle("Save Password");
		frame.setIconImage(ClientUI.ICON);
		JPasswordField textField = new JPasswordField("password");
		panel.add(textField);
		frame.setLocationRelativeTo(null);
		button.addActionListener(e ->
		{
			try
			{
				setPassword(String.valueOf(textField.getPassword()));
			}
			catch (IOException ioException)
			{
				ioException.printStackTrace();
			}
			frame.setVisible(false);
		});
		panel.add(button);
		frame.add(panel);
		frame.pack();
		frame.setVisible(true);
	}

	@Override
	protected void startUp() throws Exception
	{
		applyUsername();
		applyPassword();
		startEventListeners();
		clientThread.invoke(this::overrideLoginScreen);
	}

	private void startEventListeners()
	{
		keyManager.registerKeyListener(this);
		mouseManager.registerMouseListener(inputListener);
	}

	private void endEventListeners()
	{
		keyManager.unregisterKeyListener(this);
		mouseManager.unregisterMouseListener(inputListener);
	}

	private void setPassword(String password) throws IOException
	{
		BufferedWriter writer = new BufferedWriter(new FileWriter(PASSWD_FILE));
		writer.write(password);
		writer.close();
		applyPassword();
		log.warn("Saved password to local file. RuneLite Cloud settings will NOT save this file.");
	}

	private void removePassword()
	{
		File file = new File(PASSWD_FILE);
		file.delete();
		log.warn("Removed local password file.");
	}

	public void fillPassword()
	{
		applyPassword();
	}

	private String getPassword()
	{
		try
		{
			BufferedReader reader = new BufferedReader(new FileReader(PASSWD_FILE));
			return reader.readLine();
		}
		catch (IOException ioException)
		{
			return "";
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		if (config.syncUsername())
		{
			client.getPreferences().setRememberedUsername(usernameCache);
		}

		endEventListeners();

		clientThread.invoke(() ->
		{
			restoreLoginScreen();
			client.setShouldRenderLoginScreenFire(true);
		});
	}

	@Provides
	LoginScreenConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LoginScreenConfig.class);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event) throws IOException
	{
		if (event.getGroup().equals("loginscreen"))
		{
			clientThread.invoke(this::overrideLoginScreen);
		}

		if (event.getKey().equals("storePassword"))
		{
			if (event.getNewValue().equals("true"))
			{
				passwordPopUp();
			}
			else
			{
				removePassword();
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) throws IOException
	{
		if (!config.syncUsername())
		{
			return;
		}

		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			startEventListeners();
			applyUsername();
			applyPassword();
		}
		else if (event.getGameState() == GameState.LOGGED_IN)
		{
			String username = "";
			endEventListeners();

			if (client.getPreferences().getRememberedUsername() != null)
			{
				username = client.getUsername();
			}

			if (config.username().equals(username))
			{
				return;
			}

			log.debug("Saving username: {}", username);
			config.username(username);
		}
	}

	@Subscribe
	public void onSessionOpen(SessionOpen event) throws IOException
	{
		// configuation for the account is available now, so update the username
		applyUsername();
		applyPassword();
	}

	private void applyUsername()
	{
		if (!config.syncUsername() )
		{
			return;
		}

		GameState gameState = client.getGameState();
		if (gameState == GameState.LOGIN_SCREEN)
		{
			String username = config.username();

			if (Strings.isNullOrEmpty(username))
			{
				return;
			}

			// Save it only once
			if (usernameCache == null)
			{
				usernameCache = client.getPreferences().getRememberedUsername();
			}

			client.getPreferences().setRememberedUsername(username);
		}
	}

	private void applyPassword()
	{
		GameState gameState = client.getGameState();
		if (gameState == GameState.LOGIN_SCREEN && config.password())
		{
			if (getPassword() != "")
			{
				client.setPassword(getPassword());
			}
		}
	}

	@Override
	public boolean isEnabledOnLoginScreen()
	{
		return true;
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		if (!config.pasteEnabled() || (
			client.getGameState() != GameState.LOGIN_SCREEN &&
			client.getGameState() != GameState.LOGIN_SCREEN_AUTHENTICATOR))
		{
			return;
		}

		// enable pasting on macOS with the Command (meta) key
		boolean isModifierDown = OSType.getOSType() == OSType.MacOS ? e.isMetaDown() : e.isControlDown();

		if (e.getKeyCode() == KeyEvent.VK_V && isModifierDown)
		{
			try
			{
				final String data = Toolkit
					.getDefaultToolkit()
					.getSystemClipboard()
					.getData(DataFlavor.stringFlavor)
					.toString()
					.trim();

				switch (client.getLoginIndex())
				{
					// Username/password form
					case 2:
						if (client.getCurrentLoginField() == 0)
						{
							// Truncate data to maximum username length if necessary
							client.setUsername(data.substring(0, Math.min(data.length(), MAX_USERNAME_LENGTH)));
						}

						break;
					// Authenticator form
					case 4:
						// Truncate data to maximum OTP code length if necessary
						client.setOtp(data.substring(0, Math.min(data.length(), MAX_PIN_LENGTH)));
						break;
				}
			}
			catch (UnsupportedFlavorException | IOException ex)
			{
				log.warn("failed to fetch clipboard data", ex);
			}
		}
	}

	private void overrideLoginScreen()
	{
		client.setShouldRenderLoginScreenFire(config.showLoginFire());

		if (config.loginScreen() == LoginScreenOverride.OFF)
		{
			restoreLoginScreen();
			return;
		}

		SpritePixels pixels = null;
		if (config.loginScreen() == LoginScreenOverride.CUSTOM)
		{
			if (CUSTOM_LOGIN_SCREEN_FILE.exists())
			{
				try
				{
					BufferedImage image;
					synchronized (ImageIO.class)
					{
						image = ImageIO.read(CUSTOM_LOGIN_SCREEN_FILE);
					}

					if (image.getHeight() > Constants.GAME_FIXED_HEIGHT)
					{
						final double scalar = Constants.GAME_FIXED_HEIGHT / (double) image.getHeight();
						image = ImageUtil.resizeImage(image, (int) (image.getWidth() * scalar), Constants.GAME_FIXED_HEIGHT);
					}
					pixels = ImageUtil.getImageSpritePixels(image, client);
				}
				catch (IOException e)
				{
					log.error("error loading custom login screen", e);
					restoreLoginScreen();
					return;
				}
			}
		}
		else
		{
			pixels = getFileSpritePixels(config.loginScreen().getFileName());
		}

		if (pixels != null)
		{
			client.setLoginScreen(pixels);
		}
	}

	private void restoreLoginScreen()
	{
		client.setLoginScreen(null);
	}

	private SpritePixels getFileSpritePixels(String file)
	{
		try
		{
			log.debug("Loading: {}", file);
			BufferedImage image = ImageUtil.loadImageResource(this.getClass(), file);
			return ImageUtil.getImageSpritePixels(image, client);
		}
		catch (RuntimeException ex)
		{
			log.debug("Unable to load image: ", ex);
		}

		return null;
	}
}