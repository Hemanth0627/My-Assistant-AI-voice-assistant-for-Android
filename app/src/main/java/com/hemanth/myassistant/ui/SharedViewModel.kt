package com.hemanth.myassistant.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hemanth.myassistant.MyAssistantApp

/** The single AssistantViewModel shared by the full app and the compact panel. */
@Composable
fun sharedAssistantViewModel(): AssistantViewModel {
    val app = LocalContext.current.applicationContext as MyAssistantApp
    return viewModel(
        viewModelStoreOwner = app,
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app)
    )
}