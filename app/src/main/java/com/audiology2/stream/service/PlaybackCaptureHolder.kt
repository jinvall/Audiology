package com.audiology2.stream.service

import android.media.projection.MediaProjection

/**
 * Process-wide holder for the MediaProjection used by playback capture.
 *
 * MediaProjection cannot cross a Binder boundary, and the service may start
 * capture before the Activity has a binder. The Activity therefore obtains the
 * projection from the system dialog and stores it here BEFORE starting the
 * service; the service reads it when building the capture configuration.
 */
object PlaybackCaptureHolder {
    @Volatile
    var projection: MediaProjection? = null
}
