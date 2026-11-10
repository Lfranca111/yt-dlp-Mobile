package com.linfranca.ytdlpmobile

import java.net.URI

internal object ComicDiff {
    data class Result(val additions: List<CollectionItem>,val review: String="")
    fun locator(url: String): String=runCatching { URI(url).path.replace(Regex("\\.\\d+x\\d+(?=\\.[a-zA-Z]+$)"),"") }.getOrDefault(url.substringBefore('?'))
    fun compare(old: List<CollectionItem>,fresh: List<CollectionItem>): Result {
        if(fresh.isEmpty()) return Result(emptyList(),"Empty response; baseline retained")
        val before=old.map(CollectionJournal::key);val after=fresh.map(CollectionJournal::key)
        if(after.distinct().size!=after.size) return Result(emptyList(),"Duplicate page identities; review required")
        if(before.any { it !in after }) return Result(emptyList(),"Known pages are missing; baseline retained for review")
        if(after.take(before.size)!=before) return Result(emptyList(),"Pages inserted or reordered; review required before appending")
        val oldMap=old.associateBy(CollectionJournal::key)
        if(fresh.any { oldMap[CollectionJournal.key(it)]?.let { prev -> locator(prev.url)!=locator(it.url) }==true })
            return Result(emptyList(),"An existing page appears replaced; review required")
        return Result(fresh.drop(old.size))
    }
}
