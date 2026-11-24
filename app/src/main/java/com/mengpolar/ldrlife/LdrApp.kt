package com.mengpolar.ldrlife

import android.app.Application
import com.mengpolar.ldrlife.session.Session

class LdrApp : Application() {

    lateinit var session: Session
        private set

    override fun onCreate() {
        super.onCreate()
        session = Session(this, getSharedPreferences("ldr", MODE_PRIVATE))
        session.bootstrap()
    }
}
