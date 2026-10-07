package com.hemanth.myassistant

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * Created once when the app starts. Holds ONE ViewModelStore for the whole app,
 * so the full chat screen and the compact panel share the same conversation.
 */
class MyAssistantApp : Application(), ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}