package com.jamie.store_it

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.jamie.store_it.databinding.ActivityMainBinding
import com.jamie.store_it.internalstorage.InternalStoragePhoto
import com.jamie.store_it.sharedphoto.SharedPhotoAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var internalStoragePhotoAdapter: InternalStoragePhotoAdapter
    private lateinit var externalStoragePhotoAdapter: SharedPhotoAdapter

    private var readPermissionGranted = false
    private var writePermissionGranted = false
    private lateinit var permissionsLauncher: ActivityResultLauncher<Array<String>>


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        internalStoragePhotoAdapter = InternalStoragePhotoAdapter {

            val isDeletionSuccessful = deletePhotoFromInternalStorage(it.name)
            if (isDeletionSuccessful) {
                loadPhotosFromInternalStorageIntoRecyclerView()
                Toast.makeText(this, "Photo successfully deleted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to delete photo", Toast.LENGTH_SHORT).show()
            }
        }

        externalStoragePhotoAdapter = SharedPhotoAdapter {

        }

        permissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            readPermissionGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: readPermissionGranted
            writePermissionGranted = permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: writePermissionGranted
        }

        updateOrRequestPermission()

        val takePhoto =
            registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
                if (bitmap != null) {
                    val isPrivate = binding.switchPrivate.isChecked
                    val isSavedSuccessfully = when {
                        isPrivate -> savePhotoToInternalStorage(UUID.randomUUID().toString(), bitmap)
                        writePermissionGranted -> savePhotoToExternalStorage(UUID.randomUUID().toString(), bitmap)
                        else -> false
                    }

                    if (isPrivate) {
                        loadPhotosFromInternalStorageIntoRecyclerView()

                    }
                    if (isSavedSuccessfully) {
                        Toast.makeText(this, "Photo saved", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Failed to save photo", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "No photo captured", Toast.LENGTH_SHORT).show()
                }
            }

        binding.btnTakePhoto.setOnClickListener {
            takePhoto.launch(null) // ✅ Pass `null` explicitly
        }

        setupInternalStorageRecyclerView()
        loadPhotosFromInternalStorageIntoRecyclerView()

    }

    private fun updateOrRequestPermission() {
        val hasReadPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

        val hasWritePermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

        val minSdk29 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        readPermissionGranted = hasReadPermission
        writePermissionGranted = writePermissionGranted || minSdk29

        val permissionsToRequest = mutableListOf<String>()
        if (!writePermissionGranted) {
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (!readPermissionGranted) {
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (permissionsToRequest.isNotEmpty()) {
            permissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun savePhotoToExternalStorage(displayName: String, bmp: Bitmap): Boolean {
        val imageCollection = sdk29AndUp {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg") // Correct MIME type
            put(MediaStore.Images.Media.WIDTH, bmp.width)
            put(MediaStore.Images.Media.HEIGHT, bmp.height)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StoreIt") // Saves in Gallery under "StoreIt" folder

            // Required for Android 10+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1) // Set as pending
            }
        }

        return try {
            val uri = contentResolver.insert(imageCollection, contentValues)
                ?: throw IOException("Failed to create new MediaStore record.")

            contentResolver.openOutputStream(uri).use { outputStream ->
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, outputStream!!)) {
                    throw IOException("Failed to save bitmap.")
                }
            }

            // Make the image visible to the gallery (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0) // Mark as complete
                contentResolver.update(uri, contentValues, null, null)
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }


    // Internal Storage Setup Below

    private fun setupInternalStorageRecyclerView() = binding.rvPrivatePhotos.apply {
        adapter = internalStoragePhotoAdapter
        layoutManager = GridLayoutManager(context, 3) // Better performance
    }

    private fun loadPhotosFromInternalStorageIntoRecyclerView() {
        lifecycleScope.launch {
            val photos = loadPhotosFromInternalStorage()
            internalStoragePhotoAdapter.submitList(photos)
        }
    }

    private fun deletePhotoFromInternalStorage(filename: String): Boolean {
        return runCatching {
            File(filesDir, filename).delete()
        }.getOrElse { e ->
            e.printStackTrace()
            false
        }
    }


    private suspend fun loadPhotosFromInternalStorage(): List<InternalStoragePhoto> {
        return withContext(Dispatchers.IO) {
            val files = filesDir.listFiles()
            files?.filter { it.canRead() && it.isFile && it.name.endsWith(".jpg") }
                ?.mapNotNull { file ->
                    val bytes = file.readBytes()
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    bmp?.let { InternalStoragePhoto(file.name, it) } // Avoid adding null bitmaps
                } ?: emptyList()
        }
    }

    private fun savePhotoToInternalStorage(filename: String, bmp: Bitmap): Boolean {
        return runCatching {
            openFileOutput("$filename.jpg", MODE_PRIVATE).use { stream ->
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                    throw IOException("Couldn't save bitmap")
                }
            }
            true
        }.getOrElse { e ->
            e.printStackTrace()
            false
        }
    }
}