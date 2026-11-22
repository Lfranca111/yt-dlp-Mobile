package com.linfranca.ytdlpmobile

import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Safelist

internal object PostTextContent {
    private const val MAX_HTML = 300_000
    private const val X_HOST = """^(?:(?:www|mobile|m)\.)?(?:x|twitter)\.com$"""
    private const val X_STATUS = """^/(?:[^/]+/|i/web/)status/([0-9]+)(?:/(?:photo|video)/[0-9]+)?/?$"""
    private const val X_ARTICLE = "article, [role=article], [data-testid=tweet]"
    private const val X_QUOTE = """[data-testid=quoteTweet], [data-testid="card.wrapper"]"""
    private const val ARTICLE = "article, [role=article]"
    private const val BODY = "[itemprop~=articleBody], .entry-content, .post-content, .article-body, .status__content, [data-ad-preview=message], [data-ad-comet-preview=message], [data-e2e=browse-video-desc]"
    private val xHost = Regex(X_HOST, RegexOption.IGNORE_CASE)
    private val xStatus = Regex(X_STATUS, RegexOption.IGNORE_CASE)

    val captureScript = """
        (() => {
          const diagnostics = {stage: 'capture', articles: 0, bodies: 0, matched: 0, candidates: 0};
          const result = (html = "", reason = "ok", review = false) =>
            ({title: document.title.slice(0,240), html, review, diagnostics: {...diagnostics, reason}});
          const fallback = item => {
            if (!item) return result("", "no-body");
            diagnostics.candidates = item.candidates || 0;
            return result(item.html || "", item.reason, !!item.review);
          };
          try {
          const selection = window.__postTextSelection;
          if (selection?.fbHost()) return fallback(selection.fbFallback());
          const xHost = new RegExp(String.raw`$X_HOST`, 'i');
          const xStatus = new RegExp(String.raw`$X_STATUS`, 'i');
          const visible = e => e.getClientRects().length &&
            !e.closest('[hidden],[aria-hidden=true],nav,aside,shreddit-comment,.comment') &&
            getComputedStyle(e).visibility === 'visible';
          const currentX = xHost.test(location.hostname);
          let top;
          let exact=[];
          if (currentX) {
            const id = xStatus.exec(location.pathname)?.[1];
            if (!id) return result("", "not-post-route");
            const articles = Array.from(document.querySelectorAll('$X_ARTICLE')).filter(visible);
            diagnostics.articles = articles.length;
            diagnostics.bodies = Array.from(document.querySelectorAll('[data-testid=tweetText]')).filter(visible).length;
            const qualifies = a => a.querySelector('time') ||
              (!a.closest('[data-testid=tweetText],p,[role=paragraph],[dir=auto][lang]') &&
               !Array.from(a.parentElement?.childNodes || []).some(n =>
                 n.nodeType === Node.TEXT_NODE && n.nodeValue.trim().length > 8));
            const idFor = a => xHost.test(a.hostname) && xStatus.exec(a.pathname)?.[1];
            const structuralQuotes = new WeakMap();
            const nestedQuote = wrapper => {
              if (!wrapper?.matches('div[role=link][tabindex="0"]') || wrapper.children.length !== 2)
                return null;
              if (structuralQuotes.has(wrapper)) return structuralQuotes.get(wrapper);
              const children = Array.from(wrapper.children);
              const anchor = children.find(n => n.matches('a[href][tabindex="-1"]') &&
                !n.childElementCount && !n.textContent.trim());
              const quote = children.find(n => n.matches('article'));
              const outer = wrapper.closest('$X_ARTICLE');
              const quoteId = anchor && idFor(anchor);
              const verified = outer && quote && quoteId &&
                Array.from(quote.querySelectorAll('a[href]')).slice(0,60).some(link =>
                  link.closest('$X_ARTICLE') === quote && qualifies(link) && idFor(link) === quoteId)
                ? {anchor, quote} : null;
              structuralQuotes.set(wrapper, verified);
              return verified;
            };
            const belongs = (node, article) => node.closest('$X_ARTICLE') === article &&
              !article.contains(node.closest('$X_QUOTE')) && nestedQuote(node.parentElement)?.anchor !== node;
            const ids = article => Array.from(article.querySelectorAll('a[href]'))
              .filter(a => belongs(a, article) && qualifies(a))
              .map(idFor).filter(Boolean);
            exact = articles.filter(a => {
              if (nestedQuote(a.parentElement)?.quote === a) return false;
              const ownIds = ids(a);
              return ownIds.includes(id) && ownIds.every(value => value === id);
            });
            top = exact;
            diagnostics.matched = top.length;
            if (top.length > 1) return result("", "ambiguous-post");
            if (top.length === 0) {
              // Review only, even if a body is unmistakably readable. A
              // same-author/profile link never establishes the post ID.
              if (!selection) return result("", "no-post-identity");
              const eligible = articles.filter(a => ids(a).length === 0);
              diagnostics.eligible = eligible.length;
              return fallback(selection.xIdentityGap(eligible,id));
            }
          } else {
            const picked = Array.from(document.querySelectorAll(
              'shreddit-post, .thing.link, #post_1, $ARTICLE, $BODY')).filter(visible);
            if (picked.length > 120) return result("", "candidate-limit");
            top = picked.filter(e => !picked.some(p => p !== e && p.contains(e)));
          }
          let html = "";
          for (const el of top) {
            if (currentX) {
              const bodies = Array.from(el.querySelectorAll('[data-testid=tweetText]')).filter(b =>
                visible(b) && b.closest('$X_ARTICLE') === el && !el.contains(b.closest('$X_QUOTE')));
              if (bodies.length !== 1 || bodies[0].outerHTML.length > $MAX_HTML) continue;
              const wrapper = document.createElement('article');
              wrapper.dataset.postExportX = xStatus.exec(location.pathname)[1];
              wrapper.append(bodies[0].cloneNode(true));
              html += wrapper.outerHTML;
              if (html.length > $MAX_HTML) return result("", "capture-limit");
              continue;
            }
            if (el.outerHTML.length > $MAX_HTML) return result("", "capture-limit");
            const clone = el.cloneNode(true);
            clone.querySelectorAll('script,style,template,form,input,textarea,iframe,svg,nav,aside,shreddit-comment').forEach(n=>n.remove());
            html += clone.outerHTML;
            if (html.length > $MAX_HTML) return result("", "capture-limit");
          }
          if (html) return result(html);
          if (currentX && top.length === 1 && selection) {
            return fallback(selection.xFallback(top[0], xStatus.exec(location.pathname)[1], exact.includes(top[0])));
          }
          return fallback(!currentX && selection ? selection.genericFallback() : null);
          } catch (error) {
            return result("", "script-error");
          }
        })()
    """.trimIndent()

    private const val START_SELECTION = "window.__postTextSelection.start(); true"
    private const val STOP_SELECTION = "window.__postTextSelection?.stop(); true"
    private const val READ_SELECTION = "window.__postTextSelection.selection()"

    fun beginSelection(asset: String): String = "(() => { $asset; $START_SELECTION })()"
    val endSelection: String get() = "(() => { $STOP_SELECTION })()"
    fun readSelection(asset: String): String =
        "(() => { if (!window.__postTextSelection) { $asset }; return $READ_SELECTION })()"
    fun capture(asset: String): String =
        "(() => { $asset; window.__postTextSelection?.stop(); return $captureScript })()"

    data class Post(val title: String, val body: String, private val formattedBody: String) {
        fun text(): String = "$title\n\n$body\n"
        fun html(): String {
            val heading = Entities.escape(title)
            return """
                <!doctype html>
                <html>
                <head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>$heading</title></head>
                <body><main><article><h1>$heading</h1>
                $formattedBody
                </article></main></body>
                </html>
            """.trimIndent() + "\n"
        }
    }

    private val redditHosts = setOf("reddit.com", "www.reddit.com", "old.reddit.com", "new.reddit.com", "m.reddit.com", "redd.it")
    private val redditPost = Regex("""^/(?:r/[^/]+/)?comments/([a-z0-9]+)(?:/[^/]+)?/?$""", RegexOption.IGNORE_CASE)
    private val accountRoutes = setOf("login", "log-in", "signin", "sign-in", "signup", "register", "auth", "oauth", "consent")
    private const val noise = "nav, aside, form, button, input, textarea, select, script, style, template, iframe, svg, " +
        "footer, [hidden], [aria-hidden=true], [role=navigation], [role=alert], " +
        "shreddit-comment, .comment, .comments, .comment-list, .commentarea, #comments, .related, .related-posts, .share-buttons"
    private val hiddenStyle = Regex("""(?:^|;)\s*(?:display\s*:\s*none|visibility\s*:\s*hidden)\s*(?:!important\s*)?(?:;|$)""", RegexOption.IGNORE_CASE)
    private val blocks = setOf("p", "div", "section", "article", "blockquote", "pre", "ul", "ol", "table",
        "h1", "h2", "h3", "h4", "h5", "h6", "hr")
    private val rejected = setOf("[removed]", "[deleted]", "[unavailable]")
    private val whitespace = Regex("\\s+")
    private val safeHtml = Safelist.none()
        .addTags("a", "article", "b", "blockquote", "br", "caption", "code", "dd", "del", "div", "dl", "dt",
            "em", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "i", "li", "ol", "p", "pre", "s", "section",
            "strong", "sub", "sup", "table", "tbody", "td", "th", "thead", "tr", "u", "ul")
        .addAttributes("a", "href")
        .addProtocols("a", "href", "http", "https")

    fun parse(initialUrl: String, displayedUrl: String, pageTitle: String, html: String): Post? {
        if (html.length !in 1..MAX_HTML) return null
        val initial = safeUri(initialUrl) ?: return null
        val uri = safeUri(displayedUrl) ?: return null
        val doc = Jsoup.parseBodyFragment(html, displayedUrl)
        doc.select(noise).remove()
        doc.select("[style]").filter { hiddenStyle.containsMatchIn(it.attr("style")) }.forEach { it.remove() }
        val displayedReddit = uri.host.lowercase() in redditHosts
        if (initial.host.lowercase() in redditHosts && !displayedReddit) return null
        if (xHost.matches(initial.host) && !xHost.matches(uri.host)) return null
        if (xHost.matches(uri.host)) return parseX(initial, uri, doc.body())
        if (displayedReddit) {
            val id = redditPost.matchEntire(uri.path)?.groupValues?.get(1)?.lowercase() ?: return null
            val originalId = redditPost.matchEntire(initial.path)?.groupValues?.get(1)
            if (initial.host.lowercase() in redditHosts && originalId != null && !originalId.equals(id, true)) return null
            val post = doc.select("shreddit-post, .thing.link").filter {
                listOf("id", "data-fullname", "thingid", "post-id").any { name -> it.attr(name).equals("t3_$id", true) }
            }.singleOrNull() ?: return null
            val body = if (post.tagName() == "shreddit-post")
                post.selectFirst("[slot=text-body], [data-testid=post-content], [id=t3_${id}-post-rtjson-content]")
            else post.selectFirst(".expando .usertext-body .md")
            val title = post.attr("post-title").ifBlank {
                post.selectFirst("[slot=title], h1, a.title")?.text().orEmpty()
            }.ifBlank { pageTitle }
            return body?.let { makePost(title, it) }
        }

        if (uri.path.isNullOrBlank() || uri.path == "/" ||
            uri.path.trim('/').substringBefore('/').lowercase() in accountRoutes
        ) return null
        val explicit = doc.select("[itemprop~=articleBody]")
        val firstForumPost = doc.select("#post_1 .cooked")
        val roots = doc.select(ARTICLE).filter { element ->
            element.parents().none { it.`is`(ARTICLE) }
        }
        val linked = roots.filter { article ->
            article.select("a[rel~=bookmark][href], a[href] time").any { marker ->
                val link = marker.closest("a[href]") ?: return@any false
                val target = safeUri(link.absUrl("href")) ?: return@any false
                link.closest(ARTICLE) == article && samePage(target, uri)
            }
        }
        val primary = when {
            firstForumPost.isNotEmpty() -> firstForumPost.singleOrNull()
            linked.isNotEmpty() -> linked.singleOrNull()?.let(::articleBody)
            roots.size > 1 -> null
            explicit.isNotEmpty() -> explicit.singleOrNull()
            roots.isNotEmpty() -> roots.singleOrNull()?.let(::articleBody)
            else -> doc.select(BODY).singleOrNull()
        } ?: return null
        primary.select(ARTICLE).filter { it != primary }.forEach { it.remove() }
        val root = primary.closest("article, [role=article]") ?: primary
        val heading = root.selectFirst("h1, [itemprop=headline]")
        val title = heading?.text().orEmpty().ifBlank { pageTitle }
        if (heading != null && heading.parents().contains(primary)) heading.remove()
        return makePost(title, primary)
    }

    private fun articleBody(root: Element): Element? {
        val bodies = root.select(BODY).filter { it.closest(ARTICLE) == root }
        val outer = bodies.filter { body -> bodies.none { it != body && body.parents().contains(it) } }
        return when {
            outer.isNotEmpty() -> outer.singleOrNull()
            root.selectFirst("h1, [itemprop=headline]") != null && root.selectFirst("p") != null -> root
            else -> null
        }
    }

    private fun samePage(a: URI, b: URI): Boolean =
        a.host.equals(b.host, true) && a.path.trimEnd('/') == b.path.trimEnd('/') &&
            (a.rawQuery == null || a.rawQuery == b.rawQuery)

    private fun parseX(initial: URI, current: URI, document: Element): Post? {
        val id = xStatus.matchEntire(current.path)?.groupValues?.get(1) ?: return null
        val initialId = xStatus.matchEntire(initial.path)?.groupValues?.get(1)
        if (xHost.matches(initial.host) && initialId != null && initialId != id) return null
        val article = document.select("article[data-post-export-x]").filter {
            it.attr("data-post-export-x") == id
        }.singleOrNull() ?: return null
        val body = article.select("[data-testid=tweetText]").singleOrNull() ?: return null
        body.select("$X_QUOTE, div[role=link]").filter { it != body }.forEach { it.remove() }
        body.select("img").forEach { image ->
            val alt = image.attr("alt").take(32)
            if (alt.isBlank()) image.remove() else image.replaceWith(TextNode(alt))
        }
        fun preserveLines(node: Node) {
            node.childNodes().toList().forEach { child ->
                if (child is TextNode && '\n' in child.wholeText) {
                    val lines = child.wholeText.split('\n')
                    lines.forEachIndexed { index, line ->
                        if (index > 0) child.before(Element("br"))
                        if (line.isNotEmpty()) child.before(TextNode(line))
                    }
                    child.remove()
                } else preserveLines(child)
            }
        }
        preserveLines(body)
        return makePost("X post $id", body)
    }

    fun fromSelectedText(title: String, text: String): Post? {
        if (text.length !in 1..MAX_HTML || text.isBlank()) return null
        val paragraph = Element("p").text(text)
        val html = paragraph.html().replace("\n", "<br>")
        return Post(title.replace(whitespace, " ").trim().take(200).ifBlank { "Saved post" },
            text.trim(), "<p>$html</p>")
    }

    private fun safeUri(raw: String): URI? = runCatching { URI(raw) }.getOrNull()?.takeIf {
        it.scheme in setOf("http", "https") && it.host != null && it.userInfo == null
    }

    private fun makePost(title: String, body: Element): Post? {
        val out = StringBuilder()
        fun walk(node: Node) {
            if (node is TextNode) {
                val text = node.wholeText
                if (node.parent()?.nodeName() == "pre") out.append(text) else out.append(text.replace(whitespace, " "))
            }
            if (node is Element) {
                val tag = node.normalName()
                if (tag == "br") out.append('\n')
                if (tag in blocks) out.append("\n\n")
                if (tag == "li") out.append("\n- ")
                node.childNodes().forEach(::walk)
                if (tag in blocks) out.append("\n\n")
            }
        }
        walk(body)
        val text = out.toString().lineSequence().map { it.trim() }.joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n").trim()
        if (text.isBlank() || text.lowercase() in rejected) return null
        return Post(
            title.replace(whitespace, " ").trim().take(200).ifBlank { "Saved post" },
            text,
            Jsoup.clean(body.html(), body.baseUri(), safeHtml)
        )
    }
}
