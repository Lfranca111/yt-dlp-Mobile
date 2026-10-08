package com.linfranca.ytdlpmobile

import android.content.Intent
import android.app.Dialog
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors

/** The Collections screen alone opens this editor. Main and its recorder are untouched. */
class CollectionConvertActivity : AppCompatActivity() {
    private val pages = mutableListOf<ComicFile>()
    private var videos = emptyList<ComicFile>()
    private var tree: Uri? = null
    private var directory: Uri? = null
    private var folderName = "Comic"
    private lateinit var status: TextView
    private lateinit var warning: TextView
    private lateinit var convert: MaterialButton
    private lateinit var cancel: MaterialButton
    private lateinit var includeVideos: CheckBox
    private lateinit var embedVideos: CheckBox
    private lateinit var adapter: PageAdapter
    private lateinit var progressBar: ProgressBar
    private val thumbnailWorker = Executors.newSingleThreadExecutor()
    private val previewWorker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("comic-page-order", MODE_PRIVATE) }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { chosen ->
        if (chosen == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(chosen,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        tree = chosen
        browseFolder(ComicFiles.root(chosen))
    }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected ->
        if (selected.isEmpty()) return@registerForActivityResult
        val files = selected.mapNotNull { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val info = describe(uri)
                info.takeIf { it.image }
            }.getOrNull()
        }
        if (files.isEmpty()) { status.text = "No supported still images were selected."; return@registerForActivityResult }
        pages.clear(); pages += ComicFiles.sorted(files)
        videos = emptyList()
        directory = null; tree = null
        folderName = "Selected images"
        showPages()
        status.text = "Choose a destination folder for the PDF. Only videos already in that folder can be linked."
        outputPicker.launch(null)
    }
    private val outputPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { chosen ->
        if (chosen == null) { status.text = "Choose a destination folder to convert the selected files."; return@registerForActivityResult }
        contentResolver.takePersistableUriPermission(chosen,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        tree = chosen; directory = ComicFiles.root(chosen)
        val destinationFiles = try { ComicFiles.children(contentResolver, chosen, directory!!) }
        catch (error: Exception) { status.text = "Cannot list destination folder: ${error.message}"; directory = null; return@registerForActivityResult }
        videos = ComicFiles.sorted(destinationFiles.filter { it.video })
        folderName = "Selected images"
        val saved = ComicFiles.restoreOrder(directory, pages, prefs)
        pages.clear(); pages += saved
        showPages()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Convert collection"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val heading = TextView(this).apply { text = "Comic pages → PDF"; textSize = 23f }
        root.addView(heading)
        root.addView(TextView(this).apply {
            text = "Tap an image to inspect and zoom. Tap its filename to move it, or long press and drag a row to reorder."
            setPadding(0, dp(6), 0, dp(12))
        })
        val choose = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        choose.addView(MaterialButton(this).apply {
            text = "Choose folder"
            setOnClickListener { folderPicker.launch(null) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        choose.addView(MaterialButton(this).apply {
            text = "Choose images"
            setOnClickListener { filePicker.launch(arrayOf("image/*")) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(choose)
        warning = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        root.addView(warning)
        includeVideos = CheckBox(this).apply {
            text = "Add companion video links to PDF"
            isChecked = true
        }
        root.addView(includeVideos)
        embedVideos = CheckBox(this).apply {
            text = "Embed companion video files in PDF"
            isChecked = false
            isEnabled = includeVideos.isChecked
        }
        root.addView(embedVideos)
        root.addView(TextView(this).apply {
            text = "Embedding copies each video into the PDF. This can greatly increase its file size and the time needed to finish."
            setPadding(dp(16), 0, 0, dp(8))
        })
        includeVideos.setOnCheckedChangeListener { _, checked ->
            embedVideos.isEnabled = checked && videos.isNotEmpty()
            if (!checked) embedVideos.isChecked = false
            updateVideoSummary()
        }
        embedVideos.setOnCheckedChangeListener { _, _ -> updateVideoSummary() }
        root.addView(MaterialButton(this).apply {
            text = "View video files to link"
            setOnClickListener {
                AlertDialog.Builder(this@CollectionConvertActivity).setTitle("Companion videos")
                    .setMessage(videos.joinToString("\n") { it.name }.ifBlank { "No videos in the selected folder." })
                    .setPositiveButton("OK", null).show()
            }
        })
        val reverse = MaterialButton(this).apply {
            text = "Reverse page order"
            setOnClickListener { pages.reverse(); orderChanged() }
        }
        root.addView(reverse)
        val list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@CollectionConvertActivity) }
        adapter = PageAdapter()
        list.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val old = from.bindingAdapterPosition; val next = to.bindingAdapterPosition
                if (old !in pages.indices || next !in pages.indices) return false
                Collections.swap(pages, old, next)
                adapter.notifyItemMoved(old, next)
                saveOrder()
                return true
            }
            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit
        }).attachToRecyclerView(list)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        status = TextView(this).apply { text = "Choose a folder or images to begin."; setPadding(0, dp(8), 0, dp(8)) }
        root.addView(status)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        root.addView(progressBar)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        convert = MaterialButton(this).apply {
            text = "Make PDF"
            setOnClickListener { startConversion() }
        }
        cancel = MaterialButton(this).apply {
            text = "Cancel conversion"
            setOnClickListener { startService(Intent(this@CollectionConvertActivity,
                CollectionConvertService::class.java).setAction(CollectionConvertService.ACTION_CANCEL)) }
        }
        actions.addView(convert, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(dp(16) + bars.left, dp(12) + bars.top,
                dp(16) + bars.right, dp(12) + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        renderProgress()
    }

    private fun describe(uri: Uri): ComicFile {
        contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return ComicFile(uri, cursor.getString(0).orEmpty(), cursor.getString(1).orEmpty())
        }
        return ComicFile(uri, uri.lastPathSegment.orEmpty(), contentResolver.getType(uri).orEmpty())
    }

    private fun browseFolder(folder: Uri) {
        val selectedTree = tree ?: return
        val children = try { ComicFiles.children(contentResolver, selectedTree, folder) }
        catch (error: Exception) { status.text = "Cannot read folder: ${error.message}"; return }
        val folders = children.filter { it.mime == DocumentsContract.Document.MIME_TYPE_DIR }
        val images = children.filter { it.image }
        val choices = mutableListOf("Use this folder (${images.size} images)")
        choices += folders.map { "Open ${it.name}" }
        AlertDialog.Builder(this).setTitle("Select comic folder")
            .setItems(choices.toTypedArray()) { _, index ->
                if (index == 0) {
                    if (images.isEmpty()) { status.text = "This folder has no still images. Open a comic subfolder."; browseFolder(folder) }
                    else {
                        directory = folder
                        folderName = runCatching { describe(folder).name }.getOrDefault("Comic")
                        pages.clear(); pages += ComicFiles.restoreOrder(folder, images, prefs)
                        videos = ComicFiles.sorted(children.filter { it.video })
                        showPages()
                        status.text = "Ready: ${pages.size} pages in $folderName."
                    }
                } else browseFolder(folders[index - 1].uri)
            }.setNegativeButton("Close", null).show()
    }

    private fun showPages() {
        embedVideos.isEnabled = includeVideos.isChecked && videos.isNotEmpty()
        if (videos.isEmpty()) embedVideos.isChecked = false
        updateVideoSummary()
        adapter.notifyDataSetChanged()
        renderButtons()
    }
    private fun updateVideoSummary() {
        val mode = when {
            !includeVideos.isChecked -> "will not be included in the PDF"
            embedVideos.isChecked -> "will be embedded in the PDF"
            else -> "will be linked beside the PDF"
        }
        warning.text = ComicFiles.diagnostic(pages) + "\n${videos.size} video file(s) $mode."
    }
    private fun orderChanged() { adapter.notifyDataSetChanged(); saveOrder() }
    private fun saveOrder() { ComicFiles.saveOrder(directory, pages, prefs) }
    private fun startConversion() {
        val target = directory ?: run { status.text = "Choose a destination folder first."; return }
        if (pages.isEmpty() || CollectionConvertState.running) return
        saveOrder()
        try {
            // A comic may contain thousands of pages. Pass a private request file rather than
            // putting every Uri into an Android Intent with a Binder size limit.
            val payload = JSONObject()
                .put("folder", target.toString()).put("title", folderName)
                .put("images", JSONArray().apply { pages.forEach { put(it.uri.toString()) } })
                .put("videos", JSONArray().apply { if (includeVideos.isChecked) videos.forEach { put(it.name) } })
                .put("embedVideos", includeVideos.isChecked && embedVideos.isChecked)
                .put("videoUris", JSONArray().apply { if (includeVideos.isChecked && embedVideos.isChecked)
                    videos.forEach { put(it.uri.toString()) } })
            val request = File(filesDir, "comic-convert-${UUID.randomUUID()}.json")
            request.writeText(payload.toString())
            ContextCompat.startForegroundService(this, Intent(this, CollectionConvertService::class.java)
                .setAction(CollectionConvertService.ACTION_START)
                .putExtra(CollectionConvertService.EXTRA_REQUEST, request.name))
            status.text = "Starting PDF conversion…"
            renderButtons()
        } catch (error: Exception) { status.text = "Cannot start conversion: ${error.message}" }
    }
    private fun renderButtons() {
        if (::convert.isInitialized) convert.isEnabled = pages.isNotEmpty() && directory != null && !CollectionConvertState.running
        if (::cancel.isInitialized) cancel.isEnabled = CollectionConvertState.running
    }
    private fun renderProgress() {
        progressBar.max = CollectionConvertState.total.coerceAtLeast(1)
        progressBar.progress = CollectionConvertState.done
        status.text = CollectionConvertState.message.takeIf { CollectionConvertState.running || CollectionConvertState.done > 0 } ?: status.text
        renderButtons()
    }
    private val progressReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            renderProgress()
        }
    }
    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, progressReceiver,
            android.content.IntentFilter(CollectionConvertService.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        status.text = CollectionConvertState.message.takeIf { CollectionConvertState.running || CollectionConvertState.done > 0 } ?: status.text
        renderProgress()
    }
    override fun onStop() { unregisterReceiver(progressReceiver); super.onStop() }
    override fun onDestroy() { thumbnailWorker.shutdownNow(); previewWorker.shutdownNow(); super.onDestroy() }

    private fun showPagePreview(initial: Int) {
        if (initial !in pages.indices) return
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        ViewCompat.setOnApplyWindowInsetsListener(container) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            container.setPadding(dp(12) + bars.left, dp(12) + bars.top,
                dp(12) + bars.right, dp(12) + bars.bottom)
            insets
        }
        val caption = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 0, 0, dp(8))
            maxLines = 2
        }
        container.addView(caption)
        val image = ZoomablePageView(this).apply { contentDescription = "Pinch to zoom and drag to pan" }
        container.addView(image, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val previous = MaterialButton(this).apply { text = "Previous" }
        val next = MaterialButton(this).apply { text = "Next" }
        val close = MaterialButton(this).apply { text = "Close"; setOnClickListener { dialog.dismiss() } }
        listOf(previous, next, close).forEach {
            controls.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        container.addView(controls)
        dialog.setContentView(container)
        var position = initial
        var generation = 0
        var loaded: Bitmap? = null
        fun display() {
            generation++
            val requested = generation
            val entry = pages[position]
            caption.text = "Loading ${position + 1}/${pages.size}: ${entry.name}"
            previous.isEnabled = position > 0
            next.isEnabled = position < pages.lastIndex
            image.setImageDrawable(null)
            loaded?.recycle(); loaded = null
            previewWorker.execute {
                val bitmap = runCatching {
                    val source = ImageDecoder.createSource(contentResolver, entry.uri)
                    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        val longest = maxOf(info.size.width, info.size.height)
                        var sample = 1
                        while (longest / sample > 4096) sample *= 2
                        decoder.setTargetSampleSize(sample)
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                }.getOrNull()
                runOnUiThread {
                    if (!dialog.isShowing || isDestroyed || requested != generation) {
                        bitmap?.recycle()
                    } else if (bitmap == null) {
                        caption.text = "Could not open ${position + 1}/${pages.size}: ${entry.name}"
                    } else {
                        loaded = bitmap
                        image.show(bitmap)
                        caption.text = "${position + 1}/${pages.size}: ${entry.name} • pinch to zoom"
                    }
                }
            }
        }
        previous.setOnClickListener { if (position > 0) { position--; display() } }
        next.setOnClickListener { if (position < pages.lastIndex) { position++; display() } }
        dialog.setOnDismissListener {
            generation++
            image.setImageDrawable(null)
            loaded?.recycle(); loaded = null
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        ViewCompat.requestApplyInsets(container)
        display()
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.Holder>() {
        inner class Holder(val row: LinearLayout, val thumb: ImageView, val label: TextView) : RecyclerView.ViewHolder(row)
        override fun getItemCount() = pages.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
            val row = LinearLayout(parent.context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val thumb = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(android.graphics.Color.BLACK)
                contentDescription = "Open full page preview"
            }
            row.addView(thumb, LinearLayout.LayoutParams(dp(140), dp(175)))
            val label = TextView(parent.context).apply { setPadding(dp(10), 0, dp(6), 0); maxLines = 3 }
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(parent.context).apply { text = "☰"; textSize = 24f; contentDescription = "Drag to reorder" })
            return Holder(row, thumb, label)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = pages[position]
            holder.label.text = "${position + 1}. ${entry.name}"
            holder.thumb.setImageDrawable(null)
            holder.thumb.tag = entry.uri
            thumbnailWorker.execute {
                val bitmap = runCatching { contentResolver.loadThumbnail(entry.uri, Size(320, 420), null) }.getOrNull()
                runOnUiThread { if (holder.thumb.tag == entry.uri) holder.thumb.setImageBitmap(bitmap) }
            }
            holder.thumb.setOnClickListener {
                val current = holder.bindingAdapterPosition
                if (current in pages.indices) showPagePreview(current)
            }
            holder.row.setOnClickListener {
                val current = holder.bindingAdapterPosition
                if (current !in pages.indices) return@setOnClickListener
                val input = android.widget.EditText(this@CollectionConvertActivity).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    setText("${current + 1}")
                    selectAll()
                }
                AlertDialog.Builder(this@CollectionConvertActivity).setTitle("Move page to position")
                    .setView(input).setPositiveButton("Move") { _, _ ->
                        val target = (input.text.toString().toIntOrNull() ?: return@setPositiveButton) - 1
                        if (target !in pages.indices) { Toast.makeText(this@CollectionConvertActivity,
                            "Enter a position from 1 to ${pages.size}", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                        pages.add(target, pages.removeAt(current)); orderChanged()
                    }.setNegativeButton("Cancel", null).show()
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}

/** Fits a full page initially; pinch, drag and double tap provide readable detail. */
private class ZoomablePageView(context: android.content.Context) :
    androidx.appcompat.widget.AppCompatImageView(context) {
    private val transform = Matrix()
    private val bounds = RectF()
    private var zoom = 1f
    private var lastX = 0f
    private var lastY = 0f
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = (zoom * detector.scaleFactor).coerceIn(1f, 6f) / zoom
            zoom *= factor
            transform.postScale(factor, factor, detector.focusX, detector.focusY)
            clamp(); imageMatrix = transform
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true
        override fun onDoubleTap(event: MotionEvent): Boolean {
            if (zoom > 1.1f) reset() else {
                transform.postScale(2.5f, 2.5f, event.x, event.y)
                zoom = 2.5f
                clamp(); imageMatrix = transform
            }
            return true
        }
    })

    init { scaleType = ScaleType.MATRIX; setBackgroundColor(Color.BLACK) }
    fun show(bitmap: Bitmap) {
        setImageBitmap(bitmap)
        post { reset() }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { reset() }
    }
    private fun reset() {
        val item = drawable ?: return
        if (width <= 0 || height <= 0) return
        val fit = minOf(width.toFloat() / item.intrinsicWidth, height.toFloat() / item.intrinsicHeight)
        transform.reset()
        transform.postScale(fit, fit)
        transform.postTranslate((width - item.intrinsicWidth * fit) / 2f,
            (height - item.intrinsicHeight * fit) / 2f)
        zoom = 1f
        imageMatrix = transform
    }
    private fun clamp() {
        val item = drawable ?: return
        bounds.set(0f, 0f, item.intrinsicWidth.toFloat(), item.intrinsicHeight.toFloat())
        transform.mapRect(bounds)
        val dx = if (bounds.width() <= width) width / 2f - bounds.centerX()
            else when { bounds.left > 0f -> -bounds.left; bounds.right < width -> width - bounds.right; else -> 0f }
        val dy = if (bounds.height() <= height) height / 2f - bounds.centerY()
            else when { bounds.top > 0f -> -bounds.top; bounds.bottom < height -> height - bounds.bottom; else -> 0f }
        transform.postTranslate(dx, dy)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && !scaler.isInProgress && zoom > 1f) {
                transform.postTranslate(event.x - lastX, event.y - lastY)
                clamp(); imageMatrix = transform
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining); lastY = event.getY(remaining)
            }
        }
        return true
    }
}
