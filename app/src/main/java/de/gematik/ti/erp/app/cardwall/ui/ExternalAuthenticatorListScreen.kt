package de.gematik.ti.erp.app.cardwall.ui

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import de.gematik.ti.erp.app.cardwall.ui.model.ExternalAuthenticatorListViewModel
import de.gematik.ti.erp.app.theme.PaddingDefaults

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun ExternalAuthenticatorListScreen(
    mainNavController: NavController,
    viewModel: ExternalAuthenticatorListViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    LazyColumn(modifier = Modifier
        .fillMaxWidth()
        .padding(
            all = PaddingDefaults.Medium
        )) {
        items(viewModel.externalAuthenticatorIDList) {
            Button(modifier = Modifier.padding(all = PaddingDefaults.Medium
            ),onClick = {
                viewModel.startAuthorizationWithExternal(it.authenticationID)
            }) {
                Text(text = it.name)
            }
        }
    }
    LaunchedEffect(viewModel.lastRedirectIntent) {
        if (viewModel.lastRedirectIntent.data != null)
            context.startActivity(viewModel.lastRedirectIntent)
    }
}
