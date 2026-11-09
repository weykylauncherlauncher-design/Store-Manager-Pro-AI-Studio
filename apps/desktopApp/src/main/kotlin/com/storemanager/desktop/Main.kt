package com.storemanager.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.storemanager.core.data.AuthenticatedSession
import com.storemanager.core.data.AuthenticationService
import com.storemanager.core.data.CategoryManagementPermissionsMigration
import com.storemanager.core.data.DatabaseDriverFactory
import com.storemanager.core.data.FirstRunSetupService
import com.storemanager.core.data.ProductCategoryRepositoryImpl
import com.storemanager.core.data.ProductCategoryUseCases
import com.storemanager.core.data.ProductRepositoryImpl
import com.storemanager.core.data.ProductUseCases
import com.storemanager.core.data.ProductPermissionsMigration
import com.storemanager.core.data.PermissionCodes
import com.storemanager.core.data.PermissionGuard
import com.storemanager.core.data.StoreContextResolution
import com.storemanager.core.data.StoreContextResolver
import com.storemanager.core.data.StoreDatabase
import com.storemanager.core.data.StoreDatabaseManager
import com.storemanager.core.designsystem.components.StoreBootstrapScreen
import com.storemanager.core.designsystem.theme.StoreManagerTheme
import com.storemanager.core.domain.model.PlatformType
import com.storemanager.core.domain.model.StoreContext
import com.storemanager.core.domain.usecase.GetSystemEnvironmentUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AuthenticatedProductContext(
    val session: AuthenticatedSession,
    val useCases: ProductUseCases,
    val categoryUseCases: ProductCategoryUseCases
)

val LocalAuthenticatedProductContext = staticCompositionLocalOf<AuthenticatedProductContext> {
    error("Authenticated product context is unavailable")
}

fun main() = application {
    val databaseManager = remember {
        StoreDatabaseManager(DatabaseDriverFactory())
    }
    var appState by remember {
        mutableStateOf<AppState>(AppState.Initializing)
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(databaseManager) {
        try {
            val (database, resolution) = withContext(Dispatchers.IO) {
                val openedDatabase = databaseManager.getDatabase()
                ProductPermissionsMigration(openedDatabase, databaseManager.getDriver()).migrate()
                CategoryManagementPermissionsMigration(openedDatabase, databaseManager.getDriver()).migrate()
                openedDatabase to StoreContextResolver(openedDatabase).resolve()
            }
            appState = appStateFor(database, resolution)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            appState = AppState.InitializationError
        }
    }

    DisposableEffect(databaseManager) {
        onDispose { databaseManager.close() }
    }

    val environment = remember {
        GetSystemEnvironmentUseCase()(PlatformType.WINDOWS)
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Store Manager Pro — Windows Host",
        state = WindowState(width = 1100.dp, height = 750.dp)
    ) {
        StoreManagerTheme(darkTheme = true) {
            when (val state = appState) {
                AppState.Initializing -> InitializingScreen()
                is AppState.FirstRunSetup -> FirstRunSetupScreen(
                    setupService = remember(state.database) {
                        FirstRunSetupService(state.database)
                    },
                    onSetupComplete = {
                        appState = AppState.Initializing
                        scope.launch {
                            try {
                                val resolution = withContext(Dispatchers.IO) {
                                    ProductPermissionsMigration(
                                        state.database,
                                        databaseManager.getDriver()
                                    ).migrate()
                                    CategoryManagementPermissionsMigration(
                                        state.database,
                                        databaseManager.getDriver()
                                    ).migrate()
                                    StoreContextResolver(state.database).resolve()
                                }
                                appState = appStateFor(state.database, resolution)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                appState = AppState.InitializationError
                            }
                        }
                    }
                )
                is AppState.Login -> LoginScreen(
                    authenticationService = remember(state.database) {
                        AuthenticationService(state.database)
                    },
                    storeContext = state.storeContext,
                    onAuthenticated = { session ->
                        appState = AppState.Authenticated(
                            database = state.database,
                            storeContext = state.storeContext,
                            session = session
                        )
                    }
                )
                is AppState.Authenticated -> {
                    val productUseCases = remember(state.database) {
                        ProductUseCases(ProductRepositoryImpl(state.database))
                    }
                    val productCategoryUseCases = remember(state.database) {
                        ProductCategoryUseCases(ProductCategoryRepositoryImpl(state.database))
                    }
                    val productContext = remember(
                        state.session,
                        productUseCases,
                        productCategoryUseCases
                    ) {
                        AuthenticatedProductContext(
                            session = state.session,
                            useCases = productUseCases,
                            categoryUseCases = productCategoryUseCases
                        )
                    }
                    var showProducts by remember(state.session) {
                        mutableStateOf(false)
                    }
                    val canViewProducts = PermissionGuard.hasPermission(
                        state.session,
                        PermissionCodes.PRODUCT_VIEW
                    )
                    CompositionLocalProvider(
                        LocalAuthenticatedProductContext provides productContext
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                if (showProducts) {
                                    Button(onClick = { showProducts = false }) {
                                        Text("العودة")
                                    }
                                } else if (canViewProducts) {
                                    Button(onClick = { showProducts = true }) {
                                        Text("إدارة المنتجات")
                                    }
                                }
                            }
                            if (showProducts && canViewProducts) {
                                ProductManagementScreen(modifier = Modifier.weight(1f))
                            } else {
                                StoreBootstrapScreen(
                                    environment = environment,
                                    storeContext = StoreContext(
                                        storeId = state.storeContext.storeId,
                                        storeName = state.storeContext.storeName
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                is AppState.SelectionRequired -> StoreSelectionScreen(
                    choices = state.choices,
                    onChoiceSelected = { choice ->
                        appState = AppState.Login(state.database, choice)
                    }
                )
                AppState.InvalidConfiguration -> StartupMessage(
                    title = "إعداد قاعدة البيانات غير صالح",
                    message = "تحتوي قاعدة البيانات على إعداد غير مكتمل أو غير صالح. تم إيقاف المتابعة لحماية البيانات."
                )
                AppState.InitializationError -> StartupMessage(
                    title = "تعذر تهيئة التطبيق",
                    message = "تعذر فتح قاعدة البيانات أو تحميل سياق المتجر. لم يتم تسجيل الدخول."
                )
            }
        }
    }
}

private sealed interface AppState {
    data object Initializing : AppState
    data class FirstRunSetup(val database: StoreDatabase) : AppState
    data class Login(
        val database: StoreDatabase,
        val storeContext: StoreContextResolution.Ready
    ) : AppState
    data class Authenticated(
        val database: StoreDatabase,
        val storeContext: StoreContextResolution.Ready,
        val session: AuthenticatedSession
    ) : AppState
    data class SelectionRequired(
        val database: StoreDatabase,
        val choices: List<StoreContextResolution.Ready>
    ) : AppState
    data object InvalidConfiguration : AppState
    data object InitializationError : AppState
}

private fun appStateFor(
    database: StoreDatabase,
    resolution: StoreContextResolution
): AppState = when (resolution) {
    StoreContextResolution.NeedsFirstRunSetup -> AppState.FirstRunSetup(database)
    is StoreContextResolution.Ready -> AppState.Login(database, resolution)
    is StoreContextResolution.SelectionRequired ->
        AppState.SelectionRequired(database, resolution.choices)
    StoreContextResolution.InvalidConfiguration -> AppState.InvalidConfiguration
}

@Composable
private fun InitializingScreen() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("جارٍ تهيئة التطبيق...")
        }
    }
}

@Composable
private fun StoreSelectionScreen(
    choices: List<StoreContextResolution.Ready>,
    onChoiceSelected: (StoreContextResolution.Ready) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "يتطلب المتابعة اختيار المؤسسة والمتجر",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(16.dp))
            choices.forEach { choice ->
                androidx.compose.material3.OutlinedButton(
                    onClick = { onChoiceSelected(choice) },
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    Text("${choice.tenantName} — ${choice.storeName}")
                }
            }
        }
    }
}

@Composable
private fun StartupMessage(title: String, message: String) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}
