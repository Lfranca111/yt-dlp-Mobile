package com.linfranca.ytdlpmobile

import org.jsoup.Jsoup
import java.net.URI

/** Recognizes blocking gates, never accepts or dismisses a site's agreement. */
internal object MonitoringInteractionPolicy {
    fun textKind(text: String): String? {
        val s=text.lowercase()
        if(Regex("verify (that )?you('|’)re human|verify you are human|just a moment|security verification|checking your browser|cf-chl-|challenge-platform").containsMatchIn(s)) return "verification"
        if(Regex("(confirm|verify|yes|i am|i'm|i’m|over|under).{0,35}(18|21|age)|age.{0,25}(verification|confirmation|restricted)").containsMatchIn(s)) return "age"
        if(Regex("(i agree|accept|agree to|consent).{0,90}(terms|agreement|privacy|cookies)|terms.{0,55}(accept|agree)|consent required").containsMatchIn(s)) return "agreement"
        if(Regex("sign.?in to continue|log.?in to continue|authentication required|you must (log|sign)").containsMatchIn(s)) return "login"
        return null
    }
    fun responseKind(url: String,html: String,status: Int=200): String? {
        val doc=Jsoup.parse(html)
        textKind(doc.title())?.let { return it }
        if(status in setOf(401,403) && (html.contains("challenge-platform",true) || html.contains("cf-chl-",true))) return "verification"
        val path=runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        if(Regex("(?i)(^|/)(login|signin|sign-in)(/|$)").containsMatchIn(path) && doc.selectFirst("input[type=password]")!=null) return "login"
        // Only explicit open modals are evidence in static HTML; hidden templates are ignored.
        for(e in doc.select("[aria-modal=true],dialog[open]")) {
            if((e.parents().toList()+e).any { it.hasAttr("hidden") || it.attr("aria-hidden")=="true" || Regex("(?i)display\\s*:\\s*none|visibility\\s*:\\s*hidden").containsMatchIn(it.attr("style")) }) continue
            textKind(e.text())?.let { return it }
        }
        return null
    }
    fun message(kind: String) = when(kind) {
        "age" -> "Age confirmation required in the monitoring browser; live status unknown. Confirm it yourself in Browser Assist, then retry."
        "agreement" -> "Website agreement or consent required in the monitoring browser; live status unknown. Review it in Browser Assist, then retry."
        "login" -> "Sign-in required in the monitoring browser; live status unknown. Sign in using Browser Assist, then retry."
        else -> "Human verification required in the monitoring browser; live status unknown. Complete it in Browser Assist, then retry."
    }
}
