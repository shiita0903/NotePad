package jp.shiita.basenote.notes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.design.widget.FloatingActionButton
import android.support.v4.app.Fragment
import android.support.v4.widget.SwipeRefreshLayout
import android.support.v7.widget.DividerItemDecoration
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.*
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import jp.shiita.basenote.R
import jp.shiita.basenote.addeditnote.AddEditNoteActivity
import jp.shiita.basenote.addeditnote.AddEditNoteFragment
import jp.shiita.basenote.addeditnote.SelectTagDialogFragment
import jp.shiita.basenote.data.Note
import jp.shiita.basenote.util.snackbarLong
import java.util.*

class NotesFragment : Fragment(), NotesContract.View {

    override var presenter: NotesContract.Presenter? = null
    private lateinit var notesAdapter: NotesAdapter
    private lateinit var notesView: View
    private lateinit var noNotesView: View
    override var isActive: Boolean = false
        get() = isAdded

    override fun onResume() {
        super.onResume()
        presenter?.start()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val root = inflater.inflate(R.layout.notes_frag, container, false)

        notesAdapter = NotesAdapter(context, mutableListOf()).apply {
            onClickNoteItem = { position -> presenter?.editNote(getItem(position)) }    // RecyclerViewの要素自体をタップ時
            onClickNoteItemMenu = { position, menuId ->                                 // RecyclerViewの要素のメニューをタップ時
                val note = getItem(position)
                when(menuId) {
                    R.id.note_item_menu_delete -> {
                        presenter?.deleteNote(note)
                        removeItem(position)    // アダプタのノート一覧からも削除する必要がある
                        if (notesAdapter.itemCount == 0)
                            showNoNotes()
                    }
                    R.id.note_item_menu_select_tag -> {
                        SelectTagDialogFragment().apply {
                            setTargetFragment(this@NotesFragment, SELECT_TAG_REQUEST_CODE)
                            arguments = Bundle().apply {
                                putInt(SelectTagDialogFragment.ARGUMENT_TAG, note.tag)
                                putInt(SelectTagDialogFragment.ARGUMENT_POSITION, position)
                            }
                        }.show(fragmentManager, SelectTagDialogFragment.TAG)
                    }
                }
            }
        }

        // Set up notes view
        with(root) {
            val recyclerView = (findViewById(R.id.notes_recycler_view) as RecyclerView).apply {
                layoutManager = LinearLayoutManager(context)
                adapter = notesAdapter
                val dividerItemDecoration = DividerItemDecoration(context, DividerItemDecoration.VERTICAL)
                addItemDecoration(dividerItemDecoration)
            }

            // Set up progress indicator
            (root.findViewById(R.id.refresh_layout) as ScrollChildSwipeRefreshLayout).run {
                setColorSchemeColors(
                        android.support.v4.content.ContextCompat.getColor(context, R.color.colorPrimary),
                        android.support.v4.content.ContextCompat.getColor(context, R.color.colorAccent),
                        android.support.v4.content.ContextCompat.getColor(context, R.color.colorPrimaryDark)
                )
                // Set the scrolling view in the custom SwipeRefreshLayout.
                scrollUpChild = recyclerView
                setOnRefreshListener { presenter?.loadNotes(false) }
            }

            notesView = findViewById(R.id.notes_view)

            // Set up no notes view
            noNotesView = findViewById(R.id.no_notes_view)
            (findViewById(R.id.no_notes_add) as TextView).run {
                setOnClickListener { showAddNote() }
            }
        }

        // Set up floating action button
        (activity.findViewById(R.id.fab_add_note) as FloatingActionButton).apply {
            setImageResource(R.drawable.ic_add)
            setOnClickListener { presenter?.addNewNote() }
        }
        setHasOptionsMenu(true)

        return root
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) return
        when (requestCode) {
            SELECT_TAG_REQUEST_CODE -> {
                val position = data!!.getIntExtra(SelectTagDialogFragment.ARGUMENT_POSITION, 0)
                val tag = data.getIntExtra(SelectTagDialogFragment.ARGUMENT_TAG, 0)
                val note = notesAdapter.getItem(position).apply { this.tag = tag }  // アダプタ側のタグ更新
                presenter?.updateNote(note)                                         // DB側のタグ更新
                notesAdapter.notifyItemChanged(position)                            // 変更の通知
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_refresh -> presenter?.loadNotes(true)
            R.id.menu_delete_all -> presenter?.deleteAllNotes()
        }
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu?, inflater: MenuInflater) = inflater.inflate(R.menu.notes_fragment_menu, menu)

    override fun setLoadingIndicator(active: Boolean) {
        val root = view ?: return
        with(root.findViewById(R.id.refresh_layout) as SwipeRefreshLayout) {
            // Make sure setRefreshing() is called after the layout is done with everything else.
            post { isRefreshing = active }
        }
    }

    override fun filterNotes(tag: Int) = notesAdapter.filter(tag)

    override fun showNotes(notes: MutableList<Note>) {
        notesAdapter.notesCopy = mutableListOf(*notes.toTypedArray())
        notesAdapter.filter((activity as NotesActivity).filterTag)      // フィルタを書けるのでコピーの方だけ初期化
        notesView.visibility = View.VISIBLE
        noNotesView.visibility = View.GONE
    }

    override fun showNoNotes() {
        notesView.visibility = View.GONE
        noNotesView.visibility = View.VISIBLE
    }

    override fun showAddNote() {
        val intent = Intent(context, AddEditNoteActivity::class.java).apply {
            putExtra(AddEditNoteFragment.ARGUMENT_EDIT_NOTE_TAG, (activity as NotesActivity).filterTag)
        }
        startActivityForResult(intent, AddEditNoteActivity.REQUEST_ADD_NOTE)
    }

    override fun showDeleteNote(title: String) = showMessage(getString(R.string.delete_note_message, title))

    override fun showDeleteAllNotes() = showMessage(getString(R.string.all_notes_deleted_message))

    override fun showEditNoteUi(noteId: String) {
        val intent = Intent(context, AddEditNoteActivity::class.java).apply {
            putExtra(AddEditNoteFragment.ARGUMENT_EDIT_NOTE_ID, noteId)
        }
        startActivity(intent)
    }

    override fun showLoadingNotesError() = showMessage(getString(R.string.loading_notes_error))

    override fun showNoNotesError() = showMessage(getString(R.string.no_notes_error))

    private fun showMessage(message: String) = view?.snackbarLong(message) ?: Unit

    private class NotesAdapter(val context: Context, notes: MutableList<Note>)
        : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        var notes: MutableList<Note> = notes
            set(notes) {
                field = notes
                notifyDataSetChanged()
            }

        var notesCopy: MutableList<Note> = mutableListOf(*notes.toTypedArray())

        private val inflater = LayoutInflater.from(context)

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder !is ViewHolder) return   // スマートキャスト

            val note = notes[position]
            if (notes.size > position) {
                // itemの設定とリスナの登録
                holder.apply {
                    if (note.tag == 0) tag.visibility = View.INVISIBLE
                    else {
                        tag.visibility = View.VISIBLE
                        tag.setColorFilter(context.resources.obtainTypedArray(R.array.tag_color).getColor(note.tag, 0))
                    }
                    title.text = note.titleForList
                    date.text = Note.format.format(Date(note.date))
                    itemView.setOnClickListener {
                        onClickNoteItem(holder.adapterPosition)
                    }
                    menu.setOnClickListener {
                        popup.showAsDropDown(menu, menu.width, -menu.height)    // ポップアップメニューの表示
                    }
                    popup.contentView.findViewById(R.id.note_item_menu_delete).setOnClickListener {
                        onClickNoteItemMenu(holder.adapterPosition, R.id.note_item_menu_delete)
                        popup.dismiss()
                    }
                    popup.contentView.findViewById(R.id.note_item_menu_select_tag).setOnClickListener {
                        onClickNoteItemMenu(holder.adapterPosition, R.id.note_item_menu_select_tag)
                        popup.dismiss()
                    }
                }
            }
        }

        override fun getItemCount(): Int = notes.size

        override fun onCreateViewHolder(parent: ViewGroup?, viewType: Int): RecyclerView.ViewHolder
            = ViewHolder(inflater.inflate(R.layout.note_item, parent, false), context)

        // ViewHolderに対するクリックリスナはonBindViewHolderで登録
        class ViewHolder(itemView: View, context: Context) : RecyclerView.ViewHolder(itemView) {
            val tag = itemView.findViewById(R.id.note_item_tag) as ImageView
            val title = itemView.findViewById(R.id.note_item_title) as TextView
            val date = itemView.findViewById(R.id.note_item_date) as TextView
            val menu = itemView.findViewById(R.id.note_item_menu) as ImageButton
            val popup = PopupWindow(context).apply {
                contentView = LayoutInflater.from(context).inflate(R.layout.note_menu, null)
                isOutsideTouchable = true
                isFocusable = true
                isTouchable = true
            }
        }

        // 正直あまり綺麗ではないと思う
        fun filter(tag: Int) {
            notes.clear()
            notes = if (tag == 0) mutableListOf(*notesCopy.toTypedArray())
                    else          notesCopy.filter { it.tag == tag }.toMutableList()
        }

        fun removeItem(position: Int) {
            require(position in 0 until itemCount)
            val note = getItem(position)
            notes.remove(note)
            notesCopy.remove(note)
            notifyItemRemoved(position)
        }

        fun getItem(position: Int): Note {
            require(position in 0 until itemCount)
            return notes[position]
        }

        // RecyclerViewのアイテムタップ時のリスナ
        lateinit var onClickNoteItem: (position: Int) -> Unit

        // RecyclerViewのアイテムのメニュータップ時のリスナ
        lateinit var onClickNoteItemMenu: (position: Int, menuId: Int) -> Unit
    }

    companion object {
        fun newInstance() = NotesFragment()
        val SELECT_TAG_REQUEST_CODE = 2
    }
}