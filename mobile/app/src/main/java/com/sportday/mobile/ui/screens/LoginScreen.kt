@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.sportday.mobile.ui.screens

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sportday.mobile.data.repository.BiometricAvailability
import com.sportday.mobile.data.repository.BiometricIdentity
import com.sportday.mobile.data.repository.CipherOutcome
import com.sportday.mobile.data.repository.rememberBiometricAuthManager
import com.sportday.mobile.data.repository.rememberRepository
import com.sportday.mobile.data.repository.rememberTokenManager
import kotlinx.coroutines.launch
import javax.crypto.Cipher

/**
 * Signing in.
 *
 * The password form is the one real door and it is always here — a fingerprint
 * cannot authenticate anyone to the server, it can only release a JWT that this
 * phone already holds. So: sign in with the password once, accept the offer to
 * save the sign-in (never forced), and from then on the launch gate offers a
 * fingerprint that unseals it. Cancelling, failing, locking out or losing the
 * key all land back on the password form with their own sentence.
 */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onNavigateToRegister: () -> Unit,
    onNavigateToServerConfig: () -> Unit = {}
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var biometricMessage by remember { mutableStateOf<String?>(null) }
    var storedIdentity by remember { mutableStateOf<BiometricIdentity?>(null) }
    var pendingEnableToken by remember { mutableStateOf<String?>(null) }
    var offerEnrolment by remember { mutableStateOf(false) }
    var isPrompting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val repository = rememberRepository()
    val tokenManager = rememberTokenManager()
    val biometrics = rememberBiometricAuthManager()
    val host = LocalContext.current as? FragmentActivity

    /**
     * Shows the system prompt for one cipher. [sealToken] non-null means this is
     * the enable step — the fingerprint is confirming that the sign-in may be
     * stored; null means the credential already exists and is being released.
     */
    fun askForFingerprint(cipher: Cipher, sealToken: String?) {
        val activity = host
        if (activity == null) {
            biometricMessage = "Fingerprint sign-in needs the app's own window. Sign in with your password."
            return
        }
        isPrompting = true
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    isPrompting = false
                    val unlocked = result.cryptoObject?.cipher
                    if (unlocked == null) {
                        biometricMessage = "The fingerprint was accepted but no key came back, so nothing was stored. Sign in with your password."
                        return
                    }
                    scope.launch {
                        if (sealToken != null) {
                            val sealed = try {
                                biometrics.seal(unlocked, sealToken)
                            } catch (e: Exception) {
                                null
                            }
                            if (sealed == null) {
                                biometricMessage = "This phone could not save the sign-in. It will ask for your password next time."
                                onLoginSuccess()
                                return@launch
                            }
                            tokenManager.storeBiometricCredential(sealed)
                            onLoginSuccess()
                        } else {
                            val sealed = tokenManager.getBiometricSealedToken()
                            val identity = storedIdentity ?: tokenManager.getBiometricIdentity()
                            val token = if (sealed == null) null else biometrics.open(unlocked, sealed)
                            if (token.isNullOrBlank() || identity == null) {
                                tokenManager.clearBiometricCredential()
                                storedIdentity = null
                                biometricMessage = "The saved sign-in could not be opened and has been deleted. Sign in with your password."
                            } else {
                                tokenManager.restoreBiometricSession(token, identity)
                                onLoginSuccess()
                            }
                        }
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    isPrompting = false
                    biometricMessage = biometrics.describeError(errorCode)
                }

                override fun onAuthenticationFailed() {
                    // A finger that did not match: the prompt stays up and the
                    // student can try again, so there is nothing to say yet.
                }
            }
        )
        prompt.authenticate(
            if (sealToken != null) biometrics.enablePromptInfo() else biometrics.unlockPromptInfo(),
            BiometricPrompt.CryptoObject(cipher)
        )
    }

    /** The launch gate, and the retry button: release the stored credential. */
    val unlockWithFingerprint: () -> Unit = {
        scope.launch {
            // Asking again is always allowed, and it clears the "waiting" hint, so
            // a prompt that went away without a callback can never strand the screen.
            isPrompting = false
            biometricMessage = null
            offerEnrolment = false
            val availability = biometrics.availability()
            if (availability != BiometricAvailability.AVAILABLE) {
                biometricMessage = biometrics.availabilityMessage(availability)
                if (availability == BiometricAvailability.NOT_ENROLLED) {
                    // Every finger was removed, which destroyed the key with them.
                    offerEnrolment = true
                    tokenManager.clearBiometricCredential()
                    storedIdentity = null
                }
                return@launch
            }
            val sealed = tokenManager.getBiometricSealedToken()
            if (sealed == null) {
                biometricMessage = "There is no saved sign-in on this phone. Sign in with your password."
                return@launch
            }
            when (val outcome = biometrics.decryptCipher(sealed.iv)) {
                is CipherOutcome.Ready -> askForFingerprint(outcome.cipher, sealToken = null)
                is CipherOutcome.Unusable -> {
                    // "A finger was enrolled after this was stored" arrives here:
                    // the Keystore destroyed the key, so the blob is dead. Delete
                    // it and say so, rather than failing silently every launch.
                    tokenManager.clearBiometricCredential()
                    storedIdentity = null
                    biometricMessage = if (outcome.invalidated) {
                        "Your fingerprints changed since this sign-in was saved, so Android destroyed its key and the saved sign-in was deleted. Sign in with your password — you can turn fingerprint sign-in back on afterwards."
                    } else {
                        "The saved sign-in is no longer usable on this phone and has been deleted. Sign in with your password."
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val identity = tokenManager.getBiometricIdentity()
        if (identity != null) {
            storedIdentity = identity
            unlockWithFingerprint()
        }
    }

    // A prompt cannot outlive the window: if the app stops (the screen went off,
    // or the student left) the callback may never arrive, so stop claiming to be
    // waiting for a fingerprint. The password form is never blocked by this.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) isPrompting = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("SportDay Login") },
                actions = {
                    IconButton(onClick = onNavigateToServerConfig) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Server Configuration"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "SportDay",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Sports Event Management",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            val identity = storedIdentity
            if (identity != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (identity.fullName.isBlank()) {
                                "Welcome back, ${identity.username}"
                            } else {
                                "Welcome back, ${identity.fullName}"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "This phone has a saved sign-in that only your fingerprint can open. Your password was never stored.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = unlockWithFingerprint,
                            enabled = !isPrompting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isPrompting) "Waiting for fingerprint…" else "Use fingerprint")
                        }
                    }
                }
            }

            if (biometricMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = biometricMessage!!,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        if (offerEnrolment) {
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(onClick = {
                                val activity = host
                                if (activity == null) {
                                    biometricMessage = "Settings cannot be opened from here. Add a fingerprint in the phone's Settings app."
                                } else {
                                    try {
                                        activity.startActivity(biometrics.enrolIntent())
                                    } catch (e: Exception) {
                                        biometricMessage = "This phone has no screen for adding a fingerprint. Add one in Settings, or ask an administrator."
                                    }
                                }
                            }) {
                                Text("Open fingerprint settings")
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        text = errorMessage!!,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    if (username.isBlank() || password.isBlank()) {
                        errorMessage = "Please enter username and password"
                        return@Button
                    }
                    isLoading = true
                    errorMessage = null
                    scope.launch {
                        try {
                            val response = repository.login(username, password)
                            if (response.isSuccessful && response.body() != null) {
                                val auth = response.body()!!
                                tokenManager.saveAuth(auth)
                                // Offered, never forced. If the phone cannot do
                                // strong biometrics, or the student declines, the
                                // password path continues exactly as before.
                                if (biometrics.availability() == BiometricAvailability.AVAILABLE) {
                                    pendingEnableToken = auth.token
                                } else {
                                    onLoginSuccess()
                                }
                            } else {
                                errorMessage = "Invalid username or password"
                            }
                        } catch (e: Exception) {
                            errorMessage = "Error: ${e.message}"
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Login")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(onClick = onNavigateToRegister) {
                Text("Don't have an account? Register")
            }
        }
    }

    val tokenToSeal = pendingEnableToken
    if (tokenToSeal != null) {
        AlertDialog(
            onDismissRequest = {
                pendingEnableToken = null
                onLoginSuccess()
            },
            title = { Text("Turn on fingerprint sign-in?") },
            text = {
                Text(
                    "You are signed in. Next time, your fingerprint can unlock this sign-in instead of " +
                        "typing your password. The sign-in is kept in this phone's encrypted keystore, your " +
                        "password is not stored, and you can turn it off again in Profile."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingEnableToken = null
                    scope.launch {
                        val cipher = try {
                            biometrics.encryptCipher()
                        } catch (e: Exception) {
                            // No key, no credential: the session itself is fine,
                            // so let the student in and leave the toggle off.
                            onLoginSuccess()
                            return@launch
                        }
                        askForFingerprint(cipher, sealToken = tokenToSeal)
                    }
                }) {
                    Text("Turn on")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingEnableToken = null
                    onLoginSuccess()
                }) {
                    Text("Not now")
                }
            }
        )
    }
}
