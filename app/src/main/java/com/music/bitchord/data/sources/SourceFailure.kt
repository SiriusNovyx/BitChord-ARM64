package com.music.bitchord.data.sources

import com.music.bitchord.data.sources.addon.AddonHttpException
import com.music.bitchord.data.sources.addon.AddonUnavailable
import java.io.IOException

internal fun Throwable.isProviderWideSourceFailure(): Boolean =
    this is IOException || this is AddonUnavailable ||
        (this is AddonHttpException && (status == 401 || status == 403 || status == 429 || status >= 500))
