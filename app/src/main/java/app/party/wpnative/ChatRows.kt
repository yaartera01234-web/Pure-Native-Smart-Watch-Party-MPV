package app.party.wpnative

/**
 * Screen par dikhne wala ek message (ChatActivity ka model).
 * (Pehle ye ChatActivity ke andar chhupa tha — ab RecyclerView ke sath
 *  adapter ko bhi chahiye, is liye alag file mein.)
 */
class Msg(
    val id: Int,
    val text: String,
    val own: Boolean,
    val time: String,
    val day: String,
    var read: Boolean = false,
    var replyName: String = "",
    var replyText: String = "",
    var replyMid: String = "",      // quote tap par exact original message id
    val rx: LinkedHashMap<String, Boolean> = LinkedHashMap(),
    var fid: String = "",        // Firestore document id (dobara na aaye is liye)
    var ts: Long = 0L,           // asli waqt (pagination isi se hoti hai)
    var deleted: Boolean = false, // mita hua — doosre phone ne delete kiya to yahan bhi hat jaye
    var type: String = "text",   // "text" | "photo" | "voice"
    var mediaKey: String = "",   // phone mein save photo/voice ki chaabi
    var dur: Int = 0,            // voice: kitne second
    var wave: String = "",       // voice: har 100ms ki awaaz
    var senderName: String = "", // Party Room mein har member ka apna naam
    var senderColor: Int = 0,     // Party Room member/avatar accent
    val rxCounts: LinkedHashMap<String, Int> = LinkedHashMap(),
    val reactionActors: LinkedHashMap<String, String> = LinkedHashMap(),
    var mediaUrl: String = ""     // keyboard GIF ka shareable HTTPS source (agar mila)
)

/**
 * RecyclerView ki **ek line** — 4 tarah ki hoti hai:
 *  - MSG    : asli message
 *  - DAY    : "Aaj" / "Kal" wala sar-nama
 *  - TYPING : doosre wale ke Instagram-jaise 3 dots
 *  - EMPTY  : khaali chat wali state
 *
 * `key`  = DiffUtil ko batata hai ke kaun si line wahi purani hai
 * `sig`  = andar ka maal badla (text / ✓✓ / reaction / reply) to diff pakad leta hai
 */
class Row(
    val kind: Int,
    val key: String,
    val sig: String,
    val msg: Msg?,
    val day: String?
) {
    companion object {
        const val MSG = 0
        const val DAY = 1
        const val TYPING = 2
        const val EMPTY = 3
    }
}
