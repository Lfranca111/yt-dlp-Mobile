package com.linfranca.ytdlpmobile

import java.io.File
import java.net.HttpCookie
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.json.JSONObject

/** Generated requests follow the browser default; explicitly captured headers remain authoritative. */
internal object MediaRequestPolicy {
    fun referrer(page: String, target: String): String = runCatching {
        val from=URI(page);val to=URI(target)
        if(from.scheme !in setOf("https","http") || from.host==null || to.host==null || (from.scheme=="https" && to.scheme!="https")) return ""
        fun port(u: URI)=if(u.port!=-1) u.port else if(u.scheme=="https") 443 else 80
        val same=from.scheme.equals(to.scheme,true) && from.host.equals(to.host,true) && port(from)==port(to)
        val origin=URI(from.scheme,null,from.host,from.port,"/",null,null).toASCIIString()
        if(same) URI(origin.removeSuffix("/")+from.rawPath.ifBlank { "/" }+(from.rawQuery?.let { "?$it" } ?: "")).toASCIIString()
        else origin
    }.getOrDefault("")
    fun retryAfter(value: String?, now: Long=System.currentTimeMillis()): Long {
        val v=value?.trim().orEmpty()
        v.toLongOrNull()?.takeIf { it>=0 }?.let { return if(it>(Long.MAX_VALUE-now)/1000) Long.MAX_VALUE else now+it*1000 }
        if(v.isNotEmpty() && v.all(Char::isDigit)) return Long.MAX_VALUE
        return runCatching { ZonedDateTime.parse(v,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli().coerceAtLeast(now) }.getOrDefault(0)
    }
}

/** Keeps original cookie domains/paths, including cookies used only by a different segment host. */
internal class MediaCookieJar(private val now: ()->Long={ System.currentTimeMillis()/1000 }) {
    private data class Cookie(val domain: String,val subdomains: Boolean,val path: String,val secure: Boolean,val expires: Long,val name: String,val value: String,val httpOnly: Boolean=false) {
        val key get()=listOf(domain,path,name)
        fun matches(u: URI,time: Long)= (u.host.equals(domain,true) || (subdomains && u.host.orEmpty().lowercase().endsWith(".$domain"))) &&
            (!secure || u.scheme=="https") && (expires==0L || expires>time) &&
            (u.rawPath.ifBlank { "/" }==path || (u.rawPath.ifBlank { "/" }.startsWith(path) && (path.endsWith('/') || u.rawPath.getOrNull(path.length)=='/')))
    }
    private val cookies=linkedMapOf<List<String>,Cookie>()
    @Synchronized fun accept(url: String, headers: Map<String,List<String>>) {
        val u=URI(url);val host=u.host.lowercase()
        for((name,values) in headers) if(name.equals("Set-Cookie",true)) for(raw in values) {
            val parsed=runCatching { HttpCookie.parse(raw) }.getOrDefault(emptyList())
            for(c in parsed) {
                val domain=c.domain?.trimStart('.')?.lowercase() ?: host
                if(domain!=host && (!host.endsWith(".$domain") || !domain.contains('.'))) continue
                val path=c.path?.takeIf { it.startsWith('/') } ?: u.rawPath.substringBeforeLast('/',"").ifBlank { "/" }
                val expires=when { c.maxAge==0L -> 1L;c.maxAge<0 -> 0L;c.maxAge>Long.MAX_VALUE-now() -> Long.MAX_VALUE;else -> now()+c.maxAge }
                put(Cookie(domain,c.domain!=null,path,c.secure,expires,c.name,c.value,c.isHttpOnly))
            }
        }
    }
    @Synchronized fun observe(url: String, header: String) {
        val u=URI(url)
        header.split(';').map(String::trim).filter { it.contains('=') && !it.startsWith('$') }.forEach {
            val name=it.substringBefore('=');val value=it.substringAfter('=')
            if(cookies.values.none { c -> c.name==name && c.matches(u,Long.MIN_VALUE) }) put(Cookie(u.host.lowercase(),false,"/",u.scheme=="https",0,name,value))
        }
    }
    private fun put(c: Cookie, imported: Boolean=false) {
        if(listOf(c.domain,c.path,c.name,c.value).any { it.contains('\t') || it.contains('\n') || it.contains('\r') } || c.name.isBlank()) return
        if(imported || cookies.size<256 || c.key in cookies) cookies[c.key]=c
    }
    @Synchronized fun header(url: String): String = cookies.values.filter { it.matches(URI(url),now()) }
        .sortedByDescending { it.path.length }.distinctBy { it.name }.joinToString("; ") { "${it.name}=${it.value}" }
    @Synchronized fun load(text: String, onlyForUrl: String?=null, overwrite: Boolean=true) {
        for(raw in text.lineSequence()) {
            val line=raw.removePrefix("#HttpOnly_");if(line.startsWith('#') || line.isBlank()) continue
            val v=line.split('\t');if(v.size!=7) continue
            val expires=v[4].toLongOrNull() ?: continue
            val cookie=Cookie(v[0].trimStart('.').lowercase(),v[1].equals("TRUE",true),v[2].ifBlank { "/" },v[3].equals("TRUE",true),expires,v[5],v[6],raw.startsWith("#HttpOnly_"))
            if((onlyForUrl==null || cookie.matches(URI(onlyForUrl),now())) && (overwrite || cookie.key !in cookies)) put(cookie,true)
        }
    }
    @Synchronized fun netscape(): String = "# Netscape HTTP Cookie File\n"+cookies.values.joinToString("\n") { c ->
        (if(c.httpOnly) "#HttpOnly_" else "")+(if(c.subdomains) "." else "")+c.domain+"\t"+c.subdomains.toString().uppercase()+"\t"+c.path+"\t"+c.secure.toString().uppercase()+"\t"+c.expires+"\t"+c.name+"\t"+c.value
    }+"\n"
}

internal object DownloadSessionFiles {
    private const val NAME=".media-session-cookies.txt"
    fun privateFile(file: File)=file.name==NAME || file.name==NAME+".snapshot"
    /** Keep downloader-updated cookies on retries of the same snapshot; fresh preparation supersedes stale values. */
    fun cookieFile(info: String, original: String, directory: File): String {
        val j=runCatching { JSONObject(info) }.getOrNull() ?: return original
        if(!j.has("_app_cookie_jar")) return original
        val fresh=j.optString("_app_cookie_jar")
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(fresh.toByteArray()).joinToString("") { "%02x".format(it) }
        val target=File(directory,NAME)
        val stamp=File(directory,NAME+".snapshot")
        if(target.isFile && stamp.isFile && stamp.readText()==hash && target.useLines { it.firstOrNull().orEmpty().startsWith("# Netscape HTTP Cookie File") }) return target.absolutePath
        val jar=MediaCookieJar()
        if(target.isFile) jar.load(target.readText())
        File(original).takeIf { original.isNotBlank() && it.isFile }?.let { jar.load(it.readText()) }
        jar.load(fresh)
        target.writeText(jar.netscape())
        stamp.writeText(hash)
        return target.absolutePath
    }
    fun downloaderInfo(info: String): String=JSONObject(info).apply { remove("_app_cookie_jar") }.toString()
}
