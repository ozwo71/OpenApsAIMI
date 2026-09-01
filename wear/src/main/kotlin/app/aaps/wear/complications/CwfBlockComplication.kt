package app.aaps.wear.complications

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.annotation.StringRes
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import app.aaps.core.interfaces.logging.LTag
import app.aaps.wear.R
import app.aaps.wear.watchfaces.CustomWatchface
import app.aaps.wear.data.ComplicationData as ComplicationStore

/**
 * Publishes part of the Custom watch face as an image complication.
 *
 * This is the bridge for watches whose firmware no longer selects or binds code-based watch faces
 * (Galaxy Watch 7 and later). There a Watch Face Format document is the only face that can run, and
 * a complication is the only channel from AAPS into it. So the CWF keeps being drawn by our own
 * code - honouring the user's zip, its dynamic values and its `dynPref` cascade - and the result is
 * handed over as a picture for the document to display.
 *
 * Subclasses differ only in [block]. Splitting the face lets the two halves refresh at different
 * rates: the lower half carries the background, the images and the chart and changes slowly, while
 * the upper half carries the clock and the hands and has to keep up with the second. Measurements
 * behind that split are in `_docs/CWF_WFF_Prompt.md`.
 *
 * Limits inherent to the approach, documented there rather than worked around here: the picture only
 * changes when the system asks for an update, and the user's own complication slots are not part of
 * it, because complication data only ever reaches the watch face the system has bound.
 */
abstract class CwfBlockComplication : ModernBaseComplicationProviderService() {

    companion object {

        /**
         * One watch face shared by every subclass, kept for the life of the process rather than of a
         * service instance: the system binds a data source only for as long as it takes to answer,
         * so an instance field would be discarded between requests and every render would pay full
         * construction again - about 275 ms against about 90 ms warm, since construction, injection
         * and inflation dominate.
         *
         * Shared rather than one per provider because the halves are two views of the *same* watch
         * face; separate instances would triple the memory and re-inflate the same layout.
         *
         * Holding it is safe: `prepareForRendering` attaches the **application** context, so nothing
         * belonging to a single bind is retained.
         */
        private var warmWatchFace: CustomWatchface? = null

        /**
         * Whether the face currently being drawn shows seconds at all.
         *
         * Both the zip and the user have a say - `enableSecond` is the json's `enableSecond` **and**
         * the "show seconds" preference - so this cannot be answered from the preference alone. Used
         * to decide how often the upper half is worth refreshing: a design without seconds gains
         * nothing from a per-second render and would only cost battery.
         *
         * False until something has been drawn, which errs towards the cheap rate.
         */
        internal fun showsSeconds(): Boolean = warmWatchFace?.enableSecond == true

        /**
         * True when the watch is in its low-power always-on state rather than active.
         *
         * A complication data source is never told about ambient mode - nothing in the request says
         * so - but it can look at the display, which reports a doze state while the watch is in
         * ambient. That is what lets the picture hide its seconds and drop to the cheap refresh rate
         * without the watch face having to tell us.
         */
        internal fun isAmbient(context: Context): Boolean =
            (context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.state
                ?: Display.STATE_ON) != Display.STATE_ON
    }

    /** Which half of the face this provider draws. */
    protected abstract val block: CustomWatchface.RenderBlock

    /** Label shown in the complication picker and used as the content description. */
    @get:StringRes
    protected abstract val label: Int

    override fun buildComplicationData(
        type: ComplicationType,
        data: ComplicationStore,
        complicationPendingIntent: PendingIntent
    ): ComplicationData? {
        val contentDescription = PlainComplicationText.Builder(text = getString(label)).build()
        val icon = Icon.createWithBitmap(render())

        return when (type) {
            ComplicationType.PHOTO_IMAGE -> PhotoImageComplicationData.Builder(
                photoImage = icon,
                contentDescription = contentDescription
            )
                .setTapAction(complicationPendingIntent)
                .build()

            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                smallImage = SmallImage.Builder(image = icon, type = SmallImageType.PHOTO).build(),
                contentDescription = contentDescription
            )
                .setTapAction(complicationPendingIntent)
                .build()

            else                         -> {
                aapsLogger.warn(LTag.WEAR, "${javaClass.simpleName} unexpected type: $type")
                null
            }
        }
    }

    /**
     * Draws [block] at screen size.
     *
     * A complication request carries no size, so the size is our choice; the screen is the right one
     * because this image is meant to fill the face, and the document scales it to the rectangle it
     * declares.
     *
     * Runs on the caller's thread, which for the live path is the main thread
     * ([ModernBaseComplicationProviderService] uses `Dispatchers.Main.immediate`) - required, both
     * because this inflates a view hierarchy and because the same thread must touch those views
     * every time.
     *
     * `refreshRenderData()` is what keeps a warm instance honest: it reloads the data, re-applies it,
     * and reaches the watch face's own re-read of its description, so a newly sent zip is picked up
     * without discarding anything.
     */
    private fun render(): Bitmap {
        val metrics = resources.displayMetrics
        val started = System.currentTimeMillis()
        val watchFace = warmWatchFace ?: CustomWatchface().also {
            it.prepareForRendering(this)
            warmWatchFace = it
        }
        // The picture is a still frame: a second hand it cannot keep moving would freeze at a stale
        // position, so the watch face is told to draw as ambient and drop its seconds.
        watchFace.setRenderAmbient(isAmbient(this))
        watchFace.refreshRenderData()
        val bitmap = watchFace.renderBlock(metrics.widthPixels, metrics.heightPixels, block)
        aapsLogger.debug(
            LTag.WEAR,
            "${javaClass.simpleName}: rendered $block ${bitmap.width}x${bitmap.height} in ${System.currentTimeMillis() - started} ms"
        )
        return bitmap
    }

    /**
     * A **static** picture of the default Custom watch face, never a live render.
     *
     * Two reasons, either sufficient. The base class builds preview data on the binder thread, and
     * inflating a view hierarchy off the main thread is not allowed. And a preview is requested
     * whenever anyone browses a complication picker - on a watch still running the code-based Custom
     * watch face, rendering there would build a second `CustomWatchface` beside the live one, and the
     * two share process-wide state.
     *
     * The androidx contract asks for a fixed preview anyway: `getPreviewData` should show
     * representative content, not live data.
     */
    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val contentDescription = PlainComplicationText.Builder(text = getString(label)).build()
        val icon = Icon.createWithResource(this, R.drawable.watchface_custom)

        return when (type) {
            ComplicationType.PHOTO_IMAGE -> PhotoImageComplicationData.Builder(
                photoImage = icon,
                contentDescription = contentDescription
            ).build()

            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                smallImage = SmallImage.Builder(image = icon, type = SmallImageType.PHOTO).build(),
                contentDescription = contentDescription
            ).build()

            else                         -> null
        }
    }

    /** A tap lands anywhere on a full-screen picture, so it opens the menu rather than one detail screen. */
    override fun getComplicationAction(): ComplicationAction = ComplicationAction.MENU
}
