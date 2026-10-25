package io.github.alexeykravtsov95.talentforge.ui.about

import java.awt.Image
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The icon in the About header is the APPROVED app-icon artwork, never a redrawn
 * stand-in. Since the owner replaced the iOS icon (2026-10-25) the common resource
 * is a 192 × 192 px downscale of the same image as the iOS AppIcon (the area inside
 * the plate frame of docs/release/apple-appstore/assets/TalentForge-AppIcon-source-1254.png),
 * no longer a copy of the Android launcher raster, which keeps its earlier artwork.
 * Replacing the iOS AppIcon without regenerating the About copy fails here.
 */
class AppIconResourceTest {

    @Test
    fun theAboutIconIsADownscaleOfTheApprovedIosAppIcon() {
        val module = File(System.getProperty("user.dir")).absoluteFile
        val about = ImageIO.read(module.resolve("src/commonMain/composeResources/drawable/app_icon.png"))
        val appIcon = ImageIO.read(
            module.resolve("../iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png"),
        )
        assertEquals(ABOUT_ICON_PX to ABOUT_ICON_PX, about.width to about.height, "About icon size")
        assertEquals(1024 to 1024, appIcon.width to appIcon.height, "iOS AppIcon size")

        val expected = downscale(appIcon, ABOUT_ICON_PX)
        var total = 0L
        for (y in 0 until ABOUT_ICON_PX) {
            for (x in 0 until ABOUT_ICON_PX) {
                val actual = about.getRGB(x, y)
                val reference = expected.getRGB(x, y)
                for (shift in intArrayOf(16, 8, 0)) {
                    total += abs((actual shr shift and 0xFF) - (reference shr shift and 0xFF))
                }
            }
        }
        val meanDifference = total.toDouble() / (ABOUT_ICON_PX * ABOUT_ICON_PX * 3)
        assertTrue(
            meanDifference < MAX_MEAN_CHANNEL_DIFFERENCE,
            "drawable/app_icon.png is not the iOS AppIcon artwork (mean channel difference " +
                "$meanDifference) — regenerate it from the AppIcon source",
        )
    }

    private fun downscale(image: BufferedImage, size: Int): BufferedImage {
        val scaled = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val graphics = scaled.createGraphics()
        graphics.drawImage(image.getScaledInstance(size, size, Image.SCALE_AREA_AVERAGING), 0, 0, null)
        graphics.dispose()
        return scaled
    }

    private companion object {
        const val ABOUT_ICON_PX = 192

        // Resampling filters differ by ~2 levels on average; another artwork differs by ~27.
        const val MAX_MEAN_CHANNEL_DIFFERENCE = 6.0
    }
}
