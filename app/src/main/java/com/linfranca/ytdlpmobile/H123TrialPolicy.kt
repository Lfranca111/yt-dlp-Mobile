package com.linfranca.ytdlpmobile

import org.json.JSONObject
import java.net.URI

/** Conservative comparison: correctness wins over speed; metadata/URL values never enter logs. */
internal object H123TrialPolicy {
    data class Measurement(val bytes: Long,val elapsedMs: Long,val valid: Boolean) {
        val bytesPerSecond: Double get()=if(valid && elapsedMs>0) bytes*1000.0/elapsedMs else 0.0
    }
    fun identityMatches(normal: JSONObject,candidate: JSONObject): Boolean {
        if(normal.optString("id").isBlank() || normal.optString("id")!=candidate.optString("id")) return false
        if(normal.optInt("_app_trial_server",-1)!=candidate.optInt("_app_trial_server",-2)) return false
        if(normal.optString("_app_trial_source").isBlank() || normal.optString("_app_trial_source")!=candidate.optString("_app_trial_source")) return false
        if(normal.optString("_app_variant")!=candidate.optString("_app_variant")) return false
        if(normal.optBoolean("is_live") || candidate.optBoolean("is_live")) return false
        val a=normal.optDouble("_app_expected_duration",0.0);val b=candidate.optDouble("_app_expected_duration",0.0)
        return a>0 && b>0 && kotlin.math.abs(a-b)<=maxOf(2.0,a*0.01)
    }
    private fun formats(j: JSONObject): List<JSONObject> = j.optJSONArray("formats")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: listOf(j)
    private fun route(raw: String): String=runCatching { URI(raw).let { it.scheme+"://"+it.rawAuthority+it.rawPath } }.getOrDefault("")
    fun equivalentQuality(a: JSONObject,b: JSONObject): Boolean {
        val x=formats(a);val y=formats(b)
        val videoX=x.filter { it.optString("vcodec")!="none" };val videoY=y.filter { it.optString("vcodec")!="none" }
        if(videoX.isEmpty() || videoY.isEmpty()) return false
        if(x.any { it.optString("acodec")!="none" }!=y.any { it.optString("acodec")!="none" }) return false
        val heightsX=videoX.map { it.optInt("height",0) };val heightsY=videoY.map { it.optInt("height",0) }
        if(heightsX.all { it>0 } && heightsY.all { it>0 }) {
            fun tracks(f: List<JSONObject>)=f.map { listOf(it.optInt("width"),it.optInt("height"),it.optString("vcodec"),it.optString("acodec"),it.optString("language")) }.toSet()
            return tracks(x)==tracks(y)
        }
        // Without rendition metadata only the same resource routes qualify (session queries may refresh).
        val routesX=x.map { route(it.optString("url")) };val routesY=y.map { route(it.optString("url")) }
        return routesX.all { it.isNotBlank() } && routesX.toSet()==routesY.toSet()
    }
    fun browserWins(normal: Measurement,browser: Measurement): Boolean = browser.valid && browser.bytes>=262144 && browser.elapsedMs>=1500 &&
        (!normal.valid || normal.bytes<262144 || browser.bytesPerSecond>normal.bytesPerSecond*1.20)
}
