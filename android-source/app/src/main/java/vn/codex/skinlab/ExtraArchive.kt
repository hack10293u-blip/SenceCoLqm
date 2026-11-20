package vn.codex.skinlab

import java.io.File
import java.util.zip.ZipFile

internal fun validateExtraArchive(file: File): Boolean {
    ZipFile(file).use { zip ->
        val entries = zip.entries().toList()
        require(entries.size in 1..100000) { "Số mục ZIP không hợp lệ." }
        val wrapped = entries.any { it.name == "Extra/2022.V3/assetbundle/modules/autochess/scene_artist_autochess_01.assetbundle" }
        var total = 0L
        val names = HashSet<String>()
        for (entry in entries) {
            val name = entry.name
            require(name.isNotEmpty() && !name.startsWith('/') && !name.contains('\\') && !name.contains(':') &&
                name.split('/').none { it == ".." || it == "." } && names.add(name)) { "Đường dẫn ZIP không hợp lệ." }
            require(!wrapped || name.startsWith("Extra/")) { "ZIP chứa dữ liệu ngoài Extra." }
            require(entry.size >= 0) { "ZIP thiếu dung lượng mục." }
            total += entry.size
            require(total <= 4L * 1024 * 1024 * 1024) { "Dữ liệu giải nén vượt 4 GB." }
        }
        val target = (if (wrapped) "Extra/" else "") + "2022.V3/assetbundle/modules/autochess/scene_artist_autochess_01.assetbundle"
        require((zip.getEntry(target)?.size ?: 0) > 0) { "ZIP thiếu tệp map." }
        return wrapped
    }
}
