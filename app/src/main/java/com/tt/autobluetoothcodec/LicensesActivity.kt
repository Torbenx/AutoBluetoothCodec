package com.tt.autobluetoothcodec

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer

/**
 * Lists the libraries the app uses with their licenses. The AboutLibraries Gradle plugin collects
 * them from the dependencies at build time, tapping one shows its license text.
 */
class LicensesActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableLightEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Third-party licenses") },
                        navigationIcon = {
                            IconButton(onClick = ::finish) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    )
                },
                // Also keeps the content clear of display cutouts in landscape
                contentWindowInsets = WindowInsets.safeDrawing
            ) { innerPadding ->
                // Padding inside the list, so the entries scroll behind the navigation bar
                LibrariesContainer(Modifier.fillMaxSize(), contentPadding = innerPadding)
            }
        }
    }
}
