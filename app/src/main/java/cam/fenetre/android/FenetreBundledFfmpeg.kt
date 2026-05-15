package cam.fenetre.android

import android.content.Context
import android.util.Log
import java.io.File

object FenetreBundledFfmpeg {
    private const val TAG = "FenetreBundledFfmpeg"
    private const val EXECUTABLE_LIBRARY_NAME = "libffmpeg_exec.so"

    fun installIfAvailable(context: Context): File? {
        val executable = executableFile(context)
        if (!executable.exists()) {
            Log.w(TAG, "Bundled FFmpeg not found at ${executable.absolutePath}")
            return null
        }
        if (!executable.canExecute() && !executable.setExecutable(true, false)) {
            Log.w(TAG, "Bundled FFmpeg is not executable at ${executable.absolutePath}")
        }
        return executable
    }

    fun executablePath(context: Context): String = executableFile(context).absolutePath

    private fun executableFile(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, EXECUTABLE_LIBRARY_NAME)
}
