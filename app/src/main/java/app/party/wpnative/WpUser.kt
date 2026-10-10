package app.party.wpnative

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * App ki stable DM pehchaan.
 *
 * Display name badal sakta hai, lekin Friend Code ek install par hamesha wahi rehta hai.
 * Nayi code-backed chats dono Friend Codes se banti hain; purane name-only friends ke liye
 * legacy [chatId] ab bhi rakha gaya hai taa-ke existing chats/caches na tootain.
 */
object WpUser {
    private const val PREF = "wp_user"
    private const val KEY_NAME = "name"
    private const val KEY_PUBLISHED_NAME = "published_name"
    private const val KEY_CODE = "friend_code"
    private const val CODE_PREFIX = "WP1"
    private const val CODE_LEN = 8
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    fun me(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return p.getString(KEY_NAME, null)?.trim()?.takeIf { it.isNotEmpty() } ?: "Me"
    }

    fun setName(ctx: Context, name: String) {
        val n = name.trim().take(40)
        if (n.isNotEmpty()) ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_NAME, n).apply()
    }

    fun savedName(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_NAME, "") ?: ""

    /** Directory/presence par aakhri successfully publish hua naam. */
    fun publishedName(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_PUBLISHED_NAME, "")?.trim().orEmpty()

    fun markNamePublished(ctx: Context, name: String) {
        val clean = name.trim().take(40)
        if (clean.isNotBlank()) ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_PUBLISHED_NAME, clean).apply()
    }

    /** Is handset ka stable, shareable code: WP1-XXXX-XXXX. */
    fun friendCode(ctx: Context): String = formatFriendCode(friendCodeRaw(ctx))

    /** Firestore/document/chat keys ke liye sirf 8-character payload. */
    fun friendCodeRaw(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val old = normalizeFriendCode(p.getString(KEY_CODE, ""))
        if (old.length == CODE_LEN) return old

        val random = SecureRandom()
        val made = buildString(CODE_LEN) {
            repeat(CODE_LEN) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
        p.edit().putString(KEY_CODE, made).apply()
        return made
    }

    /** WP1-, spaces aur punctuation hata kar max 8 uppercase payload chars deta hai. */
    fun normalizeFriendCode(value: String?): String {
        var clean = value.orEmpty().uppercase().replace(Regex("[^A-Z0-9]"), "")
        if (clean.startsWith(CODE_PREFIX)) clean = clean.drop(CODE_PREFIX.length)
        return clean.take(CODE_LEN)
    }

    fun isValidFriendCode(value: String?): Boolean = normalizeFriendCode(value).length == CODE_LEN

    fun formatFriendCode(value: String?): String {
        val raw = normalizeFriendCode(value)
        return if (raw.length == CODE_LEN) "$CODE_PREFIX-${raw.take(4)}-${raw.drop(4)}" else ""
    }

    /** Legacy name-based DM id — existing name-only friends/caches ke liye. */
    fun chatId(a: String, b: String): String {
        val pair = listOf(a.trim().lowercase(), b.trim().lowercase()).sorted().joinToString("|")
        val bytes = MessageDigest.getInstance("SHA-256").digest(pair.toByteArray())
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }

    /**
     * Code mila ho to collision-safe stable chat; old friend ho to bilkul purana name chat.
     * Is se dono handsets par display names badalne ke bawajood ek hi conversation khulti hai.
     */
    fun friendChatId(ctx: Context, peerName: String, peerCode: String?): String {
        val other = normalizeFriendCode(peerCode)
        if (other.length != CODE_LEN) return chatId(me(ctx), peerName)
        return chatId("code:${friendCodeRaw(ctx)}", "code:$other")
    }
}
