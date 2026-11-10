package com.linfranca.ytdlpmobile

import java.net.URI

/** Explicit hosts and page families; a lookalike domain must never inherit a site's extractor. */
internal object CollectionSiteProfile {
    val sites = linkedMapOf(
        "nhentai.net" to "nHentai", "doujins.com" to "Doujins", "hentaifox.com" to "HentaiFox",
        "hentai2read.com" to "Hentai2Read", "comics.8muses.com" to "8Muses", "8muses.com" to "8Muses",
        "allporncomic.com" to "AllPornComic", "ilikecomix.com" to "ILikeComix",
        "myhentaigallery.com" to "MyHentaiGallery", "honeytoon.com" to "Honeytoon", "comicporn.xxx" to "ComicPorn"
    )
    fun site(host: String): String? = sites[host.removePrefix("www.").lowercase()]
    fun type(site: String, uri: URI): String {
        val p = uri.path.orEmpty().trim('/').split('/').filter(String::isNotEmpty)
        val f = p.firstOrNull().orEmpty()
        if (p.isEmpty()) return if (!uri.rawQuery.isNullOrBlank()) "Collection listing" else "Site page"
        if (f in setOf("login", "signin", "register", "mylibrary", "for-you")) return "Account page"
        if (f in setOf("community", "blog", "contact", "privacy", "terms")) return "Non-collection page"
        return when (site) {
            "nHentai" -> if (f == "g" && p.getOrNull(1)?.all(Char::isDigit) == true) "Gallery" else "Collection listing"
            "HentaiFox", "ComicPorn" -> if (f in setOf("gallery", "g", "view") && p.size >= 2) "Gallery" else "Collection listing"
            "Doujins" -> if (p.size >= 2 && f !in setOf("tags", "artists", "series", "top", "search")) "Gallery" else "Collection listing"
            "Hentai2Read" -> when {
                f == "reader" && p.size >= 3 -> "Chapter"
                f in setOf("original", "doujinshi", "imageset") && p.size >= 2 -> "Comic"
                p.size >= 2 && p[1].matches(Regex("\\d+")) -> "Chapter"
                f !in setOf("browse", "ranking", "tags", "category", "content", "search", "artists", "genres") && p.size == 1 -> "Comic"
                else -> "Collection listing"
            }
            "8Muses" -> if ("album" in p) "Comic or folder" else "Collection listing"
            "AllPornComic" -> if (f == "porncomic" && p.size >= 2) {
                if (p.size >= 3) "Chapter" else "Comic"
            } else "Collection listing"
            "ILikeComix" -> if (f !in setOf("category", "tag", "author", "page", "search") && !uri.rawQuery.orEmpty().contains("s=")) "Comic" else "Collection listing"
            "MyHentaiGallery" -> if ((f in setOf("a", "g") && p.getOrNull(1)?.all(Char::isDigit) == true) || f == "gallery") "Gallery" else "Collection listing"
            "Honeytoon" -> if (f == "comic" && p.size >= 2) { if (p.size >= 3) "Chapter" else "Comic" } else "Collection listing"
            else -> "Site page"
        }
    }
    fun isLeaf(site: String, uri: URI) = type(site, uri) in setOf("Gallery", "Chapter", "Comic", "Comic or folder")
}
