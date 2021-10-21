/*
 * Copyright (c) 2021 gematik GmbH
 * 
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the Licence);
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 * 
 *     https://joinup.ec.europa.eu/software/page/eupl
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 * 
 */

package de.gematik.ti.erp.app

import android.content.SharedPreferences
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import de.gematik.ti.erp.app.core.LocalActivity
import de.gematik.ti.erp.app.core.LocalTracker
import de.gematik.ti.erp.app.core.MainContent
import de.gematik.ti.erp.app.di.ApplicationPreferences
import de.gematik.ti.erp.app.di.NavigationObservable
import de.gematik.ti.erp.app.mainscreen.ui.MainScreen
import de.gematik.ti.erp.app.tracking.Tracker
import de.gematik.ti.erp.app.userauthentication.ui.AuthenticationModeAndMethod
import de.gematik.ti.erp.app.userauthentication.ui.AuthenticationUseCase
import de.gematik.ti.erp.app.userauthentication.ui.UserAuthenticationScreen
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

const val SCREENSHOTS_ALLOWED = "SCREENSHOTS_ALLOWED"

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var auth: AuthenticationUseCase

    @Inject
    lateinit var navigationObservable: NavigationObservable

    @Inject
    lateinit var tracker: Tracker

    @Inject
    @ApplicationPreferences
    lateinit var appPrefs: SharedPreferences

    private val _nfcTag = MutableSharedFlow<Tag>()
    val nfcTagFlow: Flow<Tag>
        get() = _nfcTag

    private val authenticationModeAndMethod = MutableSharedFlow<AuthenticationModeAndMethod>()


    @OptIn(ExperimentalAnimationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val externalAuthorizationParameters = intent.data

        if (BuildConfig.DEBUG) {
            appPrefs.edit {
                putBoolean(SCREENSHOTS_ALLOWED, true)
            }
        }

        switchScreenshotMode()
        appPrefs.registerOnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == SCREENSHOTS_ALLOWED) {
                switchScreenshotMode()
            }
        }

        setContent {
            CompositionLocalProvider(
                LocalActivity provides this,
                LocalTracker provides tracker
            ) {
                MainContent { mainViewModel ->
                    val auth by authenticationModeAndMethod.collectAsState(null)
                    val navController = rememberNavController()

                    mainViewModel.externalAuthorizationUri = intent.data

                    Box(modifier = Modifier.fillMaxSize()) {
                        if (auth !is AuthenticationModeAndMethod.Authenticated) {
                            Image(
                                painterResource(R.drawable.erp_logo),
                                null,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }

                        val noDrawModifier = Modifier
                            .fillMaxSize()
                            .layout { _, _ ->
                                layout(0, 0) {}
                            }
                        Box(
                            if (auth is AuthenticationModeAndMethod.Authenticated) Modifier else noDrawModifier
                        ) {
                            MainScreen(navController, mainViewModel)
                        }

                        AnimatedVisibility(
                            visible = auth is AuthenticationModeAndMethod.AuthenticationRequired,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            UserAuthenticationScreen()
                        }
                        LaunchedEffect(auth){
                            if(auth is AuthenticationModeAndMethod.Authenticated){
                                intent.data?.let {
                                    mainViewModel.onExternAppAuthorizationResult(it)
                                }

                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        NfcAdapter.getDefaultAdapter(applicationContext)?.let {
            if (it.isEnabled) {
                it.enableReaderMode(
                    this,
                    ::onTagDiscovered,
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    Bundle()
                )
            }
        }

        lifecycleScope.launchWhenStarted {
            auth.authenticationModeAndMethod.collect {
                authenticationModeAndMethod.emit(it)
            }
        }
    }

    private fun onTagDiscovered(tag: Tag) {
        lifecycleScope.launch {
            _nfcTag.emit(tag)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onPause() {
        super.onPause()

        NfcAdapter.getDefaultAdapter(applicationContext)?.disableReaderMode(this)
    }

    private fun switchScreenshotMode() {
        // `gemSpec_eRp_FdV A_20203` default settings are not allow screenshots
        if (appPrefs.getBoolean(SCREENSHOTS_ALLOWED, false)) {
            this.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            this.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
