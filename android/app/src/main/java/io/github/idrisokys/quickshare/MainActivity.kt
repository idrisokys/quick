package io.github.idrisokys.quickshare

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.os.StrictMode
import java.io.File
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    data class Row(val id: String, val name: String)

    private lateinit var lv: ListView
    private lateinit var st: TextView
    private lateinit var bar: ProgressBar
    private var rows = listOf<Row>()
    private var target: String? = null   // satıra dokunulduysa dosyalar sadece o cihaza gider
    private var shortcutKey = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // Bildirimden gelindiyse arayüz açılmadan doğrudan Link klasörü açılır.
        if (intent.getBooleanExtra("folder", false)) { openLinkFolder(); finish(); return }
        Identity.init(this)
        // İki izin penceresi üst üste binmesin: önce bildirim, sonra "Tüm dosyalara erişim".
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 2)
        else requestAllFilesAccess()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply { setColor(0xFF2A2A2A.toInt()); cornerRadius = dp(10).toFloat() }
        }
        info.addView(ImageView(this).apply {
            setImageResource(R.drawable.releases_icon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setOval(0, 0, v.width, v.height) }
            }
            clipToOutline = true
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        val infoText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        infoText.addView(TextView(this).apply {
            text = "sislivadi@gmail.com"; textSize = 13f; setTextColor(Color.WHITE); setTextIsSelectable(true)
        })
        infoText.addView(TextView(this).apply {
            text = "https://github.com/idrisokys/"; textSize = 13f
            setTextColor(0xFF4FC3F7.toInt())
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/idrisokys/"))) }
                catch (e: Exception) { toast(getString(R.string.link_fail)) }
            }
        })
        info.addView(infoText)

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val infoBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_info)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = this@MainActivity.getString(R.string.info)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { info.visibility = if (info.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        header.addView(infoBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(header)
        root.addView(info, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4); bottomMargin = dp(4) })

        lv = ListView(this).apply {
            divider = null
            selector = ColorDrawable(0x334FC3F7)
            adapter = object : BaseAdapter() {
                override fun getCount() = rows.size
                override fun getItem(i: Int) = rows[i]
                override fun getItemId(i: Int) = i.toLong()
                override fun getView(i: Int, v: View?, p: ViewGroup?): View {
                    val r = rows[i]
                    return ((v as? TextView) ?: TextView(this@MainActivity)).apply {
                        setPadding(dp(20), dp(26), dp(20), dp(26))
                        textSize = 26f
                        setTypeface(typeface, Typeface.BOLD)
                        text = r.name
                        setTextColor(Color.WHITE)
                        setShadowLayer(6f, 0f, 2f, 0xCC000000.toInt())
                    }
                }
            }
            setOnItemClickListener { _, _, i, _ ->
                target = rows[i].id
                pickFiles()
            }
        }
        root.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(72) })

        st = TextView(this).apply {
            textSize = 16f; setTextColor(0xFFBBBBBB.toInt()); setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(st)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(bar)

        val btn = Button(this).apply {
            setText(R.string.close)
            setOnClickListener { finishAndRemoveTask() }
        }
        val folderBtn = Button(this).apply {
            setText(R.string.open_folder)
            setOnClickListener { openLinkFolder() }
        }
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(btn, LinearLayout.LayoutParams(0, dp(64), 1f).apply { rightMargin = dp(6) })
        btnRow.addView(folderBtn, LinearLayout.LayoutParams(0, dp(64), 1f).apply { leftMargin = dp(6) })
        root.addView(btnRow, LinearLayout.LayoutParams(-1, -2))

        val frame = FrameLayout(this).apply { setBackgroundColor(0xFF111111.toInt()) }
        frame.addView(ImageView(this).apply {
            setImageResource(R.drawable.app_bg)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, FrameLayout.LayoutParams(-1, -1))
        // Yazılar okunsun diye resmin üstüne hafif koyu katman
        frame.addView(View(this).apply { setBackgroundColor(0x55000000) }, FrameLayout.LayoutParams(-1, -1))
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        btn.requestFocus()

        ReceiverService.start(this)
        Shortcuts.update(this)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == 2) { requestAllFilesAccess(); ReceiverService.start(this) }
    }

    override fun onActivityResult(rc: Int, res: Int, d: Intent?) {
        super.onActivityResult(rc, res, d)
        if (rc != 1 || res != RESULT_OK || d == null) return
        val uris = mutableListOf<Uri>()
        d.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri }
        d.data?.let { if (uris.isEmpty()) uris += it }
        if (uris.isEmpty()) return
        if (Peers.list().none { target == null || it.id == target }) { toast(getString(R.string.no_device)); return }
        Sender.submit(this, uris, target)
    }

    private fun refresh() {
        val nr = Peers.list().map { Row(it.id, it.name) }
        if (nr != rows) {                       // liste değişmediyse yeniden çizme (ilerleme sırasında)
            rows = nr
            (lv.adapter as BaseAdapter).notifyDataSetChanged()
            val key = rows.joinToString { it.id }
            if (key != shortcutKey) { shortcutKey = key; Shortcuts.update(this) }
        }
        st.setTextColor(if (Bus.saved) 0xFF4FC3F7.toInt() else 0xFFBBBBBB.toInt())
        st.text = when {
            Bus.status.isNotEmpty() -> Bus.status
            rows.isEmpty() -> getString(R.string.searching)
            else -> getString(R.string.hint)
        }
        bar.visibility = if (Bus.pct >= 0) View.VISIBLE else View.INVISIBLE
        bar.progress = Bus.pct.coerceAtLeast(0)
    }

    /** Link klasörünü com.alphainventor.filemanager ile açar. */
    private fun openLinkFolder() {
        val pkg = "com.alphainventor.filemanager"
        val dir = SaveDir.dir()
        if (!dir.exists()) dir.mkdirs()

        // 1) file:// Uri (Android'in FileUriExposedException kontrolü bu çağrı için kapatılır)
        try {
            try { StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build()) } catch (e: Exception) { }
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(dir), "resource/folder")
                .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) { }

        // 2) Depolama sağlayıcısı (DocumentsContract) Uri'si
        try {
            val rel = dir.absolutePath.removePrefix(android.os.Environment.getExternalStorageDirectory().absolutePath).trim('/')
            val uri = android.provider.DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:$rel")
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.document/directory")
                .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            return
        } catch (e: Exception) { }

        // 3) En azından dosya yöneticisini aç
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) { startActivity(launch); toast(getString(R.string.folder_manual, dir.absolutePath)) }
        else toast(getString(R.string.fm_missing))
    }

    private fun pickFiles() {
        // ACTION_GET_CONTENT lets Android route file selection to an installed
        // file manager/content provider instead of forcing the SAF DocumentsUI.
        startActivityForResult(
            Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 1
        )
    }

    /** Ana dizindeki Link klasörüne yazmak için "Tüm dosyalara erişim" iznini (Android 10'da depolama iznini) ister. */
    private fun requestAllFilesAccess() {
        if (SaveDir.direct(this)) return
        if (Build.VERSION.SDK_INT < 30) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 3)
            return
        }
        val pkg = Uri.parse("package:$packageName")
        try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)) }
        catch (e: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (e2: Exception) { }
        }
    }

    override fun onStart() { super.onStart(); Bus.listener = { refresh() }; refresh() }
    override fun onStop() { Bus.listener = null; super.onStop() }
    // Uygulama kapansa da servis arka planda çalışmaya devam eder.
}
