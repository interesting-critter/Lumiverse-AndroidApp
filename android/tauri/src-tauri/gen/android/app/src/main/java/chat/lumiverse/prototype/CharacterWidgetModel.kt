package chat.lumiverse.prototype

import org.json.JSONObject
import java.net.URI

data class WidgetCharacter(val characterId: String, val name: String, val chatId: String?)
data class WidgetSnapshot(val raw: JSONObject, val recent: List<WidgetCharacter>, val favorites: List<WidgetCharacter>) {
    val accountId: String get() = raw.getString("accountId")
    val generatedAt: Long get() = raw.getLong("generatedAt")
    companion object {
        fun parse(raw: JSONObject): WidgetSnapshot {
            require(raw.getInt("version") == 1 && raw.getLong("generatedAt") > 0)
            require(raw.getString("accountId").isNotBlank())
            fun items(key: String): List<WidgetCharacter> {
                val array = raw.getJSONArray(key)
                require(array.length() <= 16)
                return (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    val id = item.getString("characterId")
                    val name = item.getString("name")
                    val chat = if (item.isNull("chatId")) null else item.getString("chatId")
                    require(CharacterWidgetLink.identifier(id) && (chat == null || CharacterWidgetLink.identifier(chat)))
                    require(name.toByteArray(Charsets.UTF_8).size <= 1024)
                    WidgetCharacter(id, name, chat)
                }.also { list -> require(list.map { it.characterId }.toSet().size == list.size) }
            }
            return WidgetSnapshot(raw, items("recent"), items("favorites"))
        }
    }
}

object CharacterWidgetLink {
    fun identifier(raw: String) = Regex("[A-Za-z0-9_-]{1,128}").matches(raw)
    fun destination(raw: String, registration: String, snapshot: WidgetSnapshot): String? = try {
        val url = URI(raw)
        val parts = url.rawPath.orEmpty().split('/').drop(1)
        if (url.scheme != "lumiverse" || url.rawAuthority != "widget" || url.rawQuery != null || url.rawFragment != null
            || parts.size != 3 || parts[0] != registration || !identifier(parts[2])) null
        else {
            val items = when (parts[1]) { "recent" -> snapshot.recent; "favorites" -> snapshot.favorites; else -> emptyList() }
            items.firstOrNull { it.characterId == parts[2] }?.let { item ->
                item.chatId?.let { "/chat/$it" } ?: "/widget/character/${item.characterId}"
            }
        }
    } catch (_: Exception) { null }
}
