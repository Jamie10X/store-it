package com.jamie.store_it

import android.net.Uri

data class SharedStoragePhoto(
    val id: String,
    val uri: String,
    val width: Int,
    val height: Int,
    val contentUri: Uri
)
