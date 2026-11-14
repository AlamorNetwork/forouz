package com.v2ray.ang.ui.lab

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.v2ray.ang.ui.base.BaseComponentActivity

class LabActivity : BaseComponentActivity() {
    private val model: LabViewModel by viewModels {
        viewModelFactory { initializer { LabViewModel(createSavedStateHandle(), LabRepository(applicationContext)) } }
    }

    @Composable override fun ScreenContent() { LabScreen(model, ::finish) }
}
