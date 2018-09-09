package jp.shiita.basenote.addeditnote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import dagger.android.support.DaggerFragment
import jp.shiita.basenote.R
import jp.shiita.basenote.data.URLSpanData
import jp.shiita.basenote.util.MyURLSpan
import jp.shiita.basenote.util.snackbarLong
import kotlinx.android.synthetic.main.addeditnote_act.*
import javax.inject.Inject

class AddEditNoteFragment @Inject constructor() : DaggerFragment(), AddEditNoteContract.View, MyURLSpan.OnURLClickListener {

    override var presenter: AddEditNoteContract.Presenter? = null

    private lateinit var inputView: View
    private lateinit var title: TextView
    private lateinit var content: TextView

    private lateinit var webFrameLayout: FrameLayout
    private lateinit var webView: WebView
    private var webViewX = 0
    // 開かれているURLのSpanの位置を保存する
    private var urlSpanStart = 0
    private var urlSpanEnd = 0

    private lateinit var webViewBar: View
    private lateinit var goForward: ImageButton
    private lateinit var goBack: ImageButton

    private var editMode = true
    private var webMode = false

    private var noteTag = 0

    override var isActive: Boolean = false
        get() = isAdded

    override fun onResume() {
        super.onResume()
        presenter?.start()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        activity?.let { act ->
            act.fab_edit_note_done_top.setOnClickListener { switchEditMode(save = true) }
            act.fab_edit_note_done_bottom.setOnClickListener { switchEditMode(save = true) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // スワイプ検知に利用
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(event1: MotionEvent, event2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val distance = event1.x - event2.x
                val acceptSideSize = 30
                val swipeMinDistance = 50
                val swipeMinVelocity = 300

                // スワイプの移動距離・速度・開始地点の条件がそろえば、WebViewの操作をする
                if (distance > swipeMinDistance && Math.abs(velocityX) > swipeMinVelocity && event1.x > webViewX - acceptSideSize) {
                    webView.goForward()
                } else if (-distance > swipeMinDistance && Math.abs(velocityX) > swipeMinVelocity && event1.x < acceptSideSize) {
                    webView.goBack()
                }

                return false
            }
        })
        val root = inflater.inflate(R.layout.addeditnote_frag, container, false)
        // Viewの初期設定
        with(root) {
            inputView = findViewById(R.id.add_edit_note_input)
            title = findViewById(R.id.add_edit_note_title) as TextView
            content = findViewById(R.id.add_edit_note_content) as TextView
            webFrameLayout = findViewById(R.id.web_frame_layout) as FrameLayout
            webView = (findViewById(R.id.web_view) as WebView).apply {
                scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
                setWebViewClient(object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        if (canGoBack()) goBack.visibility = View.VISIBLE
                        else             goBack.visibility = View.INVISIBLE
                        if (canGoForward()) goForward.visibility = View.VISIBLE
                        else                goForward.visibility = View.INVISIBLE
                        super.onPageFinished(view, url)
                    }
                })
                settings.apply {
                    javaScriptEnabled = true
                }
                // TouchListenerのeventをそのままGestureDetectorに渡してスワイプの検知
                setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }
                // WebViewがレイアウトに設置されてから、サイズを測る
                viewTreeObserver.addOnGlobalLayoutListener { webViewX = webView.width }
            }
            webViewBar = findViewById<View>(R.id.add_edit_note_web_view_bar).apply {
                setOnTouchListener(object : View.OnTouchListener {
                    private var beforeY: Float = 0f
                    private var first: Boolean = true

                    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                        if (event == null) return false     // スマートキャスト
                        if (event.action == MotionEvent.ACTION_DOWN) first = true
                        val dy = beforeY - event.rawY
                        beforeY = event.rawY

                        if (first)
                            first = false
                        else
                            webFrameLayout.layoutParams = webFrameLayout.layoutParams.apply {
                                height += dy.toInt()
                                if (height < 0) height = 0
                            }
                        return true
                    }
                })
            }
            goBack = (findViewById(R.id.back_web_view_button) as ImageButton).apply {
                setOnClickListener { webView.goBack() }
            }
            goForward = (findViewById(R.id.forward_web_view_button) as ImageButton).apply {
                setOnClickListener { webView.goForward() }
            }
            findViewById<View>(R.id.menu_web_view_button).setOnClickListener {
                PopupMenu(context, activity?.findViewById(R.id.menu_web_view_button)).apply {
                    inflate(R.menu.web_view_menu)
                    setOnMenuItemClickListener {
                        clickWebViewMenu(it.itemId)
                        true
                    }
                    show()
                }
            }
            findViewById<View>(R.id.close_web_view_button).setOnClickListener { stopWebMode() }
        }

        // ソフトキーボードの自動出現と、選択を可能にすることを同時に行いたいため、キーボードの出現はClickListenerに任せる
        content.run {
            setTextIsSelectable(true)       // trueだとソフトキーボードは自動出現しない
            setOnClickListener { if (editMode) showSoftInput() }
        }
        content.customSelectionActionModeCallback = object : ActionMode.Callback {
            private val searchId = mapOf(R.id.menu_search_google to 0, R.id.menu_search_wikipedia to 1, R.id.menu_search_weblio to 2)

            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                var start = 0
                var end = content.text.length
                if (content.isFocused) {
                    start = Math.max(content.selectionStart, start) // 最小値は0
                    end = Math.min(content.selectionEnd, end)       // 最大値はcontent.text.length
                }
                val word = content.text.subSequence(start, end).toString()

                // floating action modeに対応しているかどうかでメニュー表示を分ける
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    when (item?.itemId) {
                        in searchId.keys -> {
                            searchWebItemSelected(item!!.itemId, word, start, end)
                            mode?.finish()
                            return true
                        }
                    }
                }
                else {
                    if (item?.itemId == R.id.menu_search_web) {
                        PopupMenu(context, activity?.findViewById(R.id.menu_search_web)).apply {
                            inflate(R.menu.search_web_menu)
                            setOnMenuItemClickListener {
                                searchWebItemSelected(it.itemId, word, start, end)
                                true
                            }
                            show()
                        }
                        mode?.finish()
                        return true
                    }
                }

                return false
            }

            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                // floating action modeに対応しているかどうかでメニュー表示を分ける
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    mode?.menuInflater?.inflate(R.menu.search_web_menu, menu)
                }
                else {
                    // カット、全て選択の削除
                    menu?.removeItem(android.R.id.cut)
                    menu?.removeItem(android.R.id.selectAll)
                    mode?.menuInflater?.inflate(R.menu.action_mode_menu, menu)
                }
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = true

            override fun onDestroyActionMode(mode: ActionMode?) {}

            private fun searchWebItemSelected(itemId: Int, word: String, start: Int, end: Int) {
                when (itemId) {
                    in searchId.keys -> {
                        startWebMode()
                        val urlStr = presenter?.generateSearchURL(word, searchId[itemId]!!)
                        if (urlStr != null) {
                            // MyURLSpanの設定
                            val span = MyURLSpan(urlStr).apply {
                                setOnURLClickListener(this@AddEditNoteFragment)
                            }
                            content.text = presenter?.addURLSpan(content.text as Spannable, span, start, end)
                            urlSpanStart = start
                            urlSpanEnd = end
                            webView.loadUrl(urlStr)
                        }
                    }
                }
            }
        }

        setHasOptionsMenu(true)
        return root
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_select_tag -> {
                SelectTagDialogFragment().apply {
                    setTargetFragment(this@AddEditNoteFragment, SELECT_TAG_REQUEST_CODE)
                    arguments = Bundle().apply {
                        putInt(SelectTagDialogFragment.ARGUMENT_TAG, noteTag)
                    }
                }.show(fragmentManager, SelectTagDialogFragment.TAG)
            }
            R.id.menu_delete -> presenter?.deleteNote()
            R.id.home -> finishActivity()
            else -> return false
        }
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu?, inflater: MenuInflater) {
        inflater.inflate(R.menu.addeditnote_fragment_menu, menu)
        // タグの色変更
        if (noteTag != 0)
            menu?.getItem(0)?.icon?.setColorFilter(resources.obtainTypedArray(R.array.tag_color).getColor(noteTag, 0), PorterDuff.Mode.SRC_IN)
        else
            menu?.getItem(0)?.icon?.colorFilter = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) return
        when (requestCode) {
            SELECT_TAG_REQUEST_CODE -> {
                noteTag = data?.getIntExtra(SelectTagDialogFragment.ARGUMENT_TAG, 0)!!
                presenter?.updateTag(noteTag)
                activity?.invalidateOptionsMenu()    // メニューを読み込み直して、タグの色を反映
            }
        }
    }

    override fun setTitle(title: String) {
        this.title.text = title
    }

    override fun setContent(content: String, urlSpanList: List<URLSpanData>) {
        // URLSpanの作成
        val sb = SpannableStringBuilder()
        sb.append(content)
        urlSpanList.forEach { urlSpan ->
            val span = MyURLSpan(urlSpan.url).apply {
                setOnURLClickListener(this@AddEditNoteFragment)
            }
            sb.setSpan(span, urlSpan.start, urlSpan.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        this.content.text = sb
    }

    override fun setNoteTag(tag: Int) {
        noteTag = tag
    }

    override fun showSaveNote() = content.snackbarLong(getString(R.string.save_note_message))

    override fun switchEditMode(save: Boolean) {
        editMode = !editMode

        // フォーカスが与えられるかどうかで、編集の可否を制御
        title.run {
            isFocusable = editMode
            isFocusableInTouchMode = editMode
        }
        content.run {
            isFocusable = editMode
            isFocusableInTouchMode = editMode
            // setTextIsSelectableがtrueだとmovementMethodが機能しないので注意する
            setTextIsSelectable(editMode)
            if (!editMode) movementMethod = LinkMovementMethod.getInstance()
        }

        val act = (activity as AddEditNoteActivity)
        when {
            editMode -> act.setFabIconResource(R.drawable.ic_done)
            !editMode -> {
                act.setFabIconResource(R.drawable.ic_edit)
                // 編集モードから抜けた時に保存処理をする
                if (save) saveNote()
            }
        }
        act.setToolbarTitle(editMode)

        hideSoftInput()
    }

    override fun finishActivity() {
        activity?.finish()
    }

    override fun onURLClick(url: String) {
        if (editMode) return    // 編集中は無効化
        startWebMode()
        webView.loadUrl(url)
        presenter?.findURLSpanData(content.text as Spannable, url)?.let {
            urlSpanStart = it.start
            urlSpanEnd = it.end
        }
        webFrameLayout.visibility = View.VISIBLE
        webViewBar.visibility = View.VISIBLE
    }

    private fun saveNote() {
        val spanList = presenter?.getURLSpanDataList(content.text as Spannable) ?: emptyList()
        presenter?.saveNote(title.text.toString(), content.text.toString(), spanList, noteTag)
    }

    // Webページを表示する処理。fabとアクションバーを非表示に
    private fun startWebMode() {
        hideSoftInput()
        inputView.visibility = View.VISIBLE
        webFrameLayout.visibility = View.VISIBLE
        webViewBar.visibility = View.VISIBLE
        (activity as AddEditNoteActivity).apply {
            switchTopFab()
            supportActionBar?.hide()
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE
        }
        webMode = true
    }

    // WebViewを閉じる処理。fabとアクションバー再表示する
    private fun stopWebMode() {
        (activity as AddEditNoteActivity).apply {
            switchBottomFab()
            supportActionBar?.show()
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
        inputView.visibility = View.VISIBLE
        webFrameLayout.visibility = View.GONE
        webViewBar.visibility = View.GONE
        webMode = false
    }

    // 全画面表示切り替え
    private fun switchFullScreen() {
        if (inputView.visibility == View.VISIBLE) {
            inputView.visibility = View.GONE
            (activity as AddEditNoteActivity).hideTopFab()
        }
        else {
            inputView.visibility = View.VISIBLE
            (activity as AddEditNoteActivity).showTopFab()
        }
    }

    // WebViewのメニューがタップされた時の処理
    private fun clickWebViewMenu(id: Int) {
        when (id) {
            R.id.web_view_menu_update_link -> {
                val urlStr = webView.url
                val span = MyURLSpan(urlStr).apply {
                    setOnURLClickListener(this@AddEditNoteFragment)
                }
                val spannable = presenter!!.removeURLSpan(content.text as Spannable, span, urlSpanStart, urlSpanEnd)
                content.text = presenter!!.addURLSpan(spannable, span, urlSpanStart, urlSpanEnd)
            }
            R.id.web_view_menu_full_screen -> switchFullScreen()
        }
    }

    // ソフトキーボードを閉じる
    private fun hideSoftInput() {
        val act = activity ?: return
        val inputMethodManager = act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(content.windowToken, 0)
    }

    // ソフトキーボードを開く
    private fun showSoftInput() {
        val act = activity ?: return
        val inputMethodManager = act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.showSoftInput(content, 0)
    }

    companion object {
        val ARGUMENT_EDIT_NOTE_ID = "EDIT_NOTE_ID"
        val ARGUMENT_EDIT_NOTE_TAG = "EDIT_NOTE_TAG"
        val SELECT_TAG_REQUEST_CODE = 1
        fun newInstance() = AddEditNoteFragment()
    }
}