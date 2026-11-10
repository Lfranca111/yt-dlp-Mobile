package com.linfranca.ytdlpmobile

import android.annotation.SuppressLint
import android.os.*
import android.content.Intent
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.json.*
import java.net.URI
import java.util.UUID

/** Guided setup only runs in a visible browser. Imported recipes never execute JavaScript. */
class CustomSourceActivity: DisplayActivity() {
    private lateinit var web: WebView
    private var browserOpened=false
    private var resetAfterSave=false
    private var initialRecipe=""
    companion object { private val processSession=UUID.randomUUID().toString() }
    private lateinit var name: EditText
    private lateinit var url: EditText
    private lateinit var links: EditText
    private lateinit var prefix: EditText
    private lateinit var pattern: EditText
    private lateinit var identity: EditText
    private lateinit var liveSelector: EditText
    private lateinit var offlineSelector: EditText
    private lateinit var online: EditText
    private lateinit var offline: EditText
    private lateinit var nextSelector: EditText
    private lateinit var mode: Spinner
    private lateinit var uploadPanel: LinearLayout
    private lateinit var livePanel: LinearLayout
    private lateinit var uploadStatus: TextView
    private lateinit var liveStatus: TextView
    private lateinit var recordingStatus: TextView
    private lateinit var liveLocation: Spinner
    private lateinit var liveLinkRow: LinearLayout
    private lateinit var liveLinkSelector: EditText
    private lateinit var allowUnconfirmedAudio: CheckBox
    private var uploadValidated=""
    private var liveValidated=""
    private var lastLivePage=""
    private var recordingTestAt=0L
    private var linkPicking=false
    private lateinit var newest: CheckBox
    private lateinit var topStatus: TextView
    private lateinit var bottomStatus: TextView
    private lateinit var saveButton: Button
    private lateinit var checkButton: Button
    private lateinit var videoOnly: CheckBox
    private lateinit var structured: CheckBox
    private lateinit var desktop: CheckBox
    private lateinit var accountText: EditText
    private var discovery="links"
    private var idAttribute=""
    private var urlTemplate=""
    private var jsonRecordKind=""
    private var exampleUrls=listOf<String>()
    private val basket=linkedMapOf<String,JSONObject>()
    private var documentScript: androidx.webkit.ScriptHandler?=null
    private var pickGeneration=0
    private var sourceId=UUID.randomUUID().toString()
    private var tested=""
    private var browserOnly=true
    private var busy=false
    private var scanToken=0
    private var destroyed=false
    private var picking: Boolean?=null
    private val browserNavigation = BrowserWebNavigation.Session()
    private fun openInSetupBrowser(target: String) = web.loadUrl(browserNavigation.open(target))
    private var pendingReload: (() -> Unit)?=null
    private val handler=Handler(Looper.getMainLooper())
    private val io=java.util.concurrent.Executors.newSingleThreadExecutor()
    private val pictures=java.util.concurrent.Executors.newFixedThreadPool(2)
    private val learning by lazy { assets.open("monitoring/channel-learning.js").bufferedReader().use { it.readText() } }
    private val ruleId get()=intent.getStringExtra("rule").orEmpty()
    private val checkOnly get()=intent.getBooleanExtra("check_only",false)
    private val exporter=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null) {
            val j=recipe()
            task {
                CustomSources.validate(j)
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(j.toString(2)) } ?: error("Cannot write source file")
                "Source exported. Login cookies, schedules and history are not included."
            }
        }
    }
    private fun report(value: String) {
        runOnUiThread {
            if(!destroyed) { topStatus.text=value;bottomStatus.text=value }
        }
    }
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root=AdaptivePairLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL;browserFirst=true }
        val upper=LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val form=LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(8)) }
        fun text(parent: LinearLayout,value: String,size: Float=16f) { parent.addView(TextView(this).apply { text=value;textSize=size;setPadding(0,dp(6),0,dp(4)) }) }
        topStatus=TextView(this).apply { accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE;form.addView(this) }
        bottomStatus=TextView(this).apply {
            setPadding(dp(12),dp(6),dp(12),dp(6));maxLines=5
            movementMethod=android.text.method.ScrollingMovementMethod()
            setOnClickListener { AlertDialog.Builder(this@CustomSourceActivity).setTitle("Current result").setMessage(text).setPositiveButton("OK",null).show() }
        }
        fun field(parent: LinearLayout,label: String,help: String,initial: String=""): EditText {
            text(parent,label);text(parent,help,13f)
            return EditText(this).apply { hint=label;setText(initial);isSingleLine=true;parent.addView(this) }
        }
        fun button(parent: LinearLayout,label: String,action: ()->Unit): Button = Button(this).apply {
            text=label;isAllCaps=false;parent.addView(this)
            setOnClickListener { try { action() } catch(e: Exception) { report(MonitoringStore.error(e)) } }
        }
        text(form,"1. Choose a channel",22f)
        name=field(form,"Name","Your own label, such as My favourite creator.")
        url=field(form,"Channel address","Use the channel/list page for uploads or linked live streams, or the player page for a stream that plays here.")
        text(form,"What should we watch?")
        mode=PanelSpinner(this).apply { adapter=ArrayAdapter(this@CustomSourceActivity,android.R.layout.simple_spinner_dropdown_item,listOf("New uploads","Live streams","Both"));form.addView(this) }
        newest=CheckBox(this).apply { text="This channel lists newest uploads first" }
        web=WebView(this).apply {
            settings.javaScriptEnabled=true;settings.domStorageEnabled=true;settings.mediaPlaybackRequiresUserGesture=true
            CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(this,true)
            webViewClient=object: WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                    if (r == null) return true
                    val target = r.url
                    if (BrowserWebNavigation.isWebScheme(target.scheme)) {
                        if (r.isForMainFrame && r.hasGesture() && !r.isRedirect) {
                            val original = target.toString()
                            val cleaned = browserNavigation.open(original)
                            if (cleaned != original && v != null) {
                                v.loadUrl(cleaned)
                                return true
                            }
                        }
                        return false
                    }
                    if (r.isForMainFrame && BrowserWebNavigation.isAppScheme(target.scheme)) {
                        val recovered = browserNavigation.recover(target.toString(), r.hasGesture() && !r.isRedirect)
                        if (recovered != null && v != null) v.loadUrl(recovered)
                        else report(BrowserWebNavigation.APP_LINK_BLOCKED)
                    }
                    return true
                }
                override fun onPageFinished(v: WebView?,u: String?) {
                    browserNavigation.pageVisible(u)
                    MonitoringSession.flush(this@CustomSourceActivity,"custom channel page finished")
                    web.evaluateJavascript(learning+";window.__channelLearning&&window.__channelLearning.configure("+recipe().toString()+")",null)
                    val action=pendingReload;pendingReload=null
                    val expectedToken=scanToken
                    if(action!=null) handler.postDelayed({
                        if(!destroyed && scanToken==expectedToken) {
                            try { action() }
                            catch(e: Exception) { busy=false;pendingReload=null;report(MonitoringStore.error(e)) }
                        }
                    },1500)
                }
            }
        }
        desktop=CheckBox(this).apply { text="Request desktop site (unchecked = Mobile)";form.addView(this) }
        desktop.setOnCheckedChangeListener { _,_ ->
            tested="";MonitoringSession.apply(web,if(desktop.isChecked) "desktop" else "mobile")
            if(!web.url.isNullOrBlank()) web.reload()
            if(this::liveStatus.isInitialized) updateModeSections()
            report("Browser mode changed. Login is retained; retest learned rules before saving. Desktop mode requests a desktop page but websites may ignore it.")
        }
        accountText=field(form,"Unique signed-in account text (optional)","After signing in, enter a short account name/message visible on this channel only while signed in. Kept privately on this phone, excluded from source export. Do not enter a password or token.")
        button(form,"Save login & test session reuse") {
            check(!busy);val j=recipe();val address=MonitoringLinkPolicy.input(url.text.toString(),false)
            MonitoringSession.save(this,address,j.getString("browserMode"),accountText.text.toString())
            report("Testing session reuse in a separate temporary browser view…")
            busy=true;val sessionToken=++scanToken
            task { try { MonitoringSession.test(this,address,j.getString("browserMode"),{ destroyed || sessionToken!=scanToken },runCatching { CustomSources.validate(j);j }.getOrNull()) } finally { runOnUiThread { busy=false } } }
        }
        button(form,"Open channel / Sign in") { check(!busy) { "Wait for the current scan, or press Cancel scan." };tested="";openChannel();report(if(mode.selectedItemPosition==1) "Open the configured live page or live listing. Sign in and play the broadcast if available." else "Open the channel's Videos/Uploads tab below. Sign in if the website asks.") }
        button(form,"Use the page I am viewing as the channel") { check(!busy);url.setText(web.url.orEmpty());tested="";report("Channel address copied. Next choose example videos or live indicators.") }
        button(form,"Return to configured channel") { check(!busy);openChannel();report("Loading the configured channel. Wait for its videos to appear before checking.") }
        button(form,"Back in browser") { if(web.canGoBack()) web.goBack() }
        uploadPanel=LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL;form.addView(this) }
        text(uploadPanel,"Upload setup",22f)
        uploadPanel.addView(newest)
        text(uploadPanel,"Leave this unchecked if unsure. Without dates or a confirmed order, newly encountered videos are held for review instead of downloaded automatically.",13f)
        text(uploadPanel,"For uploads, teach the app with two different item pages. Find posts/cards automatically, tap an item on the webpage, or paste share links. Review text and previews to exclude unrelated content.",13f)
        button(uploadPanel,"Find example posts / videos") { chooseExamples() }
        button(uploadPanel,"Show other page links") { chooseExamples(true) }
        button(uploadPanel,"Select an item on the webpage") { selectItem() }
        button(uploadPanel,"Review selected items / Generate rules") { reviewBasket() }
        button(uploadPanel,"Paste example post / video links") { pasteExamples() }
        button(uploadPanel,"Use currently opened item & return to channel") { captureOpenedItem() }
        text(uploadPanel,"If selecting cannot resolve an address: open the item normally, then use the button above, or copy its share link and paste it.",13f)
        val advanced=LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL;visibility=View.GONE }
        button(uploadPanel,"Show / hide Advanced upload settings") { advanced.visibility=if(advanced.visibility==View.VISIBLE) View.GONE else View.VISIBLE }
        uploadPanel.addView(advanced)
        videoOnly=CheckBox(this).apply { text="Only items with detected video evidence";advanced.addView(this) }
        text(advanced,"Leave unchecked for thumbnails/posts whose media type is unknown until opened. Confirmed video evidence is not available on every website.",13f)
        structured=CheckBox(this).apply { text="Include structured page / observed JSON records";isChecked=true;advanced.addView(this) }
        links=field(advanced,"Video links selector","Generated from your examples. Restricts matching to the channel's content.","a[href]")
        prefix=field(advanced,"Video path prefix","Generated from the example addresses. For example /watch or /videos/.","/videos/")
        pattern=field(advanced,"Video path pattern","Generated structural pattern. A * stands for one changing path segment.")
        identity=field(advanced,"Video ID fields","Generated query-field names, separated by commas. Tracking fields are ignored.")
        nextSelector=field(advanced,"Next results page selector","Optional channel-list pagination. Default uses rel=next; never select Next video.","a[rel=next]")
        text(uploadPanel,"Testing scrolls the visible channel list and follows compatible next-result links within limits. It then reloads and checks again. You will review the results before saving.",13f)
        button(uploadPanel,"Test learned rules, reload & review") { testSource() }
        uploadStatus=TextView(this).apply { text="Upload rules: not tested";uploadPanel.addView(this) }

        livePanel=LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL;form.addView(this) }
        text(livePanel,"Live setup",22f)
        text(livePanel,"Where is the live stream?")
        liveLocation=PanelSpinner(this).apply {
            adapter=ArrayAdapter(this@CustomSourceActivity,android.R.layout.simple_spinner_dropdown_item,listOf("The stream plays on this page","This page links to the live stream"));livePanel.addView(this)
        }
        liveLinkRow=LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL;livePanel.addView(this) }
        button(liveLinkRow,"Select the live card / button / link on the page") { pickLiveLink() }
        liveLinkSelector=field(liveLinkRow,"Live destination selector","A structural rule for the live card/link, not one saved broadcast address. Narrow it if several destinations match.")
        button(livePanel,"Pick the on-screen LIVE message (optional)") { pickIndicator(true) }
        button(livePanel,"Pick the on-screen OFFLINE message (optional)") { pickIndicator(false) }
        text(livePanel,"You can learn while the broadcast is live. Without an explicit live indicator, testing requires an advancing live playlist. An offline sample is optional; missing evidence means unknown. No uploaded-video examples are required.",13f)
        val liveAdvanced=LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL;visibility=View.GONE }
        button(livePanel,"Show / hide Advanced live settings") { liveAdvanced.visibility=if(liveAdvanced.visibility==View.VISIBLE) View.GONE else View.VISIBLE }
        livePanel.addView(liveAdvanced)
        liveSelector=field(liveAdvanced,"Live indicator selector","Generated when you pick the live message.")
        online=field(liveAdvanced,"Live text","Optional exact text observed while this channel is live.")
        offlineSelector=field(liveAdvanced,"Offline indicator selector","Generated when you pick the offline message.")
        offline=field(liveAdvanced,"Offline text","Optional explicit offline text, distinct from the live text.")
        allowUnconfirmedAudio=CheckBox(this).apply { text="Allow live recording when audio cannot be confirmed (may be silent)";liveAdvanced.addView(this) }
        button(livePanel,"Test live destination / playback") { testLiveSource() }
        liveStatus=TextView(this).apply { text="Live state, destination and media: not tested";livePanel.addView(this) }
        button(livePanel,"Test recording in Browser Assist") { testRecording() }
        recordingStatus=TextView(this).apply { text="Recording start: not tested. Use the existing recorder for a short sample, then stop and save.";livePanel.addView(this) }
        mode.onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long) { updateModeSections() }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        liveLocation.onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long) { updateModeSections() }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        text(form,"Save custom source",22f)
        saveButton=button(form,"Save tested source") {
            val j=recipe();check(CustomSetupPolicy.ready(j)) { "Test and confirm each required section: uploads, live, or both. Changed settings need retesting." }
            j.put("testedAt",System.currentTimeMillis()).put("browserOnly",browserOnly)
            CustomSources.put(this,j);MonitoringSession.save(this,j.getString("url"),j.getString("browserMode"),accountText.text.toString());updateCheckButton()
            val savedMessage="Source saved. "+if(MonitoringStore.rules(this).any { it.customSource==sourceId }) "Use Check channel now to check linked rules." else "Return to Monitoring → Add monitoring link → choose this custom source. "+when(j.getString("mode")) {
                "live"->"A verified live broadcast can then be queued for recording."
                "both"->"Uploads establish an initial baseline; verified live broadcasts can be queued for recording."
                else->"The first upload check records current videos and downloads nothing."
            }
            resetAfterSave=true;scanToken++;pickGeneration++;busy=false;pendingReload=null
            intent.removeExtra("source");intent.removeExtra("rule");intent.removeExtra("check_only");intent.removeExtra("check_now")
            intent.putExtra("saved_notice",savedMessage);recreate()
        }
        button(form,"Export source file") { CustomSources.validate(recipe());exporter.launch("custom-monitoring-source.json") }
        button(form,"Help: complete setup and checking guide") { startActivity(Intent(this,InstructionsActivity::class.java)) }
        button(form,"Return to Monitoring") { leaveSetup() }
        text(form,"The result is repeated below this panel. Tap that result for the complete message.",13f)
        if(checkOnly) {
            // Keep saved recipe fields available internally, but ordinary checks do not expose the editor.
            for(i in 0 until form.childCount) form.getChildAt(i).visibility=View.GONE
            topStatus.visibility=View.VISIBLE
            text(form,"Check monitored channel",22f)
            text(form,"Uses this source's saved rules and browser login. Sign in or complete verification below if asked, then press Check channel now. A successful check returns to Monitoring automatically.")
            button(form,"Back in browser") { if(web.canGoBack()) web.goBack() }
            button(form,"Return to configured channel") { check(!busy);openInSetupBrowser(url.text.toString());report("Loading the configured channel; wait for content before checking.") }
            button(form,"Return to Monitoring") { leaveSetup() }
        }
        val scroll=ScrollView(this).apply { addView(form) }
        upper.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));upper.addView(bottomStatus)
        val actions=LinearLayout(this)
        checkButton=button(actions,"Check channel now") { checkSaved() };checkButton.layoutParams=LinearLayout.LayoutParams(0,-2,1f)
        button(actions,"Cancel scan / selection") { scanToken++;pickGeneration++;busy=false;pendingReload=null;web.evaluateJavascript("window.__channelLearning&&window.__channelLearning.cancelPick();if(window.__customLivePick){document.removeEventListener('click',window.__customLivePick,true);window.__customLivePick=null}",null);linkPicking=false;report("Scan/selection canceled. Existing baseline and saved files were retained.") }.layoutParams=LinearLayout.LayoutParams(0,-2,1f)
        upper.addView(actions)
        root.addView(upper,LinearLayout.LayoutParams(-1,0,1f));root.addView(web,LinearLayout.LayoutParams(-1,0,1f));web.visibility=View.GONE;setContentView(root)
        var openCheck=intent.getBooleanExtra("check_now",false)
        val draft=b?.takeIf { it.getString("draft_process")==processSession }?.getString("custom_draft")?.let { runCatching { JSONObject(it) }.getOrNull() }
        val loaded=draft ?: if(checkOnly && ruleId.isNotBlank()) MonitoringStore.get(this,ruleId)?.let { CustomSources.source(this,it) }
            else intent.getStringExtra("source")?.let { id -> CustomSources.all(this).find { it.getString("id")==id } }
        loaded?.let { j -> val id=j.getString("id")
            desktop.isChecked=j.optString("browserMode","mobile")=="desktop";accountText.setText(MonitoringSession.marker(this,j.getString("url")))
            discovery=j.optString("discovery","links");idAttribute=j.optString("idAttribute");urlTemplate=j.optString("urlTemplate");jsonRecordKind=j.optString("jsonRecordKind")
            exampleUrls=j.optJSONArray("examples")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            videoOnly.isChecked=j.optBoolean("videoOnly");structured.isChecked=j.optBoolean("structured",true)
            sourceId=id;name.setText(j.getString("name"));url.setText(j.getString("url"));links.setText(j.optString("linkSelector"));prefix.setText(j.optString("pathPrefix"));pattern.setText(j.optString("pathPattern"))
            identity.setText(CustomUploadPolicy.keys(j).joinToString(","));liveSelector.setText(j.optString("liveSelector"));offlineSelector.setText(j.optString("offlineSelector"))
            online.setText(j.optString("liveText"));offline.setText(j.optString("offlineText"));nextSelector.setText(j.optString("nextSelector","a[rel=next]"));newest.isChecked=j.optBoolean("newestFirst")
            mode.setSelection(listOf("uploads","live","both").indexOf(j.getString("mode")));browserOnly=j.optBoolean("browserOnly")
            liveLocation.setSelection(if(j.optString("liveLocation","page")=="linked") 1 else 0)
            liveLinkSelector.setText(j.optString("liveLinkSelector"));allowUnconfirmedAudio.isChecked=j.optBoolean("liveAllowUnconfirmedAudio")
            uploadValidated=j.optString("uploadValidated");liveValidated=j.optString("liveValidated")
            if(j.optLong("testedAt")>0 && j.getInt("schema")<4) {
                uploadValidated=CustomSetupPolicy.fingerprint(recipe(),"uploads")
            }
            if(j.optLong("testedAt")>0) tested=recipe().toString()
            if(openCheck) { openCheck=false;pendingReload={ checkSaved() } }
            if(checkOnly || intent.getBooleanExtra("check_now",false) || b?.getBoolean("browser_open")==true && b.getString("draft_process")==processSession) openChannel()
        }
        updateModeSections()
        val watcher=object: android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
            override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) { updateModeSections() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        listOf(url,links,prefix,pattern,identity,liveSelector,offlineSelector,online,offline,nextSelector,liveLinkSelector).forEach { it.addTextChangedListener(watcher) }
        listOf(newest,videoOnly,structured,allowUnconfirmedAudio).forEach { it.setOnCheckedChangeListener { _,_-> updateModeSections() } }
        updateCheckButton()
        initialRecipe=recipe().toString()
        val savedNotice=intent.getStringExtra("saved_notice");intent.removeExtra("saved_notice")
        report(savedNotice ?: if(checkOnly && ruleId.isNotBlank()) "Check this monitoring rule using its own channel and the shared website layout. Sign in if necessary." else if(ruleId.isNotBlank()) "Editing shared source rules using the setup example. To inspect another channel, use Open browser to check on its monitoring rule." else "Start at step 1. The app learns rules from selected examples; Advanced settings are optional.")
        onBackPressedDispatcher.addCallback(this,object: androidx.activity.OnBackPressedCallback(true) { override fun handleOnBackPressed() { if(checkOnly) finish() else if(browserOpened && web.canGoBack() && !busy) web.goBack() else leaveSetup() } })
    }
    private fun recipe()=JSONObject().put("schema",4).put("id",sourceId).put("name",name.text.toString().trim()).put("url",url.text.toString().trim())
        .put("mode",listOf("uploads","live","both")[mode.selectedItemPosition]).put("linkSelector",links.text.toString().trim()).put("pathPrefix",prefix.text.toString().trim())
        .put("pathPattern",pattern.text.toString().trim()).put("identityKeys",JSONArray(identity.text.toString().split(',').map { it.trim() }.filter { it.isNotBlank() }))
        .put("newestFirst",newest.isChecked).put("liveSelector",liveSelector.text.toString().trim()).put("offlineSelector",offlineSelector.text.toString().trim())
        .put("liveText",online.text.toString().trim()).put("offlineText",offline.text.toString().trim()).put("nextSelector",nextSelector.text.toString().trim())
        .put("discovery",discovery).put("idAttribute",idAttribute).put("urlTemplate",urlTemplate).put("examples",JSONArray(exampleUrls))
        .put("jsonRecordKind",jsonRecordKind).put("videoOnly",videoOnly.isChecked).put("structured",structured.isChecked).put("browserMode",if(desktop.isChecked) "desktop" else "mobile")
        .put("liveLocation",if(liveLocation.selectedItemPosition==1) "linked" else "page").put("liveLinkSelector",liveLinkSelector.text.toString().trim())
        .put("liveAllowUnconfirmedAudio",allowUnconfirmedAudio.isChecked).put("uploadValidated",uploadValidated).put("liveValidated",liveValidated)
    private fun updateModeSections() {
        if(checkOnly) { uploadPanel.visibility=View.GONE;livePanel.visibility=View.GONE;return }
        uploadPanel.visibility=if(mode.selectedItemPosition==1) View.GONE else View.VISIBLE
        livePanel.visibility=if(mode.selectedItemPosition==0) View.GONE else View.VISIBLE
        liveLinkRow.visibility=if(liveLocation.selectedItemPosition==1) View.VISIBLE else View.GONE
        val j=recipe()
        uploadStatus.text=if(uploadValidated==CustomSetupPolicy.fingerprint(j,"uploads")) "Upload rules: validated for these settings" else "Upload rules: not tested / settings changed"
        liveStatus.text=if(liveValidated==CustomSetupPolicy.fingerprint(j,"live")) "Live state, destination and media: validated for these settings" else "Live state, destination and media: not tested / settings changed"
    }
    private fun pickLiveLink() {
        check(!busy)
        if(!linkPicking) {
            linkPicking=true
            learning {
                web.evaluateJavascript(assets.open("monitoring/live-learning.js").bufferedReader().use { it.readText() }+";window.__customLive.pickLink()",null)
            }
            report("Tap the card/link that opens the live broadcast, then press Select live card / link again. The rule is saved; its current address is not.")
            return
        }
        web.evaluateJavascript("JSON.stringify(window.__customLivePicked)") { raw ->
            try {
                val picked=JSONObject(JSONTokener(raw).nextValue() as String)
                check(picked.optString("url").isBlank() || CustomLivePolicy.safeTarget(url.text.toString(),picked.getString("url"))!=null) { "Choose a same-site page link or a live button on this source" }
                liveLinkSelector.setText(picked.getString("selector"));linkPicking=false;updateModeSections()
                report("Live link rule selected. Test it to ensure exactly one broadcast destination matches. Refine the selector if it also matches uploaded videos.")
            } catch(e: Exception) { report("Select a live page link or button on the website, then press this button again. ${MonitoringStore.error(e)}") }
        }
    }
    private fun testLiveSource() {
        check(!busy) { "Wait for the current test or cancel it first" }
        val j=recipe().put("mode","live");CustomSources.validate(j)
        val marker=accountText.text.toString()
        liveValidated="";busy=true;val token=++scanToken
        report("Testing live destination, fresh playback and audio separately. No recording starts during this check.")
        io.execute {
            val result=runCatching {
                MonitoringSession.save(this,j.getString("url"),j.getString("browserMode"),marker)
                CustomLiveResolver.resolve(this,j,null,{ destroyed || token!=scanToken })
            }
            runOnUiThread {
                if(destroyed || token!=scanToken) return@runOnUiThread
                busy=false
                if(CustomSetupPolicy.fingerprint(recipe(),"live")!=CustomSetupPolicy.fingerprint(j,"live")) { report("Live settings changed during testing. Test again.");return@runOnUiThread }
                result.onSuccess { live ->
                    if(live.live==null || live.video.isBlank()) {
                        report("Live setup was not verified for an active broadcast: ${live.detail}. Return while the source is live; uploads remain separately configured.")
                    } else {
                        lastLivePage=live.live.url;liveValidated=CustomSetupPolicy.fingerprint(j,"live");updateModeSections()
                        liveStatus.text="Live state: confirmed\nDestination: verified\nMedia: ${live.detail}\nRecording start: separate test required"
                        report("Live setup verified. Use Test recording in Browser Assist for a short recording with the existing recorder, or save when all required sections are validated.")
                    }
                }.onFailure { report("Live setup not verified: ${MonitoringStore.error(it)}. Upload validation is retained.") }
            }
        }
    }
    private fun testRecording() {
        check(!busy)
        check(lastLivePage.isNotBlank() && liveValidated==CustomSetupPolicy.fingerprint(recipe(),"live")) { "First verify the live destination and playback for these settings" }
        recordingTestAt=System.currentTimeMillis()
        startActivity(Intent(this,MonitoringBrowserSessionActivity::class.java)
            .putExtra(MonitoringBrowserSessionActivity.EXTRA_PAGE_URL,lastLivePage)
            .putExtra(MonitoringBrowserSessionActivity.EXTRA_MODE,MonitoringBrowserSessionActivity.MODE_RECORD))
        recordingStatus.text="Recording start: awaiting the existing recorder's Start action. Use Stop & save after a short sample."
    }
    override fun onResume() {
        super.onResume()
        if(recordingTestAt>0 && this::recordingStatus.isInitialized) {
            val job=TransferQueue.jobs(this).asReversed().find { j ->
                if(!j.optBoolean("live") || j.optLong("created")<recordingTestAt) false else runCatching {
                    val request=Intent.parseUri(j.getString("payload"),Intent.URI_INTENT_SCHEME)
                    request.getStringExtra(MonitoringRecordingService.EXTRA_PAGE_URL)==lastLivePage
                }.getOrDefault(false)
            }
            recordingStatus.text=if(job==null) "Recording start: not observed yet. Start a short sample in Browser Assist; detection alone does not prove recording started."
                else "Recording start: ${if(job.optBoolean("engine_started")) "service acknowledged" else "not acknowledged"}\n${job.optString("state")}: ${job.optString("detail")}"
        }
    }
    override fun onSaveInstanceState(out: Bundle) {
        if(!resetAfterSave) { out.putString("custom_draft",recipe().toString());out.putString("draft_process",processSession);out.putBoolean("browser_open",browserOpened) }
        super.onSaveInstanceState(out)
    }
    private fun leaveSetup() {
        if(!checkOnly && initialRecipe.isNotBlank() && recipe().toString()!=initialRecipe)
            AlertDialog.Builder(this).setTitle("Discard unsaved source changes?").setMessage("The changes in this form have not been saved.")
                .setNegativeButton("Keep editing",null).setPositiveButton("Discard") { _,_->finish() }.show()
        else finish()
    }
    private fun validatePage(j: JSONObject) {
        val a=URI(web.url.orEmpty());val b=URI(j.getString("url"))
        check(a.scheme==b.scheme && a.authority==b.authority && a.path.trimEnd('/')==b.path.trimEnd('/') && a.query==b.query) {
            "Return to the configured channel page, or press Use the page I am viewing as the channel. A sign-in/challenge page cannot establish a baseline."
        }
    }
    private fun snapshot(action: (String)->Unit) {
        web.evaluateJavascript(MonitoringDiscoveryScripts.snapshot()) { raw ->
            try { val html=JSONTokener(raw).nextValue() as? String ?: error("Page unavailable");check(html.length<=3000000) { "Page too large; narrow the channel filter" };action(html) }
            catch(e: Exception) { scanToken++;pendingReload=null;busy=false;report(MonitoringStore.error(e)) }
        }
    }
    private fun learning(action: ()->Unit) { web.evaluateJavascript(learning+";window.__channelLearning.configure("+recipe().toString()+")") { action() } }
    private fun openChannel() {
        val channel=MonitoringLinkPolicy.input(url.text.toString(),false)
        browserOpened=true;web.visibility=View.VISIBLE
        pickGeneration++
        documentScript?.remove()
        documentScript=MonitoringDiscoveryScripts.install(this,web,channel,recipe())
        MonitoringSession.apply(web,if(desktop.isChecked) "desktop" else "mobile")
        openInSetupBrowser(channel)
    }
    private fun updateCheckButton() {
        val ready=CustomSources.all(this).any { it.getString("id")==sourceId && it.optLong("testedAt")>0 } &&
            MonitoringStore.rules(this).any { it.customSource==sourceId && (ruleId.isBlank() || it.id==ruleId) }
        checkButton.isEnabled=ready
        checkButton.text=if(ready) "Check channel now" else "Check requires saved source + rule"
        checkButton.contentDescription=if(ready) "Check channel now" else "First test and save the source, then add a monitoring rule. Setup testing remains available above."
    }
    private fun chooseExamples(all: Boolean=false) {
        check(!busy) { "Wait for the scan to finish" }
        learning { web.evaluateJavascript("JSON.stringify(window.__channelLearning.candidates("+all+"))") { raw ->
            try {
                val a=JSONArray(JSONTokener(raw).nextValue() as String)
                val candidates=(0 until minOf(a.length(),60)).map { a.getJSONObject(it) }
                check(candidates.isNotEmpty()) { "No resolvable items found. Select an item on the page, open it then capture its address, or paste example links." }
                showCandidates(candidates)
                MonitoringStore.log(this,"custom discovery examples; source=$sourceId; candidates=${candidates.size}; general cards/links/attributes/structured data; no site adapter")
            } catch(e: Exception) { report(MonitoringStore.error(e)) }
        } }
    }
    private fun showCandidates(candidates: List<JSONObject>) {
        val box=LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL;setPadding(dp(10),dp(8),dp(10),dp(8)) }
        val selected=BooleanArray(candidates.size) { i -> candidates[i].optString("url") in basket }
        candidates.forEachIndexed { i,candidate ->
            val row=LinearLayout(this);val image=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP;contentDescription="Item preview" }
            row.addView(image,LinearLayout.LayoutParams(dp(88),dp(104)));loadPreview(candidate.optString("thumbnail"),image)
            row.addView(CheckBox(this).apply {
                isChecked=selected[i]
                text=candidate.optString("title").ifBlank { "Item page" }+"\n"+candidate.optString("author")+"\n"+candidate.optString("url")+"\n"+candidate.optString("method")+if(candidate.optBoolean("video")) " • video evidence" else " • media type unknown"
                setOnCheckedChangeListener { _,on->selected[i]=on }
            },LinearLayout.LayoutParams(0,-2,1f));box.addView(row)
        }
        val dialog=AlertDialog.Builder(this).setTitle("Choose at least 2 different items belonging to this channel")
            .setView(ScrollView(this).apply { addView(box) }).setPositiveButton("Generate rules",null).setNeutralButton("Add to selection") { _,_->
                candidates.filterIndexed { i,_->selected[i] }.forEach { addExample(it) };report("Selected ${basket.size} distinct item pages. Choose more or Review selected items / Generate rules.")
            }.setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val chosen=candidates.filterIndexed { i,_->selected[i] }
            runCatching {
                check(chosen.size>=2) { "Choose two different items" }
                generateRules(chosen);dialog.dismiss()
            }.onFailure { report(MonitoringStore.error(it));Toast.makeText(this,it.message,Toast.LENGTH_LONG).show() }
        } };dialog.show()
    }
    private fun addExample(item: JSONObject) {
        val address=CustomContentDiscovery.stable(item.getString("url"),url.text.toString()) ?: error("Use a stable item page on the configured website")
        basket[address]=JSONObject(item.toString()).put("url",address)
    }
    private fun reviewBasket() { check(basket.size>=2) { "Select or paste at least two different item pages first" };showCandidates(basket.values.toList()) }
    private fun generateRules(chosen: List<JSONObject>) {
        val addresses=chosen.map { CustomContentDiscovery.stable(it.getString("url"),url.text.toString()) ?: error("Example is not a same-site item page") }.distinct()
        val suggestion=CustomUploadPolicy.suggest(addresses)
        check(addresses.size in 2..20) { "Choose 2 to 20 different item pages. Variants of one item count once." }
        discovery="content";idAttribute="";urlTemplate="";jsonRecordKind="";exampleUrls=addresses;prefix.setText(suggestion.prefix);pattern.setText(suggestion.pattern);identity.setText(suggestion.keys.joinToString(","));tested=""
        learning { web.evaluateJavascript("window.__channelLearning.scope("+JSONArray(addresses).toString()+")") { result ->
            links.setText(runCatching { JSONTokener(result).nextValue() as String }.getOrDefault("body"))
            snapshot { html ->
                val mapping=CustomContentDiscovery.inferMapping(addresses,html)
                idAttribute=mapping?.attribute.orEmpty();urlTemplate=mapping?.template.orEmpty();jsonRecordKind=mapping?.recordKind.orEmpty()
                report("Generated rules from ${addresses.size} distinct item pages. ${if(mapping!=null) "Reusable item-ID mapping found." else "Matching links/attributes/structured page addresses will be used."} Now Test learned rules, reload & review. Pasted examples alone do not establish discovery support.")
                MonitoringStore.log(this,"custom discovery rules generated; source=$sourceId; examples=${addresses.size}; idMapping=${mapping!=null}; reload verification required")
            }
        } }
    }
    private fun pasteExamples() {
        val input=EditText(this).apply { hint="https://example.com/creator/posts/123\nhttps://example.com/creator/posts/456";minLines=4 }
        val d=AlertDialog.Builder(this).setTitle("Paste 2–20 item page/share links")
            .setMessage("Same website as the configured channel. Use post/video page links, not temporary media files. Different tracking links to one item count once.")
            .setView(input).setPositiveButton("Use examples",null).setNegativeButton("Cancel",null).create()
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching {
                val values=CustomContentDiscovery.examples(input.text.toString(),url.text.toString())
                values.forEach { addExample(JSONObject().put("url",it).put("title","Pasted item page").put("method","pasted example")) }
                generateRules(values.map { JSONObject().put("url",it) });d.dismiss()
            }.onFailure { report(MonitoringStore.error(it));Toast.makeText(this,it.message,Toast.LENGTH_LONG).show() }
        } };d.show()
    }
    private fun selectItem() {
        check(!busy);val generation=++pickGeneration
        learning { web.evaluateJavascript("window.__channelLearning.beginItemPick()",null) }
        report("Tap a post, thumbnail or content card below. The app will try to find its stable item address. This selection does not play or download it.")
        val started=SystemClock.elapsedRealtime()
        fun poll() {
            if(destroyed || generation!=pickGeneration) return
            if(SystemClock.elapsedRealtime()-started>30000) { web.evaluateJavascript("window.__channelLearning.cancelPick()",null);report("Selection timed out. Try again or paste an item link.");return }
            web.evaluateJavascript("JSON.stringify(window.__monitorSelectedItem)") { raw ->
                val item=runCatching { JSONObject(JSONTokener(raw).nextValue() as String) }.getOrNull()
                if(item==null) handler.postDelayed({ poll() },350)
                else if(item.optBoolean("unresolved")) report("This card did not expose an address. Open the item normally, then Use currently opened item & return to channel, or paste its share link. No position-based rule was saved.")
                else runCatching { addExample(item);report("Selected ${basket.size} distinct item page(s). Select another, then Review selected items / Generate rules.") }.onFailure { report(MonitoringStore.error(it)) }
            }
        }
        handler.postDelayed({ poll() },350)
    }
    private fun captureOpenedItem() {
        check(!busy)
        learning { web.evaluateJavascript("JSON.stringify(window.__channelLearning.current())") { raw ->
            runCatching {
                val item=JSONObject(JSONTokener(raw).nextValue() as String);addExample(item)
                openChannel();report("Captured item page; selected=${basket.size}. Returned to the configured channel. Capture another item, then review/generate rules.")
            }.onFailure { report(MonitoringStore.error(it)) }
        } }
    }
    private fun loadPreview(address: String,image: ImageView) {
        image.setImageResource(android.R.drawable.ic_menu_gallery)
        if(!address.startsWith("https://")) return
        pictures.execute {
            val bitmap=runCatching {
                val connection=java.net.URL(address).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout=5000;connection.readTimeout=5000;connection.instanceFollowRedirects=false
                try {
                    check(connection.responseCode==200)
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    connection.inputStream.use { input -> while(output.size()<=500000) { val count=input.read(buffer,0,minOf(buffer.size,500001-output.size()));if(count<0) break;output.write(buffer,0,count) } }
                    val bytes=output.toByteArray();check(bytes.size<=500000)
                    android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,android.graphics.BitmapFactory.Options().apply { inSampleSize=4 })
                } finally { connection.disconnect() }
            }.getOrNull()
            runOnUiThread { if(!destroyed && bitmap!=null) image.setImageBitmap(bitmap) }
        }
    }
    private fun pickIndicator(live: Boolean) {
        check(!busy)
        linkPicking=false
        web.evaluateJavascript("if(window.__customLivePick){document.removeEventListener('click',window.__customLivePick,true);window.__customLivePick=null}",null)
        if(picking!=live) {
            picking=live
            learning { web.evaluateJavascript("window.__channelLearning.pick()",null) }
            report("Tap the ${if(live) "LIVE" else "OFFLINE"} message in the webpage below, then tap this same Pick button again to use it.")
            return
        }
        web.evaluateJavascript("JSON.stringify(window.__monitorPicked)") { raw ->
            try {
                val obj=JSONObject(JSONTokener(raw).nextValue() as String)
                check(obj.getString("text").isNotBlank())
                if(live) { liveSelector.setText(obj.getString("selector"));online.setText(obj.getString("text")) }
                else { offlineSelector.setText(obj.getString("selector"));offline.setText(obj.getString("text")) }
                picking=null;tested="";report("${if(live) "Live" else "Offline"} rule filled. Test the source before saving.")
            } catch(_: Exception) { report("Tap a short visible status message in the webpage, then tap the same Pick button again.") }
        }
    }
    data class Scan(val items: List<MonitoringItem>,val html: String,val limited: Boolean,val reason: String,val pages: Int)
    private fun scan(j: JSONObject,done: (Scan)->Unit) {
        check(!busy) { "A scan is already running" };validatePage(j);CustomSources.validate(j)
        busy=true;val token=++scanToken;
        handler.postDelayed({ if(!destroyed && busy && scanToken==token) { scanToken++;busy=false;pendingReload=null;report("Channel scan timed out. Existing baseline retained; retry when the channel has loaded.") } },65000)
        val seen=linkedMapOf<String,MonitoringItem>();val visited=linkedSetOf(web.url.orEmpty())
        var steps=0;var capped=false;var stable=0;var lastCount=-1;val started=SystemClock.elapsedRealtime()
        val live=j.getString("mode")=="live"
        fun next() {
            if(destroyed || token!=scanToken) return
            snapshot { html ->
                val page=web.url.orEmpty()
                io.execute {
                    val parsed=runCatching { if(live) { CustomSources.parse(j,html,true);emptyList() } else CustomSources.parse(j,html,false,page).items }
                    runOnUiThread {
                        if(destroyed || token!=scanToken) return@runOnUiThread
                        if(parsed.isFailure) {
                            busy=false;scanToken++;pendingReload=null
                            report(MonitoringStore.error(parsed.exceptionOrNull()!!)+" Existing baseline retained; no downloads queued.")
                            return@runOnUiThread
                        }
                        capped=capped || parsed.getOrNull().orEmpty().size>=200
                        parsed.getOrNull()?.forEach { seen.putIfAbsent(it.id,it) }
                        stable=if(seen.size==lastCount) stable+1 else 0;lastCount=seen.size;steps++
                        report(if(live) "Checking the live page. Upload examples and upload baselines are not used." else "Scanning channel: ${seen.size} unique videos; page ${visited.size}; step $steps/20. Existing baseline is unchanged until the scan finishes.")
                        val limit=steps>=20 || seen.size>=1000 || SystemClock.elapsedRealtime()-started>60000
                        if(live || limit) {
                            busy=false
                            if(parsed.isFailure && seen.isEmpty()) report(MonitoringStore.error(parsed.exceptionOrNull()!!))
                            else done(Scan(seen.values.toList(),html,limit || capped,if(limit || capped) "Scan limit reached; coverage incomplete" else "Live indicator checked",visited.size))
                        } else web.evaluateJavascript("JSON.stringify({bottom:innerHeight+scrollY>=document.documentElement.scrollHeight-80,next:(document.querySelector("+JSONObject.quote(j.optString("nextSelector").ifBlank { "a[rel=next]" })+")||{}).href||''})") { raw ->
                            try {
                                val observation=JSONObject(JSONTokener(raw).nextValue() as String)
                                if(stable>=3 && observation.optBoolean("bottom")) {
                                    val n=observation.optString("next");val target=runCatching { URI(n) }.getOrNull();val base=URI(j.getString("url"))
                                    val safe=target!=null && target.scheme=="https" && target.authority==base.authority &&
                                        (target.path==base.path || target.path.startsWith(base.path.trimEnd('/')+"/page/")) && n !in visited
                                    if(n.isNotBlank() && safe && visited.size<4) {
                                        visited.add(n);stable=0
                                        pendingReload={ next() };openInSetupBrowser(n)
                                    } else {
                                        busy=false
                                        if(seen.isEmpty()) report(parsed.exceptionOrNull()?.let(MonitoringStore::error) ?: "No matching videos; baseline retained")
                                        else done(Scan(seen.values.toList(),html,n.isNotBlank() || capped,if(capped) "Snapshot match limit reached; coverage incomplete" else if(n.isNotBlank()) "More pages remain or pagination needs review" else "No more loaded items detected; delayed/hidden pages may still exist",visited.size))
                                    }
                                } else {
                                    web.evaluateJavascript("window.scrollTo(0,Math.min(document.documentElement.scrollHeight,scrollY+innerHeight*0.85))",null)
                                    handler.postDelayed({ next() },1000)
                                }
                            } catch(e: Exception) { scanToken++;pendingReload=null;busy=false;report(MonitoringStore.error(e)) }
                        }
                    }
                }
            }
        }
        next()
    }
    private fun testSource() {
        if(mode.selectedItemPosition==1) { testLiveSource();return }
        val j=recipe().put("mode","uploads");tested=""
        scan(j) { before ->
            pendingReload={
                scan(j) { after ->
                    if(j.getString("mode")!="live" && before.items.none { old -> after.items.any { it.id==old.id } }) { report("Video identities did not survive reload. Choose different examples and test again.");return@scan }
                    val expected=exampleUrls.map { "custom:"+CustomUploadPolicy.canonical(it,CustomUploadPolicy.keys(j)) }.toSet()
                    if(expected.isNotEmpty() && after.items.count { it.id in expected }<2) { report("Reload verification did not find two selected examples. Load those items in the channel list, narrow the rules, or choose other examples. Source was not verified.");return@scan }
                    val accountMarker=accountText.text.toString();val sessionToken=scanToken
                    report("Testing session reuse and comparing background matches…")
                    task {
                        MonitoringSession.save(this,j.getString("url"),j.getString("browserMode"),accountMarker)
                        val sessionResult=MonitoringSession.test(this,j.getString("url"),j.getString("browserMode"),{ destroyed || sessionToken!=scanToken },j)
                        val background=runCatching {
                            val result=CustomMonitoringEngine.check(this,MonitoringRule(name=j.getString("name"),url=j.getString("url"),mode=j.getString("mode")),{ destroyed },sourceOverride=j,allowRender=false)
                            if(j.getString("mode")!="live") check(CustomBackgroundPolicy.comparison(after.items,result.uploads?.items.orEmpty())) { "Direct response did not match the reviewed recent channel items" }
                            if(j.getString("mode")!="uploads") check(result.live?.offline==CustomSources.parse(j,after.html,true).offline) { "Browser and direct live states differ" }
                        }
                        runOnUiThread {
                            if(CustomSetupPolicy.fingerprint(recipe(),"uploads")==CustomSetupPolicy.fingerprint(j,"uploads") && !destroyed) reviewMatches(after,"Confirm these are channel videos", "Confirm rules") {
                                browserOnly=background.isFailure;uploadValidated=CustomSetupPolicy.fingerprint(j,"uploads");tested=recipe().toString();updateModeSections()
                                report(sessionResult+"\nTest passed for ${after.items.size} videos across ${after.pages} page(s). "+if(browserOnly) "Automatic browser rendering will be tried when direct checks cannot validate the page. Sign-in/verification may still require you. Save source to continue." else "Direct background checks validated for the recent channel items. Save source to continue.")
                                MonitoringStore.log(this,"custom source test; source=$sourceId; matched=${after.items.size}; pages=${after.pages}; limited=${after.limited}; background=${background.isSuccess}; ${background.exceptionOrNull()?.let(MonitoringStore::error).orEmpty()}")
                            }
                        }
                        "Rules tested. Review the matched videos in the confirmation window."
                    }
                }
            }
            // Reset to the configured list; a scan may have followed page 2.
            openInSetupBrowser(j.getString("url"))
        }
    }
    private fun checkSaved() {
        val j=if(checkOnly && ruleId.isNotBlank()) MonitoringStore.get(this,ruleId)?.let { CustomSources.source(this,it) }
            ?: error("Monitoring rule was removed")
            else CustomSources.all(this).find { it.getString("id")==sourceId } ?: error("Save a tested source first, then add a monitoring rule that uses it.")
        val rules=MonitoringStore.rules(this).filter { it.customSource==sourceId && LiveRecordingPolicy.samePage(it.url,j.getString("url")) && (ruleId.isBlank() || it.id==ruleId) }
        check(rules.isNotEmpty()) { "No rule matches this setup example. Use Open browser to check on the desired monitoring rule, or add a monitoring link for this channel." }
        scan(j) { result ->
            val initial=rules.any { it.customState.optInt("version")!=2 || !it.customState.optBoolean("coverage") }
            fun apply(coverage: Boolean) {
                val action={ CustomSources.observe(this,j,result.items,result.html,coverage,ruleId)+"\nScan: ${result.reason}." }
                if(!checkOnly) task(action) else io.execute {
                    val outcome=runCatching(action)
                    runOnUiThread {
                        if(!destroyed) outcome.onSuccess {
                            report(it)
                            Toast.makeText(this,"Channel check completed. Results are on Monitoring.",Toast.LENGTH_LONG).show()
                            finish()
                        }.onFailure { report(MonitoringStore.error(it)) }
                    }
                }
            }
            if(initial && j.getString("mode")!="live") reviewMatches(result,"Review current videos — initial baseline", "Use these as current videos") { apply(!result.limited) }
            else apply(false)
        }
    }
    private fun reviewMatches(scan: Scan,title: String,positive: String,action: ()->Unit) {
        val box=LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(8)) }
        box.addView(TextView(this).apply { text="${scan.items.size} unique videos from ${scan.pages} page(s). ${scan.reason}. Review for unrelated recommendations. Initial baseline items will not download. More existing videos encountered later can require review." })
        scan.items.take(20).forEach { item ->
            val row=LinearLayout(this)
            val image=ImageView(this).apply { contentDescription="Preview for "+item.title;scaleType=ImageView.ScaleType.CENTER_CROP;setImageResource(android.R.drawable.ic_menu_gallery) }
            row.addView(image,LinearLayout.LayoutParams(dp(72),dp(72)))
            row.addView(TextView(this).apply { text=item.title.ifBlank { "Untitled video" }+"\n"+item.url;textSize=14f },LinearLayout.LayoutParams(0,-2,1f));box.addView(row)
            if(item.thumbnail.startsWith("https://")) pictures.execute {
                val bitmap=runCatching {
                    val c=java.net.URL(item.thumbnail).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout=7000;c.readTimeout=7000;c.instanceFollowRedirects=false
                    try {
                        check(c.responseCode==200)
                        val out=java.io.ByteArrayOutputStream();val buf=ByteArray(8192)
                        c.inputStream.use { input -> while(out.size()<1000000) { val n=input.read(buf);if(n<0) break;out.write(buf,0,n) } }
                        val bytes=out.toByteArray();val options=android.graphics.BitmapFactory.Options().apply { inSampleSize=4 }
                        android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                    } finally { c.disconnect() }
                }.getOrNull()
                runOnUiThread { if(!destroyed && bitmap!=null) image.setImageBitmap(bitmap) }
            }
        }
        if(scan.items.size>20) box.addView(TextView(this).apply { text="Showing the first 20 previews; ${scan.items.size} unique video identities were captured." })
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(positive) { _,_->action() }.setNegativeButton("Wrong matches / Cancel") { _,_->report("Not confirmed. Choose different examples or narrow the rules in Advanced settings. Existing baseline retained.") }.show()
    }
    private fun task(action: ()->String) { io.execute { val result=runCatching(action);report(result.getOrElse { MonitoringStore.error(it) }) } }
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    override fun onPause() {
        if(browserOpened && ::web.isInitialized) {
            MonitoringSession.flush(this,"custom-source browser leaving foreground; session retained")
            MonitoringSession.observePage(web,"custom-source-leaving")
        }
        super.onPause()
    }

    override fun onDestroy() { destroyed=true;scanToken++;pickGeneration++;documentScript?.remove();handler.removeCallbacksAndMessages(null);web.stopLoading();web.destroy();io.shutdownNow();pictures.shutdownNow();super.onDestroy() }
}
