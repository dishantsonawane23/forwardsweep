package com.forwardsweep.data

import android.content.Context
import androidx.annotation.DrawableRes
import com.forwardsweep.data.db.ForwardSweepDatabase
import com.forwardsweep.data.delete.DeletionManager
import com.forwardsweep.data.media.MediaStoreSource
import com.forwardsweep.data.scan.ScanScheduler
import com.forwardsweep.data.settings.SettingsRepository
import com.forwardsweep.ml.PipelineFactory

/**
 * Manual dependency graph (the app is small enough not to need Hilt). The Application owns
 * one instance and implements [Provider] so workers can reach it.
 */
class DataGraph(context: Context, @DrawableRes val notificationIcon: Int) {
    private val app = context.applicationContext

    val database: ForwardSweepDatabase by lazy { ForwardSweepDatabase.create(app) }
    val settings: SettingsRepository by lazy { SettingsRepository(app) }
    val media: MediaStoreSource by lazy { MediaStoreSource(app.contentResolver) }
    val pipelines: PipelineFactory by lazy { PipelineFactory(app) }
    val images: ImageRepository by lazy { ImageRepository(app, database, media, app.contentResolver, pipelines) }
    val deletion: DeletionManager by lazy { DeletionManager(app.contentResolver, media, images) }
    val scheduler: ScanScheduler by lazy { ScanScheduler(app) }

    interface Provider {
        val graph: DataGraph
    }
}
