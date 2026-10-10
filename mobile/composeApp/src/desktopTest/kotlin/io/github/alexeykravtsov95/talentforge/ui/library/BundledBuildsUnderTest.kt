package io.github.alexeykravtsov95.talentforge.ui.library

import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.GameId

/**
 * Forever `1.60.1.70245`, the product dataset of M5.DATA. It stays bundled
 * (`BUNDLED_BUILDS` in `scripts/mobile_bundles.py`) so that builds saved on it
 * keep opening on it; the app itself never names it — a saved build carries
 * its own client build.
 */
val PREVIOUS_PRODUCT_BUNDLE: DatasetBundleId = DatasetBundleId(GameId("forever"), "1.60.1.70245")

/** Every bundled dataset, ascending: the test-side view of `BUNDLED_BUILDS`. */
val ALL_BUNDLES: List<DatasetBundleId> = listOf(REFERENCE_BUNDLE, PREVIOUS_PRODUCT_BUNDLE, PRODUCT_BUNDLE)
