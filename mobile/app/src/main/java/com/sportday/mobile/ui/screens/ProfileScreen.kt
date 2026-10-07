package com.sportday.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sportday.mobile.data.model.UserDTO
import com.sportday.mobile.data.repository.BiometricAvailability
import com.sportday.mobile.data.repository.rememberBiometricAuthManager
import com.sportday.mobile.data.repository.rememberRepository
import com.sportday.mobile.data.repository.rememberTokenManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onBack: () -> Unit, onLogout: () -> Unit) {
    var user by remember { mutableStateOf<UserDTO?>(null) }
    var fullName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val repository = rememberRepository()
    val tokenManager = rememberTokenManager()
    val biometrics = rememberBiometricAuthManager()
    val host = LocalContext.current as? FragmentActivity
    val availability = remember { biometrics.availability() }
    var biometricOn by remember { mutableStateOf(false) }
    var biometricBusy by remember { mutableStateOf(false) }
    var biometricMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        biometricOn = tokenManager.hasBiometricCredential()
    }

    // If the app stops while a prompt is up, the callback may never arrive; the
    // switch must not be left disabled, or the feature could not be turned off.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) biometricBusy = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /**
     * The enable step, reachable again from here. The fingerprint is confirming
     * that this sign-in may be stored; the token is sealed with a Keystore key
     * that only an accepted fingerprint can use.
     */
    fun turnOn() {
        val activity = host
        if (activity == null) {
            biometricMessage = "Fingerprint sign-in needs the app's own window. Try again from the profile screen."
            return
        }
        val cipher = try {
            biometrics.encryptCipher()
        } catch (e: Exception) {
            biometricMessage = "This phone could not create the key that would protect a saved sign-in, so nothing was stored."
            return
        }
        biometricBusy = true
        biometricMessage = null
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    biometricBusy = false
                    val unlocked = result.cryptoObject?.cipher
                    if (unlocked == null) {
                        biometricMessage = "The fingerprint was accepted but no key came back, so nothing was stored."
                        return
                    }
                    scope.launch {
                        val token = tokenManager.getToken()
                        val sealed = if (token.isNullOrBlank()) null else try {
                            biometrics.seal(unlocked, token)
                        } catch (e: Exception) {
                            null
                        }
                        if (sealed == null) {
                            biometricMessage = "There was no sign-in to save. Sign in again first."
                            return@launch
                        }
                        tokenManager.storeBiometricCredential(sealed)
                        biometricOn = true
                        biometricMessage = "Fingerprint sign-in is on. The saved sign-in is sealed with a key only your fingerprint can unlock; your password is not stored."
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    biometricBusy = false
                    biometricMessage = biometrics.describeError(errorCode)
                }

                override fun onAuthenticationFailed() {
                    // Not a matching finger: the prompt stays up, nothing to say.
                }
            }
        ).authenticate(biometrics.enablePromptInfo(), BiometricPrompt.CryptoObject(cipher))
    }

    LaunchedEffect(Unit) {
        try {
            val response = repository.getCurrentUser()
            if (response.isSuccessful && response.body() != null) {
                user = response.body()!!
                val u = response.body()!!
                fullName = u.fullName ?: ""
                email = u.email.orEmpty()
                age = u.age?.toString() ?: ""
                gender = u.gender ?: ""
            } else {
                errorMessage = "Failed to load profile"
            }
        } catch (e: Exception) {
            errorMessage = "Error: ${e.message}"
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        scope.launch {
                            tokenManager.clearAll()
                            onLogout()
                        }
                    }) {
                        Text("Logout")
                    }
                }
            )
        }
    ) { padding ->
        when {
            isLoading -> Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            user != null -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Profile header
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(text = user!!.username, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Badge(containerColor = MaterialTheme.colorScheme.primary) { Text(user!!.role, modifier = Modifier.padding(horizontal = 8.dp)) }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(value = fullName, onValueChange = { fullName = it }, label = { Text("Full Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = age, onValueChange = { age = it }, label = { Text("Age") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = gender, onValueChange = { gender = it }, label = { Text("Gender") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                    if (errorMessage != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Text(text = errorMessage!!, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }

                    if (successMessage != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Text(text = successMessage!!, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            isSaving = true
                            errorMessage = null
                            successMessage = null
                            scope.launch {
                                try {
                                    val updatedUser = user!!.copy(
                                        fullName = fullName.ifBlank { null },
                                        email = email,
                                        age = age.toIntOrNull(),
                                        gender = gender.ifBlank { null }
                                    )
                                    val response = repository.updateCurrentUser(updatedUser)
                                    if (response.isSuccessful && response.body() != null) {
                                        user = response.body()!!
                                        successMessage = "Profile updated successfully"
                                    } else {
                                        errorMessage = "Failed to update profile"
                                    }
                                } catch (e: Exception) {
                                    errorMessage = "Error: ${e.message}"
                                } finally {
                                    isSaving = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSaving
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Text("Save Changes")
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Sign-in",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Fingerprint,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Fingerprint sign-in",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = when {
                                            availability != BiometricAvailability.AVAILABLE ->
                                                biometrics.availabilityMessage(availability)
                                            biometricOn ->
                                                "On — a sign-in sealed with this phone's keystore is released by your fingerprint. Your password is not stored."
                                            else ->
                                                "Off — you sign in with your username and password."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = biometricOn,
                                    enabled = !biometricBusy && availability == BiometricAvailability.AVAILABLE,
                                    onCheckedChange = { wanted ->
                                        biometricMessage = null
                                        if (wanted) {
                                            turnOn()
                                        } else {
                                            // Off means gone: the ciphertext and the
                                            // Keystore key that guarded it both go.
                                            scope.launch {
                                                tokenManager.clearBiometricCredential()
                                                biometricOn = false
                                                biometricMessage = "Fingerprint sign-in is off and the saved sign-in was deleted from this phone."
                                            }
                                        }
                                    }
                                )
                            }
                            if (biometricMessage != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = biometricMessage!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
