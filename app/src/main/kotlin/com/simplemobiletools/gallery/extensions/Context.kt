package com.simplemobiletools.gallery.extensions

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Point
import android.media.AudioManager
import android.os.Build
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.OTG_PATH
import com.simplemobiletools.gallery.R
import com.simplemobiletools.gallery.activities.SettingsActivity
import com.simplemobiletools.gallery.asynctasks.GetMediaAsynctask
import com.simplemobiletools.gallery.databases.GalleryDataBase
import com.simplemobiletools.gallery.helpers.*
import com.simplemobiletools.gallery.interfaces.DirectoryDao
import com.simplemobiletools.gallery.models.Directory
import com.simplemobiletools.gallery.models.Medium
import com.simplemobiletools.gallery.views.MySquareImageView
import pl.droidsonroids.gif.GifDrawable
import java.io.File

val Context.portrait get() = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
val Context.audioManager get() = getSystemService(Context.AUDIO_SERVICE) as AudioManager
val Context.windowManager: WindowManager get() = getSystemService(Context.WINDOW_SERVICE) as WindowManager
val Context.navigationBarRight: Boolean get() = usableScreenSize.x < realScreenSize.x
val Context.navigationBarBottom: Boolean get() = usableScreenSize.y < realScreenSize.y
val Context.navigationBarHeight: Int get() = if (navigationBarBottom) navigationBarSize.y else 0
val Context.navigationBarWidth: Int get() = if (navigationBarRight) navigationBarSize.x else 0

internal val Context.navigationBarSize: Point
    get() = when {
        navigationBarRight -> Point(realScreenSize.x - usableScreenSize.x, usableScreenSize.y)
        navigationBarBottom -> Point(usableScreenSize.x, realScreenSize.y - usableScreenSize.y)
        else -> Point()
    }

val Context.usableScreenSize: Point
    get() {
        val size = Point()
        windowManager.defaultDisplay.getSize(size)
        return size
    }

val Context.realScreenSize: Point
    get() {
        val size = Point()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
            windowManager.defaultDisplay.getRealSize(size)
        return size
    }

fun Context.getHumanizedFilename(path: String): String {
    val humanized = humanizePath(path)
    return humanized.substring(humanized.lastIndexOf("/") + 1)
}

fun Context.launchSettings() {
    startActivity(Intent(applicationContext, SettingsActivity::class.java))
}

val Context.config: Config get() = Config.newInstance(applicationContext)

val Context.galleryDB: GalleryDataBase get() = GalleryDataBase.getInstance(applicationContext)

fun Context.movePinnedDirectoriesToFront(dirs: ArrayList<Directory>): ArrayList<Directory> {
    val foundFolders = ArrayList<Directory>()
    val pinnedFolders = config.pinnedFolders

    dirs.forEach {
        if (pinnedFolders.contains(it.path)) {
            foundFolders.add(it)
        }
    }

    dirs.removeAll(foundFolders)
    dirs.addAll(0, foundFolders)
    if (config.tempFolderPath.isNotEmpty()) {
        val newFolder = dirs.firstOrNull { it.path == config.tempFolderPath }
        if (newFolder != null) {
            dirs.remove(newFolder)
            dirs.add(0, newFolder)
        }
    }
    return dirs
}

@Suppress("UNCHECKED_CAST")
fun Context.getSortedDirectories(source: ArrayList<Directory>): ArrayList<Directory> {
    Directory.sorting = config.directorySorting
    val dirs = source.clone() as ArrayList<Directory>
    dirs.sort()
    return movePinnedDirectoriesToFront(dirs)
}

fun Context.getNoMediaFolders(callback: (folders: ArrayList<String>) -> Unit) {
    Thread {
        val folders = ArrayList<String>()

        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Files.FileColumns.DATA)
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? AND ${MediaStore.Files.FileColumns.TITLE} LIKE ?"
        val selectionArgs = arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_NONE.toString(), "%$NOMEDIA%")
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            if (cursor?.moveToFirst() == true) {
                do {
                    val path = cursor.getStringValue(MediaStore.Files.FileColumns.DATA) ?: continue
                    val noMediaFile = File(path)
                    if (noMediaFile.exists() && noMediaFile.name == NOMEDIA) {
                        folders.add("${noMediaFile.parent}/")
                    }
                } while (cursor.moveToNext())
            }
        } finally {
            cursor?.close()
        }

        callback(folders)
    }.start()
}

fun Context.rescanFolderMedia(path: String) {
    Thread {
        rescanFolderMediaSync(path)
    }.start()
}

fun Context.rescanFolderMediaSync(path: String) {
    getCachedMedia(path) {
        val cached = it
        GetMediaAsynctask(applicationContext, path, false, false, false) {
            Thread {
                val newMedia = it
                val mediumDao = galleryDB.MediumDao()
                mediumDao.insertAll(newMedia)

                cached.forEach {
                    if (!newMedia.contains(it)) {
                        mediumDao.deleteMediumPath(it.path)
                    }
                }
            }.start()
        }.execute()
    }
}

fun Context.storeDirectoryItems(items: ArrayList<Directory>) {
    Thread {
        galleryDB.DirectoryDao().insertAll(items)
    }.start()
}

fun Context.checkAppendingHidden(path: String, hidden: String, includedFolders: MutableSet<String>): String {
    val dirName = when (path) {
        internalStoragePath -> getString(R.string.internal)
        sdCardPath -> getString(R.string.sd_card)
        OTG_PATH -> getString(R.string.otg)
        else -> {
            if (path.startsWith(OTG_PATH)) {
                path.trimEnd('/').substringAfterLast('/')
            } else {
                path.getFilenameFromPath()
            }
        }
    }

    return if (File(path).doesThisOrParentHaveNoMedia() && !path.isThisOrParentIncluded(includedFolders)) {
        "$dirName $hidden"
    } else {
        dirName
    }
}

fun Context.loadImage(type: Int, path: String, target: MySquareImageView, horizontalScroll: Boolean, animateGifs: Boolean, cropThumbnails: Boolean) {
    target.isHorizontalScrolling = horizontalScroll
    if (type == TYPE_IMAGES || type == TYPE_VIDEOS) {
        if (type == TYPE_IMAGES && path.isPng()) {
            loadPng(path, target, cropThumbnails)
        } else {
            loadJpg(path, target, cropThumbnails)
        }
    } else if (type == TYPE_GIFS) {
        try {
            val gifDrawable = GifDrawable(path)
            target.setImageDrawable(gifDrawable)
            if (animateGifs) {
                gifDrawable.start()
            } else {
                gifDrawable.stop()
            }

            target.scaleType = if (cropThumbnails) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        } catch (e: Exception) {
            loadJpg(path, target, cropThumbnails)
        } catch (e: OutOfMemoryError) {
            loadJpg(path, target, cropThumbnails)
        }
    }
}

fun Context.addTempFolderIfNeeded(dirs: ArrayList<Directory>): ArrayList<Directory> {
    val directories = ArrayList<Directory>()
    val tempFolderPath = config.tempFolderPath
    if (tempFolderPath.isNotEmpty()) {
        val newFolder = Directory(null, tempFolderPath, "", tempFolderPath.getFilenameFromPath(), 0, 0, 0, 0L, getPathLocation(tempFolderPath), 0)
        directories.add(newFolder)
    }
    directories.addAll(dirs)
    return directories
}

fun Context.getPathLocation(path: String): Int {
    return when {
        isPathOnSD(path) -> LOCATION_SD
        path.startsWith(OTG_PATH) -> LOCATION_OTG
        else -> LOCAITON_INTERNAL
    }
}

fun Context.loadPng(path: String, target: MySquareImageView, cropThumbnails: Boolean) {
    val options = RequestOptions()
            .signature(path.getFileSignature())
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .format(DecodeFormat.PREFER_ARGB_8888)

    val builder = Glide.with(applicationContext)
            .asBitmap()
            .load(path)

    if (cropThumbnails) options.centerCrop() else options.fitCenter()
    builder.apply(options).into(target)
}

fun Context.loadJpg(path: String, target: MySquareImageView, cropThumbnails: Boolean) {
    val options = RequestOptions()
            .signature(path.getFileSignature())
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)

    val builder = Glide.with(applicationContext)
            .load(path)

    if (cropThumbnails) options.centerCrop() else options.fitCenter()
    builder.apply(options).transition(DrawableTransitionOptions.withCrossFade()).into(target)
}

fun Context.getCachedDirectories(getVideosOnly: Boolean = false, getImagesOnly: Boolean = false, callback: (ArrayList<Directory>) -> Unit) {
    Thread {
        val directoryDao = galleryDB.DirectoryDao()
        val directories = directoryDao.getAll() as ArrayList<Directory>
        val shouldShowHidden = config.shouldShowHidden
        val excludedPaths = config.excludedFolders
        val includedPaths = config.includedFolders
        var filteredDirectories = directories.filter { it.path.shouldFolderBeVisible(excludedPaths, includedPaths, shouldShowHidden) } as ArrayList<Directory>
        val filterMedia = config.filterMedia

        filteredDirectories = (when {
            getVideosOnly -> filteredDirectories.filter { it.types and TYPE_VIDEOS != 0 }
            getImagesOnly -> filteredDirectories.filter { it.types and TYPE_IMAGES != 0 }
            else -> filteredDirectories.filter {
                (filterMedia and TYPE_IMAGES != 0 && it.types and TYPE_IMAGES != 0) ||
                        (filterMedia and TYPE_VIDEOS != 0 && it.types and TYPE_VIDEOS != 0) ||
                        (filterMedia and TYPE_GIFS != 0 && it.types and TYPE_GIFS != 0)
            }
        }) as ArrayList<Directory>

        callback(filteredDirectories)

        removeInvalidDBDirectories(directories, directoryDao)
    }.start()
}

fun Context.getCachedMedia(path: String, getVideosOnly: Boolean = false, getImagesOnly: Boolean = false, callback: (ArrayList<Medium>) -> Unit) {
    Thread {
        val mediumDao = galleryDB.MediumDao()
        val media = (if (path == "/") mediumDao.getAll() else mediumDao.getMediaFromPath(path)) as ArrayList<Medium>
        val shouldShowHidden = config.shouldShowHidden
        var filteredMedia = media
        if (!shouldShowHidden) {
            filteredMedia = media.filter { !it.name.startsWith('.') } as ArrayList<Medium>
        }

        val filterMedia = config.filterMedia
        filteredMedia = (when {
            getVideosOnly -> filteredMedia.filter { it.type == TYPE_VIDEOS }
            getImagesOnly -> filteredMedia.filter { it.type == TYPE_IMAGES }
            else -> filteredMedia.filter {
                (filterMedia and TYPE_IMAGES != 0 && it.type == TYPE_IMAGES) ||
                        (filterMedia and TYPE_VIDEOS != 0 && it.type == TYPE_VIDEOS) ||
                        (filterMedia and TYPE_GIFS != 0 && it.type == TYPE_GIFS)
            }
        }) as ArrayList<Medium>

        callback(filteredMedia)
        media.filter { !getDoesFilePathExist(it.path) }.forEach {
            mediumDao.deleteMediumPath(it.path)
        }
    }.start()
}

fun Context.removeInvalidDBDirectories(dirs: ArrayList<Directory>? = null, directoryDao: DirectoryDao = galleryDB.DirectoryDao()) {
    val dirsToCheck = dirs ?: directoryDao.getAll()
    dirsToCheck.filter { !getDoesFilePathExist(it.path) }.forEach {
        directoryDao.deleteDirPath(it.path)
    }
}

fun Context.updateDBMediaPath(oldPath: String, newPath: String) {
    val newFilename = newPath.getFilenameFromPath()
    val newParentPath = newPath.getParentPath()
    galleryDB.MediumDao().updateMedium(oldPath, newParentPath, newFilename, newPath)
}

fun Context.updateDBDirectory(directory: Directory) {
    galleryDB.DirectoryDao().updateDirectory(directory.path, directory.tmb, directory.mediaCnt, directory.modified, directory.taken, directory.size, directory.types)
}
