/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaupload.impl

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.core.data.tryOrNull
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfig
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfigProvider
import io.element.android.libraries.preferences.api.store.SessionPreferencesStore
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import kotlinx.coroutines.flow.first
import timber.log.Timber

@ContributesBinding(SessionScope::class)
class DefaultMediaOptimizationConfigProvider(
    @ApplicationContext private val context: Context,
    private val sessionPreferencesStore: SessionPreferencesStore,
    private val featureFlagsService: FeatureFlagService,
) : MediaOptimizationConfigProvider {
    override suspend fun get(): MediaOptimizationConfig {
        val compressImages = sessionPreferencesStore.doesOptimizeImages().first()
        if (!featureFlagsService.isFeatureEnabled(FeatureFlags.SelectableMediaQuality)) {
            return MediaOptimizationConfig(
                compressImages = compressImages,
                videoCompressionPreset = if (compressImages) VideoCompressionPreset.STANDARD else VideoCompressionPreset.HIGH,
            )
        }
        val preset = sessionPreferencesStore.getVideoCompressionPreset().first()
        if (preset != VideoCompressionPreset.AUTOMATIC) {
            return MediaOptimizationConfig(
                compressImages = compressImages,
                videoCompressionPreset = preset,
            )
        }
        // Orizon: automatic quality, based on the network in use right now.
        val network = currentNetwork()
        Timber.d("Automatic media quality, network: $network")
        return when (network) {
            NetworkKind.FAST_UNMETERED -> MediaOptimizationConfig(
                compressImages = false,
                videoCompressionPreset = VideoCompressionPreset.ORIGINAL,
            )
            NetworkKind.SLOW -> MediaOptimizationConfig(
                compressImages = true,
                videoCompressionPreset = VideoCompressionPreset.LOW,
            )
            NetworkKind.OTHER -> MediaOptimizationConfig(
                compressImages = compressImages,
                videoCompressionPreset = VideoCompressionPreset.STANDARD,
            )
        }
    }

    private enum class NetworkKind { FAST_UNMETERED, SLOW, OTHER }

    private fun currentNetwork(): NetworkKind {
        val capabilities = tryOrNull {
            val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            connectivityManager?.getNetworkCapabilities(connectivityManager.activeNetwork)
        } ?: return NetworkKind.OTHER
        val upstreamKbps = capabilities.linkUpstreamBandwidthKbps
        val notMetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED))
        return when {
            upstreamKbps in 1 until SLOW_UPSTREAM_KBPS -> NetworkKind.SLOW
            notMetered && (upstreamKbps <= 0 || upstreamKbps >= FAST_UPSTREAM_KBPS) -> NetworkKind.FAST_UNMETERED
            else -> NetworkKind.OTHER
        }
    }

    private companion object {
        /** Below 1 Mbit/s of estimated upload, videos are sent in low quality. */
        const val SLOW_UPSTREAM_KBPS = 1_000

        /** From 5 Mbit/s of estimated upload on an unmetered network, media are sent as they are. */
        const val FAST_UPSTREAM_KBPS = 5_000
    }
}
