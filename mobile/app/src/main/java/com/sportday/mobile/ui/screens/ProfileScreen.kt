package com.sportday.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sportday.mobile.data.model.UserDTO
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

    LaunchedEffect(Unit) {
        try {
            val response = repository.getCurrentUser()
            if (response.isSuccessful && response.body() != null) {
                user = response.body()!!
                val u = response.body()!!
                fullName = u.fullName ?: ""
                email = u.email
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
                }
            }
        }
    }
}
