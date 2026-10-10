package app.party.wpnative

import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ResultReceiver
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
        // IMPORTANT: EditText ka super pehle EditorInfo dobara populate karta hai. MIME uske
        // BAAD lagana hai; pehle lagane par Gboard ko null milta tha aur GIF select disabled tha.
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        outAttrs.contentMimeTypes = MIME_TYPES
        val extras = outAttrs.extras ?: Bundle().also { outAttrs.extras = it }
        // Kuch OEM keyboards API 25+ par bhi support-library keys hi dekhte hain.
        extras.putStringArray(ANDROIDX_MIME_KEY, MIME_TYPES)
        extras.putStringArray(SUPPORT_MIME_KEY, MIME_TYPES)

        return object : InputConnectionWrapper(base, false) {
            override fun commitContent(
                inputContentInfo: InputContentInfo,
                flags: Int,
                opts: Bundle?
            ): Boolean {
                return if (dispatchGif(inputContentInfo, flags)) true
                else super.commitContent(inputContentInfo, flags, opts)
            }

            /** Samsung/older Gboard ka support-library private-command fallback. */
            override fun performPrivateCommand(action: String?, data: Bundle?): Boolean {
                val modern = action == ANDROIDX_ACTION
                val legacy = action == SUPPORT_ACTION
                if ((!modern && !legacy) || data == null) {
                    return super.performPrivateCommand(action, data)
                }
                val prefix = if (modern) ANDROIDX_PREFIX else SUPPORT_PREFIX
                var receiver: ResultReceiver? = null
                return try {
                    @Suppress("DEPRECATION")
                    val uri = data.getParcelable(prefix + "CONTENT_URI") as? Uri
                    @Suppress("DEPRECATION")
                    val description = data.getParcelable(prefix + "CONTENT_DESCRIPTION") as? ClipDescription
                    @Suppress("DEPRECATION")
                    val link = data.getParcelable(prefix + "CONTENT_LINK_URI") as? Uri
                    @Suppress("DEPRECATION")
                    receiver = data.getParcelable(prefix + "CONTENT_RESULT_RECEIVER") as? ResultReceiver
                    val flags = data.getInt(prefix + "CONTENT_FLAGS")
                    if (uri == null || description == null) {
                        receiver?.send(0, null)
                        super.performPrivateCommand(action, data)
                    } else {
                        val handled = dispatchGif(InputContentInfo(uri, description, link), flags)
                        receiver?.send(if (handled) 1 else 0, null)
                        handled || super.performPrivateCommand(action, data)
                    }
                } catch (_: Throwable) {
                    receiver?.send(0, null)
                    super.performPrivateCommand(action, data)
                }
            }
        }
    }

    private fun dispatchGif(info: InputContentInfo, flags: Int): Boolean {
        if (!info.description.hasMimeType("image/gif")) return false
        if (flags and InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0) {
            // Platform commit mein token permission deta hai; kuch OEM compat commands URI
            // pehle hi grant kar dete hain aur synthetic InputContentInfo request throw karta hai.
            try { info.requestPermission() } catch (_: Throwable) { }
        }
        return onGifContent?.invoke(info) == true
    }

    companion object {
        private val MIME_TYPES = arrayOf("image/gif")
        private const val ANDROIDX_MIME_KEY =
            "androidx.core.view.inputmethod.EditorInfoCompat.CONTENT_MIME_TYPES"
        private const val SUPPORT_MIME_KEY =
            "android.support.v13.view.inputmethod.EditorInfoCompat.CONTENT_MIME_TYPES"
        private const val ANDROIDX_ACTION =
            "androidx.core.view.inputmethod.InputConnectionCompat.COMMIT_CONTENT"
        private const val SUPPORT_ACTION =
            "android.support.v13.view.inputmethod.InputConnectionCompat.COMMIT_CONTENT"
        private const val ANDROIDX_PREFIX =
            "androidx.core.view.inputmethod.InputConnectionCompat."
        private const val SUPPORT_PREFIX =
            "android.support.v13.view.inputmethod.InputConnectionCompat."
    }
}
