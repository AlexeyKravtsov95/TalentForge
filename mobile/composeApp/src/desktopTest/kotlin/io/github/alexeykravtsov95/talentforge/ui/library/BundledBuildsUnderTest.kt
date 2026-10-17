package io.github.alexeykravtsov95.talentforge.ui.library

import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.GameId

/**
 * Forever `1.60.1.70245`, the product dataset of M5.DATA. It stays bundled
 * (`BUNDLED_BUILDS` in `scripts/mobile_bundles.py`) so that builds saved on it
 * keep opening on it; the app itself never names it — a saved build carries
 * its own client build.
 */
val BUNDLE_70245: DatasetBundleId = DatasetBundleId(GameId("forever"), "1.60.1.70245")

/**
 * Forever `1.60.1.70338`, the product dataset of M5.DATA.2 — the same talent
 * rows as 1.60.1.70374, yet its own bundle: builds saved on it keep opening on
 * it, exactly like [BUNDLE_70245].
 */
val BUNDLE_70338: DatasetBundleId = DatasetBundleId(GameId("forever"), "1.60.1.70338")

/**
 * Forever `1.60.1.70374`, the product dataset of M5.DATA.3. Builds saved on it
 * keep opening on it; the product dataset 1.60.1.70450 removed, re-purposed
 * and re-ranked some of its nodes (docs/forever-dataset.md).
 */
val PREVIOUS_PRODUCT_BUNDLE: DatasetBundleId = DatasetBundleId(GameId("forever"), "1.60.1.70374")

/** Every bundled dataset, ascending: the test-side view of `BUNDLED_BUILDS`. */
val ALL_BUNDLES: List<DatasetBundleId> =
    listOf(REFERENCE_BUNDLE, BUNDLE_70245, BUNDLE_70338, PREVIOUS_PRODUCT_BUNDLE, PRODUCT_BUNDLE)
