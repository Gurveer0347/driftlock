package org.driftlock.app.storage

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object SessionExports {
    /** Returns a chooser intent only. The caller must launch it following an explicit user action. */
    fun shareIntent(context: Context, file: File): Intent {
        require(file.isFile && file.canonicalFile.parentFile == File(context.filesDir, "session_exports").canonicalFile)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".session-files", file)
        val send = Intent(Intent.ACTION_SEND).setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Export DRIFTLOCK session")
    }
}
