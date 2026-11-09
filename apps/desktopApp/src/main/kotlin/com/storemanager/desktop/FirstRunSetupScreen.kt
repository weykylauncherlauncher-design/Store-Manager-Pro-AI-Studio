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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.storemanager.core.data.FirstRunSetupService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FirstRunSetupScreen(
    setupService: FirstRunSetupService,
    onSetupComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var tenantName by remember { mutableStateOf("") }
    var storeName by remember { mutableStateOf("") }
    var storeCode by remember { mutableStateOf("") }
    var adminFullName by remember { mutableStateOf("") }
    var adminUsername by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var setupSucceeded by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.TopCenter
            ) {
                if (setupSucceeded) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 72.dp)
                            .width(680.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(32.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "اكتمل إعداد المتجر",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "تم إنشاء المؤسسة والمتجر وحساب المدير بنجاح. يمكنك الآن متابعة شاشة التهيئة المؤقتة.",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Button(onClick = onSetupComplete) {
                                Text("متابعة")
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .width(720.dp)
                            .padding(horizontal = 24.dp, vertical = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "مرحباً بك في Store Manager Pro",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "أكمل البيانات التالية لتجهيز مؤسستك وحساب المدير الأول.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        SetupSection(title = "بيانات المؤسسة") {
                            SetupTextField(
                                value = tenantName,
                                onValueChange = { tenantName = it },
                                label = "اسم المؤسسة",
                                enabled = !isSubmitting
                            )
                        }

                        SetupSection(title = "بيانات المتجر") {
                            SetupTextField(
                                value = storeName,
                                onValueChange = { storeName = it },
                                label = "اسم المتجر",
                                enabled = !isSubmitting
                            )
                            SetupTextField(
                                value = storeCode,
                                onValueChange = { storeCode = it },
                                label = "رمز المتجر",
                                enabled = !isSubmitting
                            )
                        }

                        SetupSection(title = "حساب المدير") {
                            SetupTextField(
                                value = adminFullName,
                                onValueChange = { adminFullName = it },
                                label = "الاسم الكامل",
                                enabled = !isSubmitting
                            )
                            SetupTextField(
                                value = adminUsername,
                                onValueChange = { adminUsername = it },
                                label = "اسم المستخدم",
                                enabled = !isSubmitting
                            )
                            PasswordField(
                                value = password,
                                onValueChange = { password = it },
                                label = "كلمة المرور",
                                visible = passwordVisible,
                                onVisibilityChange = { passwordVisible = it },
                                enabled = !isSubmitting
                            )
                            PasswordField(
                                value = confirmPassword,
                                onValueChange = { confirmPassword = it },
                                label = "تأكيد كلمة المرور",
                                visible = confirmPasswordVisible,
                                onVisibilityChange = { confirmPasswordVisible = it },
                                enabled = !isSubmitting
                            )
                        }

                        errorMessage?.let { message ->
                            Text(
                                text = message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Button(
                            onClick = {
                                errorMessage = validateSetupFields(
                                    tenantName,
                                    storeName,
                                    storeCode,
                                    adminFullName,
                                    adminUsername,
                                    password,
                                    confirmPassword
                                )
                                if (errorMessage != null) return@Button

                                val passwordChars = password.toCharArray()
                                isSubmitting = true
                                errorMessage = null
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            setupService.setup(
                                                tenantName = tenantName,
                                                storeName = storeName,
                                                storeCode = storeCode,
                                                adminFullName = adminFullName,
                                                adminUsername = adminUsername,
                                                adminPassword = passwordChars
                                            )
                                        }
                                        setupSucceeded = true
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: IllegalStateException) {
                                        errorMessage = "تعذر إكمال الإعداد. قد تكون قاعدة البيانات قد أُعدّت مسبقاً أو تحتوي على بيانات متعارضة."
                                    } catch (_: IllegalArgumentException) {
                                        errorMessage = "تحقق من البيانات المدخلة وحاول مرة أخرى."
                                    } catch (_: Exception) {
                                        errorMessage = "حدث خطأ أثناء إعداد المتجر. لم يتم اعتماد الإعداد؛ يرجى المحاولة لاحقاً."
                                    } finally {
                                        passwordChars.fill('\u0000')
                                        password = ""
                                        confirmPassword = ""
                                        isSubmitting = false
                                    }
                                }
                            },
                            enabled = !isSubmitting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isSubmitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.width(20.dp).height(20.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("جارٍ إعداد المتجر...")
                            } else {
                                Text("إنهاء الإعداد")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupSection(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun SetupTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visible: Boolean,
    onVisibilityChange: (Boolean) -> Unit,
    enabled: Boolean
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            TextButton(onClick = { onVisibilityChange(!visible) }, enabled = enabled) {
                Text(if (visible) "إخفاء" else "إظهار")
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

private fun validateSetupFields(
    tenantName: String,
    storeName: String,
    storeCode: String,
    adminFullName: String,
    adminUsername: String,
    password: String,
    confirmPassword: String
): String? {
    if (listOf(tenantName, storeName, storeCode, adminFullName, adminUsername).any(String::isBlank)) {
        return "يرجى تعبئة جميع الحقول المطلوبة."
    }
    if (password.isBlank()) return "يرجى إدخال كلمة مرور المدير."
    if (password != confirmPassword) return "كلمتا المرور غير متطابقتين."
    return null
}
