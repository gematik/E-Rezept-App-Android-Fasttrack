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

package de.gematik.ti.erp.app.idp.usecase

import de.gematik.ti.erp.app.api.ApiCallException
import de.gematik.ti.erp.app.idp.repository.IdpRepository
import de.gematik.ti.erp.app.idp.repository.SingleSignOnToken
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.IOException
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exception thrown by [IdpUseCase.loadAccessToken].
 */
class RefreshFlowException : IOException {
    /**
     * Is true if the sso token is not valid anymore and the user is required to authenticate again.
     */
    val userActionRequired: Boolean
    val tokenScope: SingleSignOnToken.Scope?

    constructor(userActionRequired: Boolean, tokenScope: SingleSignOnToken.Scope?, cause: Throwable) : super(cause) {
        this.userActionRequired = userActionRequired
        this.tokenScope = tokenScope
    }

    constructor(userActionRequired: Boolean, tokenScope: SingleSignOnToken.Scope?, message: String) : super(message) {
        this.userActionRequired = userActionRequired
        this.tokenScope = tokenScope
    }
}

class AltAuthenticationCryptoException(cause: Throwable) : IllegalStateException(cause)

@Singleton
class IdpUseCase @Inject constructor(
    private val repository: IdpRepository,
    private val basicUseCase: IdpBasicUseCase,
    private val altAuthUseCase: IdpAlternateAuthenticationUseCase,
) {
    private val lock = Mutex()

    /**
     * If no bearer token is set or [refresh] is true, this will trigger [IdpBasicUseCase.refreshAccessTokenWithSsoFlow].
     */
    suspend fun loadAccessToken(refresh: Boolean = false, profileName: String): String =
        lock.withLock {
            val ssoToken = repository.getSingleSignOnToken(profileName)
            if (ssoToken == null) {
                repository.invalidateDecryptedAccessToken()
                throw RefreshFlowException(
                    true,
                    repository.getSingleSignOnTokenScope(profileName),
                    "SSO token not set!"
                )
            }
            val accToken = repository.decryptedAccessToken

            if (refresh || accToken == null) {
                repository.invalidateDecryptedAccessToken()

                val initialData = basicUseCase.initializeConfigurationAndKeys()
                try {
                    val refreshData = basicUseCase.refreshAccessTokenWithSsoFlow(
                        initialData,
                        scope = IdpScope.Default,
                        ssoToken = ssoToken.token
                    )
                    refreshData.accessToken
                } catch (e: Exception) {
                    Timber.e(e, "Couldn't refresh access token")
                    (e as? ApiCallException)?.also {
                        when (it.response.code()) {
                            // 400 returned by redirect call if sso token is not valid anymore
                            400, 401, 403 -> {
                                repository.invalidateSingleSignOnTokenRetainingScope()
                                throw RefreshFlowException(true, ssoToken.scope, e)
                            }
                        }
                    }
                    throw RefreshFlowException(false, null, e)
                }
            } else {
                accToken
            }.also {
                repository.decryptedAccessToken = it
            }
        }

    /**
     * Initial flow fetching the sso & access token requiring the health card to sign the challenge.
     */
    suspend fun authenticationFlowWithHealthCard(
        healthCardCertificate: suspend () -> ByteArray,
        sign: suspend (hash: ByteArray) -> ByteArray
    ) = lock.withLock {
        val initialData = basicUseCase.initializeConfigurationAndKeys()
        val challengeData = basicUseCase.challengeFlow(initialData, scope = IdpScope.Default)
        val basicData = basicUseCase.basicAuthFlow(
            initialData = initialData,
            challengeData = challengeData,
            healthCardCertificate = healthCardCertificate(),
            sign = sign
        )
        repository.setSingleSignOnToken(SingleSignOnToken(basicData.ssoToken))
        repository.decryptedAccessToken = basicData.accessToken
    }

    /**
     * Pairing flow fetching the sso & access token requiring the health card and generated key material.
     */
    suspend fun alternatePairingFlowWithSecureElement(
        publicKeyOfSecureElementEntry: PublicKey,
        aliasOfSecureElementEntry: ByteArray,
        healthCardCertificate: suspend () -> ByteArray,
        signWithHealthCard: suspend (hash: ByteArray) -> ByteArray
    ) = lock.withLock {
        val initialData = basicUseCase.initializeConfigurationAndKeys()
        val challengeData = basicUseCase.challengeFlow(initialData, scope = IdpScope.BiometricPairing)
        val healthCardCert = healthCardCertificate()
        val basicData = basicUseCase.basicAuthFlow(
            initialData = initialData,
            challengeData = challengeData,
            healthCardCertificate = healthCardCert,
            sign = signWithHealthCard
        )

        altAuthUseCase.registerDeviceWithHealthCard(
            initialData = initialData,
            accessToken = basicData.accessToken,
            healthCardCertificate = healthCardCert,
            publicKeyOfSecureElementEntry = publicKeyOfSecureElementEntry,
            aliasOfSecureElementEntry = aliasOfSecureElementEntry,
            signWithHealthCard = signWithHealthCard,
        )

        // FIXME this should be handled differently as soon as we support multiple users
        repository.setHealthCardCertificate(healthCardCert)
        repository.setPairingScope()
        repository.setAliasOfSecureElementEntry(aliasOfSecureElementEntry)
    }

    /**
     * Actual authentication with secure element key material. Just like the [authenticationFlowWithHealthCard] it
     * sets the sso & access token within the repository.
     */
    suspend fun alternateAuthenticationFlowWithSecureElement(profileName: String) = lock.withLock {
        val healthCardCertificate =
            requireNotNull(repository.getHealthCardCertificate(profileName)) { "Health card certificate not set! Maybe you forgot to call alternatePairingFlowWithSecureElement before." }
        val aliasOfSecureElementEntry =
            requireNotNull(repository.getAliasOfSecureElementEntry(profileName)) { "Alias of secure element entry not set! Maybe you forgot to call alternatePairingFlowWithSecureElement before." }

        lateinit var privateKeyOfSecureElementEntry: PrivateKey
        lateinit var signatureObjectOfSecureElementEntry: Signature

        try {
            privateKeyOfSecureElementEntry = (
                KeyStore.getInstance("AndroidKeyStore")
                    .apply { load(null) }
                    .getEntry(aliasOfSecureElementEntry.decodeToString(), null) as KeyStore.PrivateKeyEntry
                ).privateKey
            signatureObjectOfSecureElementEntry =
                Signature.getInstance("SHA256withECDSA", "AndroidKeyStoreBCWorkaround")
        } catch (e: Exception) {
            // the system might have removed the key during biometric reenrollment
            // therefore their is no choice but to delete everything
            repository.invalidate(profileName)
            throw AltAuthenticationCryptoException(e)
        }

        val initialData = basicUseCase.initializeConfigurationAndKeys()
        val challengeData = basicUseCase.challengeFlow(initialData, scope = IdpScope.Default)

        val authData = altAuthUseCase.authenticateWithSecureElement(
            initialData = initialData,
            challenge = challengeData.challenge,
            healthCardCertificate = healthCardCertificate,
            authenticationMethod = IdpAlternateAuthenticationUseCase.AuthenticationMethod.Strong,
            aliasOfSecureElementEntry = aliasOfSecureElementEntry,
            privateKeyOfSecureElementEntry = privateKeyOfSecureElementEntry,
            signatureObjectOfSecureElementEntry = signatureObjectOfSecureElementEntry,
        )

        repository.setSingleSignOnToken(
            SingleSignOnToken(
                authData.ssoToken,
                scope = SingleSignOnToken.Scope.AlternateAuthentication
            )
        )
        repository.decryptedAccessToken = authData.accessToken
    }

    fun getSavedCardAccessNumber() = repository.cardAccessNumber
}
