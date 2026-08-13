package com.phisher98

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CommonActivity.showToast

class MovicsCustomSectionsFragment(
    private val plugin: StreamPlayPlugin,
    private val sharedPref: SharedPreferences,
    private val onDismissCallback: (() -> Unit)? = null,
) : DialogFragment() {

    private val res = plugin.resources ?: error("Unable to access plugin resources")

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            val metrics = resources.displayMetrics
            val maxWidth = (520 * metrics.density).toInt()
            setLayout(minOf((metrics.widthPixels * 0.92f).toInt(), maxWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) error("Layout $name not found")
        return inflater.inflate(res.getLayout(id), container, false)
    }

    private fun getDrawable(name: String): Drawable {
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return res.getDrawable(id, null) ?: error("Drawable $name not found")
    }

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) error("View $name not found")
        return findViewById(id)
    }

    private fun View.makeTvCompatible() {
        val id = res.getIdentifier("outline", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id != 0) background = res.getDrawable(id, null)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = getLayout("movics_custom_sections_layout", inflater, container)
        val backgroundId = res.getIdentifier("dialog_background", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (backgroundId != 0) root.background = res.getDrawable(backgroundId, null)

        val addRow: View = root.findView("addSectionRow")
        val manageRow: View = root.findView("manageSectionsRow")
        val addIcon: ImageView = root.findView("addSectionIcon")
        val manageIcon: ImageView = root.findView("manageSectionsIcon")
        val saveIcon: ImageView = root.findView("saveIcon")

        addRow.makeTvCompatible()
        manageRow.makeTvCompatible()
        saveIcon.makeTvCompatible()
        addIcon.setImageDrawable(getDrawable("add_icon"))
        manageIcon.setImageDrawable(getDrawable("settings_icon"))
        saveIcon.setImageDrawable(getDrawable("save_icon"))

        addRow.setOnClickListener { showAddDialog(inflater, container) }
        manageRow.setOnClickListener { showSavedSections(inflater, container) }
        saveIcon.setOnClickListener { confirmRestart() }
        return root
    }

    private fun showAddDialog(inflater: LayoutInflater, container: ViewGroup?) {
        val view = getLayout("movics_add_custom_section", inflater, container)
        val name: EditText = view.findView("sectionName")
        val value: EditText = view.findView("sectionValue")
        val categorySpinner: Spinner = view.findView("categorySpinner")
        val mediaSpinner: Spinner = view.findView("mediaTypeSpinner")
        val help: TextView = view.findView("valueHelp")

        val categories = MovicsSectionCategory.entries
        categorySpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            categories.map { it.label },
        )
        mediaSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            listOf("General / Auto", "Movies", "TV Shows", "Mixed: Movies + TV"),
        )

        categorySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, item: View?, position: Int, id: Long) {
                val category = categories[position]
                val fixedMediaType = when (category) {
                    MovicsSectionCategory.MOVIES, MovicsSectionCategory.COLLECTIONS -> 1
                    MovicsSectionCategory.TV_SHOWS, MovicsSectionCategory.NETWORKS -> 2
                    else -> null
                }
                mediaSpinner.isEnabled = fixedMediaType == null
                fixedMediaType?.let(mediaSpinner::setSelection)
                help.text = when (category) {
                    MovicsSectionCategory.LANGUAGE -> "Language code(s), for example: ko or ko,ja"
                    MovicsSectionCategory.TMDB_LINK -> "Example: https://www.themoviedb.org/movie/top-rated or /person"
                    MovicsSectionCategory.PEOPLE -> "One or more TMDB person IDs, for example: 287,1245"
                    MovicsSectionCategory.PERSON_WORKS -> "TMDB person ID(s). Their movies/TV shows appear directly in this section."
                    MovicsSectionCategory.MOVIES, MovicsSectionCategory.TV_SHOWS -> "One or more exact TMDB title IDs"
                    else -> "One or more TMDB IDs separated with commas"
                }
            }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val sectionName = name.text.toString().trim()
                val rawValue = value.text.toString().trim()
                val category = categories[categorySpinner.selectedItemPosition]
                val mediaType = when (mediaSpinner.selectedItemPosition) {
                    1 -> "movie"
                    2 -> "tv"
                    3 -> "mixed"
                    else -> "general"
                }
                val error = validate(sectionName, category, rawValue)
                if (error != null) {
                    showToast(error)
                    return@setOnClickListener
                }

                val sections = MovicsCustomSections.load(sharedPref).toMutableList()
                sections.add(
                    MovicsCustomSection(
                        name = sectionName,
                        category = category,
                        mediaType = mediaType,
                        value = rawValue,
                    )
                )
                MovicsCustomSections.save(sharedPref, sections)
                showToast("Section added. Reload Movics to display it.")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun validate(name: String, category: MovicsSectionCategory, value: String): String? {
        if (name.isBlank()) return "Enter a section name"
        if (value.isBlank()) return "Enter an ID, value, or TMDB link"
        if (category == MovicsSectionCategory.TMDB_LINK) {
            val lower = value.lowercase()
            if (!lower.startsWith("https://www.themoviedb.org/") && !lower.startsWith("https://themoviedb.org/")) {
                return "Enter a valid themoviedb.org link"
            }
            return null
        }
        if (category == MovicsSectionCategory.LANGUAGE) {
            val valid = value.split(',').map { it.trim() }.all { it.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z]{2})?")) }
            return if (valid) null else "Use language codes such as ko, en, or ar"
        }
        val validIds = value.split(',').map { it.trim() }.all { it.toIntOrNull()?.let { id -> id > 0 } == true }
        return if (validIds) null else "Use one or more numeric TMDB IDs separated by commas"
    }

    private fun showSavedSections(inflater: LayoutInflater, container: ViewGroup?) {
        val view = getLayout("stremio_dialog_list_links", inflater, container)
        val recycler: RecyclerView = view.findView("rvLinks")
        val empty: TextView = view.findView("tvNoLinks")
        val sections = MovicsCustomSections.load(sharedPref).toMutableList()
        empty.text = "There are no custom sections yet."

        if (sections.isEmpty()) {
            empty.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            empty.visibility = View.GONE
            recycler.visibility = View.VISIBLE
            recycler.layoutManager = LinearLayoutManager(requireContext())
            recycler.adapter = SectionsAdapter(sections) { section ->
                val updated = MovicsCustomSections.load(sharedPref).filterNot { it.id == section.id }
                MovicsCustomSections.save(sharedPref, updated)
                (recycler.adapter as? SectionsAdapter)?.remove(section)
                showToast("Section deleted. Reload Movics to apply.")
                if (updated.isEmpty()) {
                    empty.visibility = View.VISIBLE
                    recycler.visibility = View.GONE
                }
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Saved custom sections")
            .setView(view)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun confirmRestart() {
        AlertDialog.Builder(requireContext())
            .setTitle("Save & Reload")
            .setMessage("Restart Cloudstream now to load the updated home sections?")
            .setPositiveButton("Yes") { _, _ -> restartApp() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun restartApp() {
        val context = requireContext().applicationContext
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        launch?.component?.let { component ->
            context.startActivity(Intent.makeRestartActivityTask(component))
            Runtime.getRuntime().exit(0)
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onDismissCallback?.invoke()
    }

    private inner class SectionsAdapter(
        private val items: MutableList<MovicsCustomSection>,
        private val onDelete: (MovicsCustomSection) -> Unit,
    ) : RecyclerView.Adapter<SectionsAdapter.Holder>() {

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findView("tvName")
            val value: TextView = view.findView("tvLink")
            val type: TextView = view.findView("tvType")
            val delete: ImageButton = view.findView("btnDelete")
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(getLayout("stremio_item_saved_link", LayoutInflater.from(parent.context), parent))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val section = items[position]
            holder.name.text = section.name
            holder.value.text = section.value
            val mediaLabel = when (section.mediaType) {
                "movie" -> "Movies"
                "tv" -> "TV"
                "mixed" -> "Mixed"
                else -> "General"
            }
            holder.type.text = "${section.category.label} • $mediaLabel"
            holder.delete.setOnClickListener { onDelete(section) }
        }

        override fun getItemCount(): Int = items.size

        fun remove(section: MovicsCustomSection) {
            val index = items.indexOfFirst { it.id == section.id }
            if (index >= 0) {
                items.removeAt(index)
                notifyItemRemoved(index)
            }
        }
    }
}
