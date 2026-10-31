package com.forwardsweep.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.forwardsweep.data.DataGraph

class ForwardSweepApp : Application(), DataGraph.Provider, ImageLoaderFactory {
    override val graph: DataGraph by lazy { DataGraph(this, R.drawable.ic_notification) }

    /** Coil with video-frame thumbnails for WhatsApp videos and GIFs. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .build()
}
