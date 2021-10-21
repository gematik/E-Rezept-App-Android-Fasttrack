package de.gematik.ti.erp.app.cardwall.ui.model

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.gematik.ti.erp.app.core.BaseViewModel
import de.gematik.ti.erp.app.idp.api.models.AuthenticationID
import de.gematik.ti.erp.app.idp.usecase.IdpUseCase
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ExternalAuthenticatorListViewModel @Inject constructor(
    private val idpUseCase: IdpUseCase
) : BaseViewModel() {
    var externalAuthenticatorIDList by mutableStateOf(emptyList<AuthenticationID>())

    var lastRedirectIntent by mutableStateOf(Intent())

    init {
        viewModelScope.launch {
            externalAuthenticatorIDList =
                idpUseCase.downloadDiscoveryDocumentAndGetExternAuthenticatorIDs()

//            val redirectUri = idpUseCase.getUniversalLinkForExternalAuthorization(externalAuthenticatorIDList.first().authenticationID)
//            lastRedirectIntent = Intent(Intent.ACTION_VIEW, redirectUri)

        }
    }

    fun startAuthorizationWithExternal(id:String){
        viewModelScope.launch {
            val redirectUri = idpUseCase.getUniversalLinkForExternalAuthorization(id)
            lastRedirectIntent = Intent(Intent.ACTION_VIEW, redirectUri)
        }
    }
}
