package com.storemanager.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.storemanager.core.data.AuthenticatedSession
import com.storemanager.core.data.AuthenticationService
import com.storemanager.core.data.StoreContextResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LoginScreen(
    authenticationService: AuthenticationService,
    storeContext: StoreContextResolution.Ready,
    onAuthenticated: (AuthenticatedSession) -> Unit,
    modifier: Modifier = Modifier
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isAuthenticating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(modifier = Modifier.width(480.dp).fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "مرحباً بعودتك",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "${storeContext.storeName} — ${storeContext.tenantName}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("اسم المستخدم") },
                            enabled = !isAuthenticating,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("كلمة المرور") },
                            enabled = !isAuthenticating,
                            singleLine = true,
                            visualTransformation = if (passwordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                TextButton(
                                    onClick = { passwordVisible = !passwordVisible },
                                    enabled = !isAuthenticating
                                ) {
                                    Text(if (passwordVisible) "إخفاء" else "إظهار")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        errorMessage?.let { message ->
                            Text(
                                text = message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Button(
                            onClick = {
                                if (username.isBlank() || password.isEmpty()) {
                                    errorMessage = "أدخل اسم المستخدم وكلمة المرور."
                                    password = ""
                                    return@Button
                                }

                                val submittedUsername = username
                                val submittedPassword = password
                                isAuthenticating = true
                                errorMessage = null
                                scope.launch {
                                    var session: AuthenticatedSession? = null
                                    try {
                                        session = withContext(Dispatchers.IO) {
                                            authenticationService.authenticate(
                                                tenantId = storeContext.tenantId,
                                                storeId = storeContext.storeId,
                                                username = submittedUsername,
                                                password = submittedPassword.toCharArray()
                                            )
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        errorMessage = "تعذر تسجيل الدخول. تحقق من البيانات وحاول مرة أخرى."
                                    } finally {
                                        password = ""
                                        passwordVisible = false
                                        isAuthenticating = false
                                    }

                                    session?.let(onAuthenticated)
                                }
                            },
                            enabled = !isAuthenticating,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isAuthenticating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.width(20.dp).height(20.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("جارٍ تسجيل الدخول...")
                            } else {
                                Text("تسجيل الدخول")
                            }
                        }
                    }
                }
            }
        }
    }
}
