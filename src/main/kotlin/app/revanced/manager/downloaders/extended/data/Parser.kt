package app.revanced.manager.downloaders.extended.data

object Parser {
    fun findMatch(input: String, pattern: Regex, groupIndex: Int = 1): String? {
        return pattern.find(input)?.groups?.get(groupIndex)?.value
    }

    fun findGroupsToMap(input: String, pattern: Regex): Map<String, String> {
        return pattern.findAll(input).associate { matchResult ->
            val type = matchResult.groupValues[1]
            val url = matchResult.groupValues[2]
            type to url
        }
    }
}