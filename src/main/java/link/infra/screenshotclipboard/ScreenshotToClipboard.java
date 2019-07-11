package link.infra.screenshotclipboard;

import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.*;
import java.io.IOException;
import java.lang.annotation.Native;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.NativeImage;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenshotEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;

@Mod("screenshotclipboard")
public class ScreenshotToClipboard {
	private static final Logger LOGGER = LogManager.getLogger();

	public ScreenshotToClipboard() {
		DistExecutor.runWhenOn(Dist.CLIENT, () -> () -> {
			// A bit dangerous, but shouldn't technically cause any issues on most platforms - headless mode just disables the awt API
			// Minecraft usually has this enabled because it's using GLFW rather than AWT/Swing
			// Also causes problems on OSX, see: https://github.com/MinecraftForge/MinecraftForge/pull/5591#issuecomment-470805491
			System.setProperty("java.awt.headless", "false");
			MinecraftForge.EVENT_BUS.register(this);
		});
	}

	private boolean useHackyMode = true;

	@SubscribeEvent
	public void handleScreenshot(ScreenshotEvent event) {
		NativeImage img = event.getImage();
		// Only allow RGBA
		if (img.getFormat() != NativeImage.PixelFormat.RGBA) {
			return;
		}

		//BufferedImage bufImg = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);

		// Convert NativeImage to BufferedImage
		ByteBuffer byteBuffer = null;
		if (useHackyMode) {
			try {
				byteBuffer = hackyUnsafeGetPixelsRGBA(img);
			} catch (Exception e) {
				LOGGER.warn("An error has occurred trying to take a screenshot using Hacky Mode (tm), Safe Mode will be used", e);
				useHackyMode = false;
			}
			if (!useHackyMode) {
				byteBuffer = safeGetPixelsRGBA(img);
			}
		} else {
			byteBuffer = safeGetPixelsRGBA(img);
		}

		// TODO: For Mac OSX support, make a native library that takes a ByteBuffer of RGBA pixel data, and copies it to the clipboard

		byte[] array;
		if (byteBuffer.hasArray()) {
			array = byteBuffer.array();
		} else {
			// can't use .array() because unsafe retrieval references the volatile bytes directly!!
			array = new byte[img.getHeight() * img.getWidth() * 4];
			byteBuffer.get(array);
		}

		DataBufferByte buf = new DataBufferByte(array, array.length);
//		int[] array = new int[byteBuffer.remaining() / 4];
//		byteBuffer.asIntBuffer().get(array);
//		DataBufferInt buf = new DataBufferInt(array, array.length);
		//ColorModel cm = ColorModel.getRGBdefault();
		//BufferedImage bufImg = new BufferedImage(cm, Raster.createWritableRaster(cm.createCompatibleSampleModel(img.getWidth(), img.getHeight()), buf, null), false, null);

		// This is RGBA but it doesn't work with ColorModel.getRGBdefault for some reason!
		ColorSpace cs = ColorSpace.getInstance(ColorSpace.CS_sRGB);
		int[] nBits = {8, 8, 8, 8};
		//int[] bOffs = {3, 2, 1, 0};
		int[] bOffs = {0, 1, 2, 3};
		ColorModel cm = new ComponentColorModel(cs, nBits, true, false,
				Transparency.TRANSLUCENT,
				DataBuffer.TYPE_BYTE);
		BufferedImage bufImg = new BufferedImage(cm, Raster.createInterleavedRaster(buf,
				img.getWidth(), img.getHeight(),
				img.getWidth()*4, 4,
				bOffs, null), false, null);

		Transferable trans = getTransferableImage(bufImg);
		Clipboard c = Toolkit.getDefaultToolkit().getSystemClipboard();
		c.setContents(trans, null);
	}

	private Field imagePointerField = null;

	// This method is theoretically faster than safeGetPixelsRGBA but it might explode violently
	private ByteBuffer hackyUnsafeGetPixelsRGBA(NativeImage img) throws Exception {
		if (imagePointerField == null) {
			imagePointerField = ObfuscationReflectionHelper.findField(NativeImage.class, "field_195722_d");
		}
		long imagePointer = imagePointerField.getLong(img);
		ByteBuffer buf = MemoryUtil.memByteBufferSafe(imagePointer, img.getWidth() * img.getHeight() * 4);
		if (buf == null) {
			throw new RuntimeException("Invalid image");
		}
		return buf;
	}

	private ByteBuffer safeGetPixelsRGBA(NativeImage img) {
		ByteBuffer byteBuffer = ByteBuffer.allocate(img.getWidth() * img.getHeight() * 4);
		byteBuffer.order(ByteOrder.LITTLE_ENDIAN); // is this system dependent? TEST!!
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < img.getWidth(); x++) {
				byteBuffer.putInt(img.getPixelRGBA(x, y));
			}
		}
		return byteBuffer;
	}

	private Transferable getTransferableImage(final BufferedImage bufferedImage) {
		return new Transferable() {
			@Override
			public DataFlavor[] getTransferDataFlavors() {
				return new DataFlavor[] { DataFlavor.imageFlavor };
			}

			@Override
			public boolean isDataFlavorSupported(DataFlavor flavor) {
				return DataFlavor.imageFlavor.equals(flavor);
			}

			@Override
			public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
				if (DataFlavor.imageFlavor.equals(flavor)) {
					return bufferedImage;
				}
				throw new UnsupportedFlavorException(flavor);
			}
		};
	}

}