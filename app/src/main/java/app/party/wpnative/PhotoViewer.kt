package app.party.wpnative

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.ViewGroup
import android.widget.ImageView

/** Photo par tap → poori screen par badi photo (website ka fullscreen viewer). */
object PhotoViewer {

    fun show(ctx: Context, bmp: Bitmap) {
        try {
            val dlg = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val iv = ImageView(ctx).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.BLACK)
                setOnClickListener { dlg.dismiss() }     // kahin bhi tap = band
            }
            dlg.setContentView(iv, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            dlg.show()
        } catch (t: Throwable) { }
    }
}
