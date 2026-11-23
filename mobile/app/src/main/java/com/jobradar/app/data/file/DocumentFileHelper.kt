package com.jobradar.app.data.file

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.jobradar.app.core.network.AppResult
import com.jobradar.app.core.network.NetworkErrorCodes
import com.jobradar.app.domain.model.SelectedDocument
import com.jobradar.app.domain.repository.DocumentFileInspector
import com.jobradar.app.domain.validator.DocumentFileValidator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

private val logger = Logger.getLogger("DocumentFileHelper")

@Singleton
class DocumentFileHelper @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DocumentFileInspector, DocumentMultipartFactory {

    override suspend fun inspect(uriString: String): AppResult<SelectedDocument> =
        withContext(Dispatchers.IO) {
            try {
                val uri = uriString.toUri()
                val resolver = context.contentResolver
                val (displayName, queriedSize) = queryDisplayNameAndSize(uri)
                val sizeBytes = resolveSizeBytes(uri, queriedSize)
                val resolvedName = displayName.ifBlank {
                    uri.lastPathSegment?.substringAfterLast('/') ?: "document"
                }
                val effectiveMime = DocumentFileValidator.resolveEffectiveMimeType(
                    rawMimeType = resolver.getType(uri),
                    displayName = resolvedName,
                )
                AppResult.Success(
                    SelectedDocument(
                        uriString = uriString,
                        displayName = resolvedName,
                        sizeBytes = sizeBytes,
                        mimeType = effectiveMime,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                logger.log(Level.WARNING, "Permission denied reading document metadata", e)
                AppResult.Error(
                    code = NetworkErrorCodes.FILE_READ_ERROR,
                    message = "Permission denied reading selected file.",
                    cause = e,
                )
            } catch (e: IOException) {
                logger.log(Level.WARNING, "I/O failure reading document metadata", e)
                AppResult.Error(
                    code = NetworkErrorCodes.FILE_READ_ERROR,
                    message = "Unable to read selected file.",
                    cause = e,
                )
            } catch (e: IllegalArgumentException) {
                logger.log(Level.WARNING, "Invalid URI when inspecting document", e)
                AppResult.Error(
                    code = NetworkErrorCodes.FILE_READ_ERROR,
                    message = "Invalid file URI.",
                    cause = e,
                )
            }
        }

    override suspend fun createPart(
        document: SelectedDocument,
        partName: String,
    ): AppResult<MultipartBody.Part> = withContext(Dispatchers.IO) {
        try {
            val uri = document.uriString.toUri()
            val resolver = context.contentResolver
            val canOpen = resolver.openInputStream(uri)?.use { true } ?: false
            if (!canOpen) {
                return@withContext AppResult.Error(
                    code = NetworkErrorCodes.FILE_READ_ERROR,
                    message = "Could not open selected file stream.",
                )
            }

            val effectiveMime = DocumentFileValidator.resolveEffectiveMimeType(
                rawMimeType = document.mimeType,
                displayName = document.displayName,
            )
            val mediaType = effectiveMime.toMediaTypeOrNull()
                ?: DocumentFileValidator.MIME_PDF.toMediaType()
            val safeFileName = sanitizeFilename(document.displayName, fallbackPrefix = partName)
            val requestBody = StreamingUriRequestBody(
                context = context,
                uri = uri,
                mediaType = mediaType,
                knownSizeBytes = document.sizeBytes,
            )
            AppResult.Success(MultipartBody.Part.createFormData(partName, safeFileName, requestBody))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            logger.log(Level.WARNING, "Permission denied opening document stream", e)
            AppResult.Error(
                code = NetworkErrorCodes.FILE_READ_ERROR,
                message = "Permission denied opening selected file.",
                cause = e,
            )
        } catch (e: IOException) {
            logger.log(Level.WARNING, "Failed to prepare multipart file part", e)
            AppResult.Error(
                code = NetworkErrorCodes.FILE_READ_ERROR,
                message = "Unable to prepare file upload.",
                cause = e,
            )
        }
    }

    private fun queryDisplayNameAndSize(uri: Uri): Pair<String, Long> {
        var displayName = ""
        var size = -1L
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    displayName = cursor.getString(nameIndex).orEmpty()
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
        return displayName to size
    }

    private fun resolveSizeBytes(uri: Uri, queriedSize: Long): Long {
        if (queriedSize >= 0L) return queriedSize
        val fdLength = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            ?: -1L
        if (fdLength >= 0L) return fdLength

        val maxInspectBytes = DocumentFileValidator.MAX_FILE_SIZE_BYTES + 1L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        context.contentResolver.openInputStream(uri)?.use { stream ->
            while (total <= maxInspectBytes) {
                val read = stream.read(buffer)
                if (read <= 0) break
                total += read
            }
        }
        return total
    }

    private fun sanitizeFilename(rawName: String, fallbackPrefix: String): String {
        val cleaned = rawName
            .replace("\"", "_")
            .replace("\r", "")
            .replace("\n", "")
            .trim()
        return cleaned.ifEmpty { "${fallbackPrefix}.bin" }
    }

    private class StreamingUriRequestBody(
        private val context: Context,
        private val uri: Uri,
        private val mediaType: MediaType,
        private val knownSizeBytes: Long,
    ) : RequestBody() {

        override fun contentType(): MediaType = mediaType

        override fun contentLength(): Long = if (knownSizeBytes > 0L) knownSizeBytes else -1L

        override fun writeTo(sink: BufferedSink) {
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Unable to open stream for selected document.")
            stream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var totalWritten = 0L
                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead < 0) break
                    totalWritten += bytesRead
                    if (totalWritten > DocumentFileValidator.MAX_FILE_SIZE_BYTES) {
                        throw IOException("Selected file exceeds 5 MB upload limit.")
                    }
                    sink.write(buffer, 0, bytesRead)
                }
            }
        }
    }
}
