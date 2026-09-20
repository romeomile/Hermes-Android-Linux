package com.romirmile.hermes.vm

import com.romirmile.hermes.BuildConfig

/**
 * Identity of the guest disk this build ships.
 *
 * [SHA256] is computed from the asset at build time (see `app/build.gradle.kts`), so it changes
 * whenever the image changes. The extraction marker is derived from it, which makes "the app kept a
 * stale guest disk" impossible: two releases cannot share an identity the way they did when the
 * marker was a hand-bumped counter (1.0.2 and 1.0.3 shipped different images under the same marker,
 * so anyone updating from 1.0.2 never received the 1.0.3 image at all).
 */
object GuestImage {

    /** Full SHA-256 of `assets/vm/base.qcow2.gz` as packed into this build. */
    val SHA256: String = BuildConfig.GUEST_IMAGE_SHA256

    /** Short form used in the extraction marker and in log lines. */
    val ID: String = SHA256.take(16)

    /** True when the build carried no image to hash (a checkout before `image/build_guest_image.sh`). */
    val isMissing: Boolean = SHA256 == "missing"
}
