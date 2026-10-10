package app.party.wpnative

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputContentInfo
import android.widget.EditText

/** Gboard/Samsung keyboard ke rich-content GIF ko normal text composer mein receive karta hai. */
class KeyboardGifEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : EditText(context, attrs) {

    var onGifContent: ((InputContentInfo) -> Boolean)? = null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        outAttrs.contentMimeTypes = arrayOf("image/gif")
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(base, false) {
            override fun commitContent(
                inputContentInfo: InputContentInfo,
                flags: Int,
                opts: Bundle?
            ): Boolean {
                if (!inputContentInfo.description.hasMimeType("image/gif")) {
                    return super.commitContent(inputContentInfo, flags, opts)
                }
                if (flags and InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0) {
                    try { inputContentInfo.requestPermission() } catch (_: Throwable) { return false }
                }
                return onGifContent?.invoke(inputContentInfo) == true ||
                    super.commitContent(inputContentInfo, flags, opts)
            }
        }
    }
}
