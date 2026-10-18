package io.github.alexeykravtsov95.talentforge.ui.about

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * M5.UI.ABOUT.1: the icon in the About header is the APPROVED launcher artwork
 * (M5.RC.2a, concept #3), never a redrawn stand-in. The common resource is a
 * byte-for-byte copy of the Android xxxhdpi launcher raster (192 × 192 px);
 * replacing the launcher icon without updating the copy fails here.
 */
class AppIconResourceTest {

    @Test
    fun theAboutIconIsTheApprovedLauncherIconByteForByte() {
        val module = File(System.getProperty("user.dir")).absoluteFile
        assertContentEquals(
            module.resolve("src/androidMain/res/mipmap-xxxhdpi/ic_launcher.png").readBytes(),
            module.resolve("src/commonMain/composeResources/drawable/app_icon.png").readBytes(),
            "drawable/app_icon.png differs from the approved launcher icon — copy mipmap-xxxhdpi/ic_launcher.png",
        )
    }
}
