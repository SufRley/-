package com.xxjz.ledger.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.xxjz.ledger.core.common.Logger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/** 收据照片的最长边：足够看清小票，又不至于让备份包变成几十兆。 */
private const val MAX_PHOTO_EDGE = 1600

private const val PHOTO_QUALITY = 85

private const val TAG = "EditorPhoto"

/** 拍照 / 相册两个动作的句柄，由 [rememberPhotoCapture] 返回。 */
class PhotoCapture internal constructor(
    val takePhoto: () -> Unit,
    val pickFromGallery: () -> Unit,
)

/**
 * 收据照片：拍照与相册（PRD v1.9.1 第 5 条）。
 *
 * 两条路都先把图缩到长边 [MAX_PHOTO_EDGE] 再落到 `filesDir/receipts/`：
 * - 相机原图动辄 4000×3000、5MB 起，一张收据占这么多空间不值当；
 * - 缩放后顺时针校正 EXIF 旋转，避免在某些机型上竖着拍出来是横的。
 *
 * 落盘用 filesDir 而不是 cacheDir：备份会把照片一起打包，缓存被系统清掉就丢了。
 */
@Composable
internal fun rememberPhotoCapture(onCaptured: (String?) -> Unit): PhotoCapture {
    val context = LocalContext.current
    // 拍照要先给出目标 Uri，拍完再读回来；这里用 remember 保住这个中间文件
    val pending = remember { arrayOfNulls<File>(1) }

    val cameraLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val file = pending[0]
            if (ok && file != null) {
                onCaptured(compressTo(context, Uri.fromFile(file)))
            } else {
                file?.delete()
            }
            pending[0] = null
        }

    val galleryLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) onCaptured(compressTo(context, uri))
        }

    return remember(context) {
        PhotoCapture(
            takePhoto = {
                val target = newTempFile(context)
                pending[0] = target
                cameraLauncher.launch(
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target),
                )
            },
            pickFromGallery = {
                galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
    }
}

/** 拍照用的临时文件：放在 cacheDir 下，FileProvider 已经暴露了这个目录。 */
private fun newTempFile(context: Context): File {
    val dir = File(context.cacheDir, "shots").apply { mkdirs() }
    return File.createTempFile("shot_", ".jpg", dir).also {
        // FileProvider 拿不到未创建的空文件对应的 Uri 权限，先建出来
        it.writeBytes(ByteArray(0))
    }
}

/**
 * 把任意来源的图压缩后存进 `filesDir/receipts/`，返回绝对路径；失败返回 null。
 *
 * 解码用 `inSampleSize` 两段式：先只读尺寸算出采样率，再按需解码，
 * 这样一张 4000×3000 的原图不会在解码阶段就把内存顶爆。
 */
private fun compressTo(
    context: Context,
    source: Uri,
): String? =
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.open(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeOf(bounds.outWidth, bounds.outHeight) }
        val bitmap = context.open(source)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val upright = bitmap.rotatedIfNeeded(context.exifRotation(source))
        val scaled = upright.scaleToFit(MAX_PHOTO_EDGE)

        val dir = File(context.filesDir, "receipts").apply { mkdirs() }
        val out = File(dir, "receipt_${System.currentTimeMillis()}.jpg")
        FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, it) }
        if (scaled !== upright) scaled.recycle()
        if (upright !== bitmap) bitmap.recycle()
        bitmap.recycle()
        out.absolutePath
    }.onFailure { Logger.d(TAG, "收据照片处理失败", it) }.getOrNull()

/** File / content 两种 Uri 通吃；打不开返回 null，交给上层回退。 */
private fun Context.open(uri: Uri): InputStream? =
    runCatching {
        if (uri.scheme == "file") FileInputStream(File(uri.path.orEmpty())) else contentResolver.openInputStream(uri)
    }.getOrNull()

/** 读出 EXIF 方向对应的旋转角度（0 / 90 / 180 / 270），读不到当 0。 */
private fun Context.exifRotation(uri: Uri): Int =
    runCatching {
        open(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    }.getOrDefault(0)

/** 顺时针旋转 [degrees]；角度为 0 时原样返回，避免白白复制一张大图。 */
private fun Bitmap.rotatedIfNeeded(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

/** 两段式采样率：让解码后的长边落在 [MAX_PHOTO_EDGE] 的两倍以内，留出缩放余量。 */
private fun sampleSizeOf(
    width: Int,
    height: Int,
): Int {
    var sample = 1
    while (width / (sample * 2) >= MAX_PHOTO_EDGE && height / (sample * 2) >= MAX_PHOTO_EDGE) {
        sample *= 2
    }
    return sample
}

/** 等比缩放：长边压到 [maxEdge]，已经够小则原样返回。 */
private fun Bitmap.scaleToFit(maxEdge: Int): Bitmap {
    val edge = maxOf(width, height)
    if (edge <= maxEdge) return this
    val ratio = maxEdge.toFloat() / edge
    return Bitmap.createScaledBitmap(this, (width * ratio).toInt(), (height * ratio).toInt(), true)
}

/**
 * 相机按钮弹出的中间选项（PRD v1.9.1 第 5 条）：拍照 / 相册。
 *
 * 用居中的卡片而不是底部抽屉：这是「点一个按钮后的二级选择」，
 * 从中间弹出来更贴近用户说的「跳出中间选项」，也不会被计算器挡住。
 */
@Composable
internal fun PhotoSourceDialog(
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().testTag("photo_source_dialog"),
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = "添加收据",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
                PhotoOption(
                    icon = Icons.Outlined.PhotoCamera,
                    label = "拍照",
                    tag = "photo_camera",
                    onClick = onCamera,
                )
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                PhotoOption(
                    icon = Icons.Outlined.PhotoLibrary,
                    label = "从相册选择",
                    tag = "photo_gallery",
                    onClick = onGallery,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.padding(end = 8.dp).testTag("photo_cancel"),
                    ) {
                        Text("取消")
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoOption(
    icon: ImageVector,
    label: String,
    tag: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(tag),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
