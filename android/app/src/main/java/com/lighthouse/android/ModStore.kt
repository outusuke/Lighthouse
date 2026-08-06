package com.lighthouse.android

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The mods folder, as the engine sees it.
 *
 * Which mods are on, and in what order, is not a property of the folder: the
 * engine keeps it in the `gSettings.EnabledMods` / `gSettings.DisabledMods`
 * cvars, pipe-separated and keyed by filename, and loads them in the order the
 * enabled list gives (see src/port/UI/LighthouseModMenuWindow.cpp). This class
 * reads that list out of lighthouse.cfg.json to show state, and changes it by
 * calling into the running engine — never by renaming files, which would change
 * the very key the cvars refer to.
 *
 * A mod is one of three things, all of which the engine accepts:
 *   - an `.o2r` archive
 *   - a `.zip` archive
 *   - a plain folder
 */
object ModStore {

    private const val TAG = "ModStore"

    /** Matches SEPARATOR in src/port/UI/LighthouseModMenuWindow.cpp. */
    private const val SEPARATOR = "|"
    private const val CONFIG_NAME = "lighthouse.cfg.json"

    data class Mod(
        val file: File,
        /** The filename, which is the key the engine's cvars refer to. */
        val name: String,
        val enabled: Boolean,
        val isFolder: Boolean
    ) {
        val kind: String
            get() = when {
                isFolder -> "folder"
                name.endsWith(".zip", ignoreCase = true) -> "zip"
                else -> "o2r"
            }
    }

    /**
     * Mods in load order: the last entry wins where two mods touch the same file.
     *
     * Order and enabled state come from the engine's `gSettings.EnabledMods`
     * cvar, read out of lighthouse.cfg.json — the same list the in-game Mod Menu
     * edits. Anything on disk the cvars have not seen yet is appended as
     * disabled, which is how the engine treats it on the next launch.
     */
    fun list(context: Context): List<Mod> {
        val dir = GameAssets.modsDir(context)
        dir.mkdirs()

        val onDisk = (dir.listFiles() ?: emptyArray())
            .filter { it.name != "place_mods_here.txt" }
            .associateBy { it.name }

        val enabledOrder = readModCVar(context, "EnabledMods")
        val ordered = LinkedHashMap<String, Mod>()

        for (name in enabledOrder) {
            val file = onDisk[name] ?: continue
            ordered[name] = Mod(file, name, enabled = true, isFolder = file.isDirectory)
        }
        for ((name, file) in onDisk.entries.sortedBy { it.key.lowercase() }) {
            if (name in ordered) continue
            ordered[name] = Mod(file, name, enabled = false, isFolder = file.isDirectory)
        }
        return ordered.values.toList()
    }

    /**
     * Toggles a mod through the engine rather than by touching the file.
     *
     * The engine keys its enabled/disabled cvars by filename, so renaming a file
     * to mark it disabled would both fail to disable it and break the name the
     * cvar refers to. Writing lighthouse.cfg.json from here would not work
     * either: the engine is running behind this screen and would overwrite the
     * file from its own in-memory state.
     */
    fun setEnabled(mod: Mod, enabled: Boolean): String? {
        if (mod.enabled == enabled) return null
        return try {
            nativeSetModEnabled(mod.name, enabled)
            null
        } catch (error: UnsatisfiedLinkError) {
            Log.e(TAG, "Engine not available to toggle ${mod.name}", error)
            "Could not reach the game to change ${mod.name}."
        }
    }

    /** Reads a pipe-separated mod cvar out of the engine's config file. */
    private fun readModCVar(context: Context, key: String): List<String> {
        val config = File(GameAssets.gameDir(context), CONFIG_NAME)
        if (!config.isFile) return emptyList()
        return try {
            val settings = JSONObject(config.readText()).optJSONObject("CVars")
                ?.optJSONObject("gSettings")
            settings?.optString(key).orEmpty()
                .split(SEPARATOR)
                .filter { it.isNotEmpty() }
        } catch (error: Exception) {
            Log.w(TAG, "Could not read $key from $CONFIG_NAME", error)
            emptyList()
        }
    }

    private external fun nativeSetModEnabled(name: String, enabled: Boolean)

    fun delete(mod: Mod): String? =
        if (mod.file.deleteRecursively()) null else "Could not delete ${mod.name}."

    /**
     * Reorders the enabled mods by replaying them through the engine.
     *
     * Load order is the order of the names in the EnabledMods cvar, so it is
     * changed by re-enabling them in the wanted order rather than by renaming
     * files. Renaming would change the very name the cvar refers to, which is
     * how the previous filename-prefix scheme silently disabled whatever it
     * reordered.
     */
    fun applyOrder(mods: List<Mod>): String? {
        return try {
            // EnableMod inserts at the front, so applying in reverse leaves the
            // list in the order shown.
            for (mod in mods.filter { it.enabled }.asReversed()) {
                nativeSetModEnabled(mod.name, false)
                nativeSetModEnabled(mod.name, true)
            }
            null
        } catch (error: UnsatisfiedLinkError) {
            Log.e(TAG, "Engine not available to reorder mods", error)
            "Could not reach the game to reorder the mods."
        }
    }

    /** Copies an `.o2r`/`.zip` picked through the document picker into the mods folder. */
    fun importArchive(context: Context, uri: Uri): String? {
        val displayName = queryDisplayName(context, uri) ?: return "Could not read the file's name."
        if (!displayName.endsWith(".o2r", true) && !displayName.endsWith(".zip", true)) {
            return "$displayName is not a mod. Mods are .o2r or .zip files, or folders."
        }

        val target = nextFreeFile(context, displayName)
        return try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "The document provider did not open $displayName." }
                FileOutputStream(target).use { output -> input.copyTo(output, COPY_BUFFER) }
            }
            null
        } catch (error: Exception) {
            target.delete()
            Log.e(TAG, "Import of $displayName failed", error)
            "Could not import $displayName: ${error.message ?: error}"
        }
    }

    /** Copies a folder picked through the tree picker into the mods folder. */
    fun importFolder(context: Context, treeUri: Uri): String? {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        val displayName = queryDisplayName(context, rootUri) ?: documentId.substringAfterLast('/')

        // The engine reads everything after the last dot as an extension and
        // ignores folders whose "extension" it does not know, so a folder named
        // "Cool.Mod v2" would silently never load.
        val safeName = displayName.replace('.', '_')
        val target = nextFreeFile(context, safeName)

        return try {
            copyTree(context, rootUri, treeUri, target)
            null
        } catch (error: Exception) {
            target.deleteRecursively()
            Log.e(TAG, "Import of folder $displayName failed", error)
            "Could not import $displayName: ${error.message ?: error}"
        }
    }

    /** Writes a mod back out to a location the user picked. Folders are zipped. */
    fun export(context: Context, mod: Mod, destination: Uri): String? {
        return try {
            context.contentResolver.openOutputStream(destination).use { output ->
                requireNotNull(output) { "The document provider did not open the destination." }
                if (mod.isFolder) {
                    ZipOutputStream(output.buffered()).use { zip -> zipTree(mod.file, mod.file, zip) }
                } else {
                    mod.file.inputStream().use { input -> input.copyTo(output, COPY_BUFFER) }
                }
            }
            null
        } catch (error: Exception) {
            Log.e(TAG, "Export of ${mod.name} failed", error)
            "Could not export ${mod.name}: ${error.message ?: error}"
        }
    }

    /** A folder mod leaves as a .zip, which the engine loads just the same. */
    fun exportName(mod: Mod): String = if (mod.isFolder) "${mod.name}.zip" else mod.name

    private const val COPY_BUFFER = 1 shl 16

    private fun nextFreeFile(context: Context, name: String): File {
        val dir = GameAssets.modsDir(context)
        dir.mkdirs()

        // Imported under its own name: the filename is the key the engine's mod
        // cvars use, so decorating it would divorce the file from any enabled
        // state already recorded for that name. Load order is the cvar's order,
        // not the filename's, and a new mod starts disabled at the end anyway.
        var candidate = File(dir, name)
        var attempt = 1
        while (candidate.exists()) {
            // Keep the extension; the engine decides what a file is by it.
            val stem = name.substringBeforeLast('.', name)
            val extension = name.substringAfterLast('.', "")
            val suffix = if (extension.isEmpty()) "" else ".$extension"
            candidate = File(dir, "${stem}_$attempt$suffix")
            attempt++
        }
        return candidate
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun copyTree(context: Context, documentUri: Uri, treeUri: Uri, target: File) {
        target.mkdirs()

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(documentUri)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val childId = cursor.getString(0)
                val childName = cursor.getString(1)
                val isDirectory = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                val childTarget = File(target, childName)

                if (isDirectory) {
                    copyTree(context, childUri, treeUri, childTarget)
                } else {
                    context.contentResolver.openInputStream(childUri).use { input ->
                        if (input == null) throw IOException("Could not read $childName.")
                        FileOutputStream(childTarget).use { output -> input.copyTo(output, COPY_BUFFER) }
                    }
                }
            }
        }
    }

    private fun zipTree(root: File, current: File, zip: ZipOutputStream) {
        for (child in current.listFiles() ?: emptyArray()) {
            val relative = child.relativeTo(root).path
            if (child.isDirectory) {
                zip.putNextEntry(ZipEntry("$relative/"))
                zip.closeEntry()
                zipTree(root, child, zip)
            } else {
                zip.putNextEntry(ZipEntry(relative))
                child.inputStream().use { input -> input.copyTo(zip, COPY_BUFFER) }
                zip.closeEntry()
            }
        }
    }
}
