package vn.codex.skinlab

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract as D
import java.io.File
import java.util.zip.ZipFile

internal class SafExtraInstaller(private val resolver: ContentResolver, private val tree: Uri) {
    private val root = D.buildDocumentUriUsingTree(tree, D.getTreeDocumentId(tree))
    private fun child(parent: Uri, name: String): Uri? {
        val list = D.buildChildDocumentsUriUsingTree(tree, D.getDocumentId(parent))
        resolver.query(list, arrayOf(D.Document.COLUMN_DOCUMENT_ID, D.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1) == name) return D.buildDocumentUriUsingTree(tree, cursor.getString(0))
        }
        return null
    }
    private fun directory(parent: Uri, name: String): Uri = child(parent, name)
        ?: D.createDocument(resolver, parent, D.Document.MIME_TYPE_DIR, name) ?: error("Không tạo được thư mục $name.")

    fun install(file: File) {
        val wrapped = validateExtraArchive(file)
        check(child(root, "Extra1") == null) { "Extra1 đã tồn tại. Hãy giữ bản sao trước khi Fix Data lần nữa." }
        var original = child(root, "Extra") ?: error("Không thấy Extra trong thư mục đã cấp quyền.")
        check(child(root, "SenceLabExtraPending") == null) { "Thư mục tạm đã tồn tại." }
        var stage: Uri? = D.createDocument(resolver, root, D.Document.MIME_TYPE_DIR, "SenceLabExtraPending")
            ?: error("Không tạo được thư mục tạm qua SAF.")
        try {
            val directories = hashMapOf("" to stage!!)
            ZipFile(file).use { zip ->
                for (entry in zip.entries()) {
                    val name = if (wrapped) entry.name.removePrefix("Extra/") else entry.name
                    val parts = name.trimEnd('/').split('/').filter(String::isNotEmpty)
                    if (parts.isEmpty()) continue
                    var parent = stage!!
                    var path = ""
                    for (part in parts.dropLast(if (entry.isDirectory) 0 else 1)) {
                        path += "$part/"
                        parent = directories.getOrPut(path) { directory(parent, part) }
                    }
                    if (!entry.isDirectory) {
                        val uri = D.createDocument(resolver, parent, "application/octet-stream", parts.last()) ?: error("Không tạo được tệp.")
                        resolver.openOutputStream(uri, "w")?.use { out -> zip.getInputStream(entry).use { it.copyTo(out) } }
                            ?: error("Không ghi được tệp qua SAF.")
                    }
                }
            }
            original = D.renameDocument(resolver, original, "Extra1") ?: error("Không đổi tên được Extra thành Extra1.")
            try {
                check(D.renameDocument(resolver, stage!!, "Extra") != null) { "Không thay được thư mục Extra." }
                stage = null
            } catch (failure: Exception) {
                check(D.renameDocument(resolver, original, "Extra") != null) { "Không khôi phục được Extra. Bản gốc đang ở Extra1." }
                throw failure
            }
        } finally {
            stage?.let { runCatching { D.deleteDocument(resolver, it) } }
        }
    }
}
