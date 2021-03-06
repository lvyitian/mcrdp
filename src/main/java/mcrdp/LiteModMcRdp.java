package mcrdp;

import static org.lwjgl.opengl.GL11.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

import net.minecraft.block.BlockWallSign;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mumfrey.liteloader.LiteMod;
import com.mumfrey.liteloader.PacketHandler;
import com.mumfrey.liteloader.PlayerClickListener;
import com.mumfrey.liteloader.PlayerInteractionListener.MouseButton;
import com.mumfrey.liteloader.PreRenderListener;
import com.mumfrey.liteloader.Tickable;

public class LiteModMcRdp implements LiteMod, PlayerClickListener, PacketHandler, PreRenderListener, Tickable {
	private final Minecraft minecraft = Minecraft.getMinecraft();

	@Override
	public String getName() {
		return "mcrdp";
	}

	@Override
	public String getVersion() {
		return "0.1";
	}

	@Override
	public void init(File configPath) {
		instances.put("pi", RDPInstance.create("pi", "pi", "", 800, 600));
	}

	@Override
	public void upgradeSettings(String version, File configPath,
			File oldConfigPath) {

	}

	@Override
	public List<Class<? extends Packet<?>>> getHandledPackets() {
		List<Class<? extends Packet<?>>> packets = new ArrayList<>();
		packets.add(SPacketChunkData.class);
		packets.add(SPacketUpdateTileEntity.class);
		return packets;
	}

	/**
	 * Locations of newly added/updated sign block entities
	 */
	private final List<BlockPos> newSigns = new ArrayList<>();

	@Override
	public boolean handlePacket(INetHandler netHandler, Packet<?> packet) {
		if (packet instanceof SPacketChunkData) {
			SPacketChunkData cpacket = (SPacketChunkData) packet;
			for (NBTTagCompound tag : cpacket.getTileEntityTags()) {
				if (tag.getString("id").toLowerCase().contains("sign")) {
					newSigns.add(new BlockPos(tag.getInteger("x"), tag.getInteger("y"), tag.getInteger("z")));
				}
			}
		} else if (packet instanceof SPacketUpdateTileEntity) {
			SPacketUpdateTileEntity cpacket = (SPacketUpdateTileEntity) packet;
			if (cpacket.getTileEntityType() == 9) {
				newSigns.add(cpacket.getPos());
			}
		}
		return true;
	}

	@Override
	public void onTick(Minecraft minecraft, float partialTicks, boolean inGame, boolean clock) {
		if (!clock) {
			return;
		}
		this.minecraft.mcProfiler.startSection("unloadCheck");
		if (minecraft.world == null && !infos.isEmpty()) {
			// Unload world check
			minecraft.ingameGUI.getChatGUI().printChatMessage(new TextComponentString("Removed all " + this.infos.size() + " infos"));
			infos.clear();
			this.minecraft.mcProfiler.endSection(); // unloadCheck
			return;
		}
		this.minecraft.mcProfiler.endStartSection("blockCheck");
		{
			// Unloaded info check
			int oldSize = this.infos.size();
			boolean removedAny =
					this.infos.removeIf(info -> minecraft.world.getBlockState(info.pos).getBlock() != Blocks.WALL_SIGN);
			int newSize = this.infos.size();
	
			if (removedAny) {
				minecraft.ingameGUI.getChatGUI().printChatMessage(new TextComponentString("Removed " + (oldSize - newSize) + " unloaded infos"));
			}
		}
		this.minecraft.mcProfiler.endStartSection("newSigns");
		newSigns.forEach(this::handleNewTE);
		newSigns.clear();
	}

	/**
	 * Contains information about an RDP sign.
	 */
	public class RDPInfo {
		public RDPInfo(BlockPos pos, EnumFacing facing, String[] lines) throws InvalidRDPException {
			if (!lines[0].contains("mcrdp")) {
				throw new InvalidRDPException(pos, "First line '" + lines[0] + "' doesn't contain 'mcrdp'");
			}
			if (lines[1].matches("\\d+x\\d+")) {
				String[] part = lines[1].split("x");
				width = Integer.parseInt(part[0]);
				height = Integer.parseInt(part[1]);
			} else if (lines[1].isEmpty()) {
				width = 8;
				height = 6;
			} else {
				throw new InvalidRDPException(pos, "Second line '" + lines[1] + "' should be empty or [width]x[height]");
			}
			if (instances.containsKey(lines[3])) {
				server = lines[3];
			} else {
				throw new InvalidRDPException(pos, "Fourth line '" + lines[3] + "' does not contain a known RDP server");
			}
			this.pos = pos;
			this.facing = facing;
			double offX = 0;
			double offZ = 0;
			switch (facing) {
			case NORTH: offX = 1; offZ = 1 - Z_PUSH; break;
			case SOUTH: offZ = Z_PUSH; break;
			case EAST: offX = Z_PUSH; offZ = 1; break;
			case WEST: offX = 1 - Z_PUSH; break;
			default: break;
			}
			this.posVector = new Vec3d(pos.getX() + offX, pos.getY(), pos.getZ() + offZ);
		}

		public final BlockPos pos;
		public final Vec3d posVector;
		public final int width, height; // in blocks
		public final String server;
		public final EnumFacing facing;

		public boolean isLookedAt(Entity entity) {
			Vec3d look = calcLookVector(entity);
			if (look != null) {
				double h = getLookedH(look);
				double v = getLookedV(look);
				return (h >= 0 && h < width) && (v >= 0 && v < height);
			} else {
				return false;
			}
		}

		/**
		 * Push to avoid z-fighting: slightly larger than a sign.
		 * (this will not always be in the Z direction)
		 */
		private static final float Z_PUSH = 2/16f;
		/** Max look distance */
		private static final int LOOK_DISTANCE = 64;

		/**
		 * Calculates the given entity's look position along the plane of this info
		 * @return
		 */
		@Nullable
		public Vec3d calcLookVector(Entity entity) {
			Vec3d start = entity.getPositionEyes(0); // TODO partialTicks
			Vec3d end = entity.getLookVec() // TODO partialTicks
					.normalize().scale(LOOK_DISTANCE).add(start);
			switch (facing.getAxis()) {
			case X: return start.getIntermediateWithXValue(end, posVector.x);
			case Y: return start.getIntermediateWithYValue(end, posVector.y);
			case Z: return start.getIntermediateWithZValue(end, posVector.z);
			default: throw new AssertionError();
			}
		}

		/** Gets the block that is being looked at horizontally */
		public double getLookedH(Vec3d look) {
			switch (facing.getAxis()) {
			case X: return (look.z - posVector.z) * -facing.getAxisDirection().getOffset();
			case Y: return look.x - posVector.x;
			case Z: return (look.x - posVector.x) * facing.getAxisDirection().getOffset();
			default: throw new AssertionError();
			}
		}
		/** Gets the block that is being looked at vertically */
		public double getLookedV(Vec3d look) {
			switch (facing.getAxis()) {
			case X: return look.y - posVector.y;
			case Y: return look.z - posVector.z;
			case Z: return look.y - posVector.y;
			default: throw new AssertionError();
			}
		}

		/**
		 * DEBUG: draw the position that's being looked at
		 */
		public void drawLookPosition(Entity entity) {
			Vec3d look = calcLookVector(entity);
			if (look != null) {
				RenderGlobal.renderFilledBox(look.x - .3, look.y - .3, look.z - .3, look.x + .3, look.y + .3, look.z + .3, 1, 0, 0, 1);
			}
		}
	}

	private Map<String, RDPInstance> instances = Maps.newHashMap();
	private List<RDPInfo> infos = Lists.newArrayList();

	private void handleNewTE(BlockPos pos) {
		IBlockState state = minecraft.world.getBlockState(pos);
		if (state.getBlock() != Blocks.WALL_SIGN) {
			return;
		}
		TileEntitySign sign = (TileEntitySign) minecraft.world.getTileEntity(pos);
		if (sign == null) {
			return;
		}

		String[] lines = new String[4];
		for (int i = 0; i < 4; i++) {
			lines[i] = sign.signText[i].getUnformattedText();
		}
		if (!lines[0].contains("mcrdp")) {
			// Nothing at all that can be wrong.
			return;
		}
		try {
			infos.add(new RDPInfo(pos, state.getValue(BlockWallSign.FACING), lines));
			minecraft.ingameGUI.getChatGUI().printChatMessage(new TextComponentString("Test"));
		} catch (InvalidRDPException ex) {
			ITextComponent component = new TextComponentString(ex.getMessage());
			component.getStyle().setColor(TextFormatting.RED);
			minecraft.ingameGUI.getChatGUI().printChatMessage(component);
		}
	}

	@Override
	public boolean onMouseClicked(EntityPlayerSP player, MouseButton button) {
		// TODO: sort by distance maybe
		Optional<RDPInfo> optInfo = infos.stream()
				.filter(i -> i.isLookedAt(player)).findAny();
		if (optInfo.isPresent()) {
			RDPInfo info = optInfo.get();
			RDPInstance instance = instances.get(info.server);
			if (!player.isSneaking()) {
				Vec3d vec = info.calcLookVector(player);
				assert vec != null : "Unexpected null look vector for looked at info";
				int mouseX = (int) ((info.getLookedH(vec) / info.width) * instance.width);
				int mouseY = instance.height - (int) ((info.getLookedV(vec) / info.height) * instance.height) - 1;
				// Attempt to click the mouse (doesn't work for unknown reasons)
				instance.input.moveMouse(mouseX, mouseY);
				instance.input.mouseButton(button.ordinal() + 1, true, mouseX, mouseY);
				instance.input.mouseButton(button.ordinal() + 1, false, mouseX, mouseY);
			} else {
				minecraft.displayGuiScreen(new GuiRDPControl(instance));
			}
			return false;
		} else {
			return true;
		}
	}

	@Override
	public boolean onMouseHeld(EntityPlayerSP player, MouseButton button) {
		return true;
	}

	@Override
	public void onSetupCameraTransform(float partialTicks, int pass,
			long timeSlice) {
		minecraft.mcProfiler.startSection("mcrdp.display");
		EntityPlayerSP player = minecraft.player;
		double x = player.lastTickPosX + (player.posX - player.lastTickPosX) * (double)partialTicks;
		double y = player.lastTickPosY + (player.posY - player.lastTickPosY) * (double)partialTicks;
		double z = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * (double)partialTicks;

		glPushMatrix();
		glTranslated(-x, -y, -z);

		for (RDPInstance instance : instances.values()) {
			glBindTexture(GL_TEXTURE_2D, instance.glId);

			if (instance.canvas == null) {
				if (!this.infos.isEmpty()) {
					int oldSize = this.infos.size();
					boolean removedAny =
							this.infos.removeIf(info -> info.server.equals(instance.server));
					int newSize = this.infos.size();
					if (removedAny) {
						minecraft.ingameGUI.getChatGUI()
								.printChatMessage(
										new TextComponentString("Purged "
												+ (oldSize - newSize)
												+ " entries for dead instance "
												+ instance.server));
					}
				}
				continue;
			}

			infos.stream().forEach(this::drawInfo);
		}

		glPopMatrix();
		minecraft.mcProfiler.endSection();
	}

	/**
	 * Draws a RDP info, assuming the texture already is bound.
	 */
	private void drawInfo(RDPInfo info) {
		try {
			glPushMatrix();
			glTranslated(info.posVector.x, info.posVector.y, info.posVector.z);
			switch (info.facing) {
			case NORTH:
				glRotatef(180, 0, 1, 0);
				break;
			case EAST:
				glRotatef(90, 0, 1, 0);
				break;
			case SOUTH:
				// Noop
				glRotatef(0, 0, 1, 0);
				break;
			case WEST:
				glRotatef(270, 0, 1, 0);
				break;
			default:
				// Unexpected values (up, down)
				return;
			}
			drawImage(info.width, info.height);
			glPopMatrix();
		} catch (RuntimeException ex) {
			ex.printStackTrace();
			throw ex;
		}
	}

	/**
	 * Draws the given image at the current location, using the given width and height values.  Also draws a backing to it.
	 * @param width The width in blocks (NOT the width of the image)
	 * @param height The height in blocks (NOT the height of the image)
	 */
	private void drawImage(int width, int height) {
		glEnable(GL_TEXTURE_2D);
		glBegin(GL_QUADS);
		{
			// This needs to be flipped vertically for some reason...
			glTexCoord2f(0, 1);
			glVertex3f(0, 0, 0);

			glTexCoord2f(1, 1);
			glVertex3f(width, 0, 0);

			glTexCoord2f(1, 0);
			glVertex3f(width, height, 0);

			glTexCoord2f(0, 0);
			glVertex3f(0, height, 0);
		}
		glEnd();
		glDisable(GL_TEXTURE_2D);
		glBegin(GL_QUADS);
		{
			glVertex3f(0, height, 0);
			glVertex3f(width, height, 0);
			glVertex3f(width, 0, 0);
			glVertex3f(0, 0, 0);
		}
		glEnd();
	}

	@Override
	public void onRenderWorld(float partialTicks) {
	}

	@Override
	public void onRenderSky(float partialTicks, int pass) {
	}

	@Override
	public void onRenderClouds(float partialTicks, int pass,
			RenderGlobal renderGlobal) {
	}

	@Override
	public void onRenderTerrain(float partialTicks, int pass) {

	}
}
