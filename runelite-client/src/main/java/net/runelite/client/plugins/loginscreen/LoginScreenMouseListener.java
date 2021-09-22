package net.runelite.client.plugins.loginscreen;

import net.runelite.client.input.MouseListener;
import javax.inject.Inject;
import java.awt.event.MouseEvent;

public class LoginScreenMouseListener implements MouseListener
{
	private final LoginScreenPlugin plugin;

	@Inject
	private LoginScreenMouseListener(LoginScreenPlugin plugin)
	{
		this.plugin = plugin;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent mouseEvent)
	{
		plugin.fillPassword();
		return mouseEvent;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseExited(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}
}