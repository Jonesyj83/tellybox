package com.opentelly.tv.ui.parental

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.opentelly.tv.appContainer
import kotlinx.coroutines.launch

open class ProtectedActivity : com.opentelly.tv.ui.ScaledActivity() {
    private var prompting = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                appContainer.parental.changes.collect {
                    val repo = appContainer.parental
                    if (repo.allScreens && !repo.unlocked && !prompting) {
                        prompting = true
                        window.decorView.alpha = 0f
                        onParentalLock()
                        requestPin(cancel = { finish() }) {
                            prompting = false
                            window.decorView.alpha = 1f
                            onParentalUnlock()
                        }
                    }
                }
            }
        }
    }
    protected open fun onParentalLock() = Unit
    protected open fun onParentalUnlock() = Unit
}
