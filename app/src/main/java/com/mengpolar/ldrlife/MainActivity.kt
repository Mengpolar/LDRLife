package com.mengpolar.ldrlife

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mengpolar.ldrlife.ui.AppRoot
import com.mengpolar.ldrlife.ui.theme.LDRLifeTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = (application as LdrApp).session
        setContent {
            LDRLifeTheme {
                AppRoot(session)
            }
        }
    }
}
