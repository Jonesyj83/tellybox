package com.openstream.tv.ui.playlists

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.openstream.tv.R
import com.openstream.tv.appContainer
import com.openstream.tv.data.db.PlaylistEntity
import com.openstream.tv.data.db.PlaylistType
import com.openstream.tv.data.parser.stalker.StalkerClient
import com.openstream.tv.databinding.ActivityAddPlaylistBinding
import com.openstream.tv.databinding.ItemChoiceBinding
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class AddPlaylistActivity : AppCompatActivity() {

    private enum class Mode { M3U_URL, M3U_FILE, XTREAM, STALKER }

    private lateinit var binding: ActivityAddPlaylistBinding
    private var mode: Mode? = null
    private var pickedUri: Uri? = null

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        pickedUri = uri
        val name = displayName(uri)
        binding.fileLabel.text = name ?: uri.toString()
        binding.urlInput.setText("")
        if (binding.nameInput.text.isNullOrBlank() && name != null) {
            binding.nameInput.setText(name.substringBeforeLast('.'))
        }
        binding.saveButton.requestFocus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddPlaylistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindChoice(binding.typeM3uUrl, R.string.type_m3u_url, R.string.type_m3u_url_desc, Mode.M3U_URL)
        bindChoice(binding.typeM3uFile, R.string.type_m3u_file, R.string.type_m3u_file_desc, Mode.M3U_FILE)
        bindChoice(binding.typeXtream, R.string.type_xtream, R.string.type_xtream_desc, Mode.XTREAM)
        bindChoice(binding.typeStalker, R.string.type_stalker, R.string.type_stalker_desc, Mode.STALKER)

        binding.pickFileButton.setOnClickListener { pickFile() }
        binding.saveButton.setOnClickListener { save() }
        binding.backButton.setOnClickListener { showTypeStep() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.progress.visibility == View.VISIBLE -> Unit
                    mode != null -> showTypeStep()
                    else -> finish()
                }
            }
        })

        binding.typeM3uUrl.root.requestFocus()
    }

    private fun bindChoice(b: ItemChoiceBinding, title: Int, desc: Int, m: Mode) {
        b.choiceTitle.setText(title)
        b.choiceDesc.setText(desc)
        b.root.setOnClickListener { showForm(m) }
    }

    private fun showTypeStep() {
        mode = null
        binding.formStep.visibility = View.GONE
        binding.typeStep.visibility = View.VISIBLE
        binding.typeM3uUrl.root.requestFocus()
    }

    private fun showForm(m: Mode) {
        mode = m
        binding.typeStep.visibility = View.GONE
        binding.formStep.visibility = View.VISIBLE
        binding.formError.visibility = View.GONE

        fun show(v: View, visible: Boolean) { v.visibility = if (visible) View.VISIBLE else View.GONE }
        show(binding.fileGroup, m == Mode.M3U_FILE)
        show(binding.credentialsGroup, m == Mode.XTREAM)
        show(binding.macGroup, m == Mode.STALKER)

        binding.formType.setText(
            when (m) {
                Mode.M3U_URL -> R.string.type_m3u_url
                Mode.M3U_FILE -> R.string.type_m3u_file
                Mode.XTREAM -> R.string.type_xtream
                Mode.STALKER -> R.string.type_stalker
            },
        )
        binding.urlLabel.setText(
            when (m) {
                Mode.M3U_URL, Mode.M3U_FILE -> R.string.field_url
                Mode.XTREAM -> R.string.field_server
                Mode.STALKER -> R.string.field_portal
            },
        )
        binding.urlInput.hint = when (m) {
            Mode.M3U_URL -> "http://"
            Mode.M3U_FILE -> getString(R.string.field_file_path_hint)
            Mode.XTREAM -> getString(R.string.field_server_hint)
            Mode.STALKER -> getString(R.string.field_portal_hint)
        }
        if (m == Mode.M3U_FILE) binding.urlLabel.setText(R.string.field_file_path)
        if (m == Mode.M3U_FILE) binding.pickFileButton.requestFocus() else binding.urlInput.requestFocus()
    }

    private fun pickFile() {
        try {
            openDocument.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_no_picker, Toast.LENGTH_LONG).show()
            binding.urlInput.requestFocus()
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun text(v: android.widget.EditText) = v.text?.toString()?.trim().orEmpty()

    private fun showError(msg: String) {
        binding.formError.text = msg
        binding.formError.visibility = View.VISIBLE
    }

    private fun required(label: Int) = getString(R.string.error_required, getString(label))

    private fun save() {
        val m = mode ?: return
        val url = text(binding.urlInput)
        val userAgent = text(binding.userAgentInput).ifEmpty { null }
        val epg = text(binding.epgInput).ifEmpty { null }

        val playlist: PlaylistEntity = when (m) {
            Mode.M3U_URL -> {
                if (url.isEmpty()) return showError(required(R.string.field_url))
                val full = if (url.contains("://")) url else "http://$url"
                PlaylistEntity(
                    name = text(binding.nameInput).ifEmpty { Uri.parse(full).host ?: getString(R.string.default_name_m3u) },
                    type = PlaylistType.M3U,
                    url = full,
                    userAgent = userAgent,
                )
            }
            Mode.M3U_FILE -> {
                val location = pickedUri?.toString() ?: url
                if (location.isEmpty()) return showError(required(R.string.field_file))
                PlaylistEntity(
                    name = text(binding.nameInput).ifEmpty { getString(R.string.default_name_m3u) },
                    type = PlaylistType.M3U,
                    url = location,
                    userAgent = userAgent,
                )
            }
            Mode.XTREAM -> {
                val user = text(binding.usernameInput)
                val pass = text(binding.passwordInput)
                if (url.isEmpty()) return showError(required(R.string.field_server))
                // Username/password can also come from a pasted get.php link.
                if ((user.isEmpty() || pass.isEmpty()) && !url.contains("username=")) {
                    return showError(required(if (user.isEmpty()) R.string.field_username else R.string.field_password))
                }
                PlaylistEntity(
                    name = text(binding.nameInput).ifEmpty { getString(R.string.default_name_xtream) },
                    type = PlaylistType.XTREAM,
                    url = url,
                    username = user.ifEmpty { null },
                    password = pass.ifEmpty { null },
                    userAgent = userAgent,
                )
            }
            Mode.STALKER -> {
                val mac = text(binding.macInput)
                if (url.isEmpty()) return showError(required(R.string.field_portal))
                if (!StalkerClient.isValidMac(mac)) return showError(getString(R.string.error_bad_mac))
                PlaylistEntity(
                    name = text(binding.nameInput).ifEmpty { getString(R.string.default_name_stalker) },
                    type = PlaylistType.STALKER,
                    url = url,
                    macAddress = StalkerClient.normalizeMac(mac),
                )
            }
        }

        binding.formError.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE
        binding.progress.requestFocus()

        val container = appContainer
        lifecycleScope.launch {
            // The import itself runs in the app scope so it finishes even if this screen closes.
            val job = container.appScope.async {
                val id = container.playlists.add(playlist, epg)
                id to container.playlists.refresh(id)
            }
            val (id, result) = job.await()
            result.onSuccess {
                container.appScope.launch { container.epg.refreshForPlaylist(id) }
                Toast.makeText(
                    this@AddPlaylistActivity,
                    getString(R.string.playlist_refresh_done, playlist.name, it.channels, it.movies, it.series),
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }.onFailure { e ->
                binding.progress.visibility = View.GONE
                AlertDialog.Builder(this@AddPlaylistActivity)
                    .setTitle(R.string.load_failed_title)
                    .setMessage(e.message ?: e.javaClass.simpleName)
                    .setPositiveButton(R.string.load_failed_edit) { _, _ ->
                        lifecycleScope.launch { container.playlists.delete(id) }
                        binding.saveButton.requestFocus()
                    }
                    .setNegativeButton(R.string.load_failed_keep) { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            }
        }
    }
}
