package io.homeassistant.companion.android.hatail

import kotlinx.serialization.Serializable

internal val UPDATE_REPOSITORIES = setOf("home-assistant/android", "tailscale/tailscale-android", "tailscale/tailscale")

@Serializable
internal data class InstalledComponent(val repository: String, val version: String, val commit: String) {
    val sourceUrl: String get() = "https://github.com/$repository/commit/$commit"
}

@Serializable
internal data class HatailBuildInformation(val revision: String, val components: List<InstalledComponent>)

@Serializable
internal sealed interface ReleaseRelation {
    @Serializable data object Current : ReleaseRelation

    @Serializable data object Newer : ReleaseRelation

    @Serializable data object Older : ReleaseRelation

    @Serializable data object Diverged : ReleaseRelation

    @Serializable data object Unknown : ReleaseRelation
}

@Serializable
internal data class PublishedRelease(
    val tag: String,
    val name: String,
    val notes: String,
    val publishedAt: String,
    val commit: String,
    val relation: ReleaseRelation,
) {
    fun releaseUrl(repository: String): String =
        "https://github.com/$repository/releases/tag/${java.net.URLEncoder.encode(tag, "UTF-8")}"
}

@Serializable
internal data class ComponentVersionState(
    val installed: InstalledComponent,
    val release: PublishedRelease? = null,
    val checkedAtEpochMs: Long = 0,
    val failed: Boolean = false,
)

@Serializable
internal data class HatailUpdateCache(
    val buildKey: String,
    val attemptedAtEpochMs: Long,
    val components: List<ComponentVersionState>,
)

internal data class HatailVersionsState(
    val revision: String = "",
    val components: List<ComponentVersionState> = emptyList(),
    val refreshing: Boolean = false,
    val throttled: Boolean = false,
    val attemptedAtEpochMs: Long = 0,
) {
    val hasNewerRelease: Boolean get() = components.any { it.release?.relation == ReleaseRelation.Newer }
}

internal fun releaseRelation(status: String): ReleaseRelation = when (status) {
    "identical" -> ReleaseRelation.Current
    "ahead" -> ReleaseRelation.Newer
    "behind" -> ReleaseRelation.Older
    "diverged" -> ReleaseRelation.Diverged
    else -> ReleaseRelation.Unknown
}
