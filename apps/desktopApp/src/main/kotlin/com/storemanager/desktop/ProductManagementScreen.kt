package com.storemanager.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.storemanager.core.data.AuthorizationException
import com.storemanager.core.data.PermissionCodes
import com.storemanager.core.data.PermissionGuard
import com.storemanager.core.domain.model.CreateProduct
import com.storemanager.core.domain.model.CreateProductVariant
import com.storemanager.core.domain.model.ProductCategory
import com.storemanager.core.domain.model.Product
import com.storemanager.core.domain.model.VariantAttributes
import com.storemanager.core.domain.model.UpdateProduct
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface ProductEditorMode {
    data object Create : ProductEditorMode
    data class Edit(val product: Product) : ProductEditorMode
}

private data class ProductUnitOption(val id: String, val label: String)

private data class VariantDraftUiState(
    val id: Long,
    val sku: String,
    val barcode: String,
    val name: String,
    val attributes: Map<String, String>
)

private fun replaceVariantDraft(
    drafts: List<VariantDraftUiState>,
    updatedDraft: VariantDraftUiState
): List<VariantDraftUiState> = drafts.map { draft ->
    if (draft.id == updatedDraft.id) updatedDraft else draft
}

private val productUnitOptions = listOf(
    ProductUnitOption("UOM_PIECE", "قطعة"),
    ProductUnitOption("UOM_KG", "كيلوغرام"),
    ProductUnitOption("UOM_GRAM", "غرام"),
    ProductUnitOption("UOM_METER", "متر"),
    ProductUnitOption("UOM_LITER", "لتر")
)

@Composable
fun ProductManagementScreen(modifier: Modifier = Modifier) {
    val context = LocalAuthenticatedProductContext.current
    val canViewProducts = PermissionGuard.hasPermission(
        context.session,
        PermissionCodes.PRODUCT_VIEW
    )
    var searchQuery by remember { mutableStateOf("") }
    var retryKey by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var products by remember { mutableStateOf<List<Product>>(emptyList()) }
    var refreshKey by remember { mutableStateOf(0) }
    var editorMode by remember { mutableStateOf<ProductEditorMode?>(null) }
    var isLoadingProduct by remember { mutableStateOf(false) }
    var editorLoadError by remember { mutableStateOf(false) }
    var pendingDeactivation by remember { mutableStateOf<Product?>(null) }
    var operationProductId by remember { mutableStateOf<String?>(null) }
    var productActionError by remember { mutableStateOf<String?>(null) }
    var showCategoryManagement by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val canCreateProducts = PermissionGuard.hasPermission(
        context.session,
        PermissionCodes.PRODUCT_CREATE
    )
    val canUpdateProducts = PermissionGuard.hasPermission(
        context.session,
        PermissionCodes.PRODUCT_UPDATE
    )
    val canDeactivateProducts = PermissionGuard.hasPermission(
        context.session,
        PermissionCodes.PRODUCT_DEACTIVATE
    )

    LaunchedEffect(context, searchQuery, retryKey, refreshKey) {
        if (!canViewProducts) return@LaunchedEffect

        isLoading = true
        loadError = false
        try {
            products = withContext(Dispatchers.IO) {
                if (searchQuery.isBlank()) {
                    context.useCases.getProducts(context.session, includeInactive = true)
                } else {
                    context.useCases.searchProducts(
                        context.session,
                        searchQuery,
                        includeInactive = true
                    )
                }
            }
            isLoading = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadError = true
            isLoading = false
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl
    ) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            if (showCategoryManagement && canViewProducts) {
                ProductCategoryManagementScreen(
                    onBack = { showCategoryManagement = false },
                    modifier = Modifier.fillMaxSize()
                )
            } else Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "المنتجات",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (canViewProducts) {
                        Button(onClick = { showCategoryManagement = true }) {
                            Text("إدارة الفئات")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (canCreateProducts) {
                        Button(onClick = { editorMode = ProductEditorMode.Create }) {
                            Text("إضافة منتج")
                        }
                    }
                }

                if (!canViewProducts) {
                    Text(
                        text = "لا تملك صلاحية عرض المنتجات.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("بحث بالاسم أو بادئة الكود أو الباركود") },
                        singleLine = true
                    )

                    Text(
                        text = if (isLoading) {
                            "جارٍ تحميل عدد المنتجات..."
                        } else if (loadError) {
                            "تعذر تحميل عدد المنتجات"
                        } else {
                            "عدد المنتجات: ${products.size}"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    productActionError?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    when {
                        isLoading -> LoadingProducts()
                        loadError -> ProductLoadError(onRetry = { retryKey++ })
                        products.isEmpty() -> ProductEmptyState(isSearch = searchQuery.isNotBlank())
                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(products, key = Product::id) { product ->
                                ProductCard(
                                    product = product,
                                    canUpdate = canUpdateProducts,
                                    canDeactivate = canDeactivateProducts,
                                    isOperationInProgress = operationProductId == product.id,
                                    isAnyOperationInProgress = operationProductId != null,
                                    onEdit = {
                                        scope.launch {
                                            isLoadingProduct = true
                                            editorLoadError = false
                                            try {
                                                val currentProduct = withContext(Dispatchers.IO) {
                                                    context.useCases.getProduct(
                                                        context.session,
                                                        product.id
                                                    )
                                                }
                                                if (currentProduct == null) {
                                                    editorLoadError = true
                                                } else {
                                                    editorMode = ProductEditorMode.Edit(currentProduct)
                                                }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                editorLoadError = true
                                            } finally {
                                                isLoadingProduct = false
                                            }
                                        }
                                    },
                                    onDeactivate = {
                                        productActionError = null
                                        pendingDeactivation = product
                                    },
                                    onReactivate = {
                                        if (operationProductId != null) return@ProductCard
                                        productActionError = null
                                        operationProductId = product.id
                                        scope.launch {
                                            try {
                                                val updated = withContext(Dispatchers.IO) {
                                                    context.useCases.reactivateProduct(
                                                        context.session,
                                                        product.id
                                                    )
                                                }
                                                check(updated) {
                                                    "تعذر العثور على المنتج ضمن المؤسسة الحالية."
                                                }
                                                refreshKey++
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (error: AuthorizationException) {
                                                productActionError = "لا تملك صلاحية إعادة تفعيل المنتجات."
                                            } catch (_: Exception) {
                                                productActionError = "تعذر إعادة تفعيل المنتج. لم تتغير حالته."
                                            } finally {
                                                operationProductId = null
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                if (isLoadingProduct) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                if (editorLoadError) {
                    Text(
                        text = "تعذر تحميل بيانات المنتج. أعد المحاولة.",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                editorMode?.let { mode ->
                    ProductEditorDialog(
                        mode = mode,
                        onDismiss = { editorMode = null },
                        onCreate = { product, variants ->
                            withContext(Dispatchers.IO) {
                                if (variants == null) {
                                    context.useCases.createProduct(context.session, product)
                                } else {
                                    context.useCases.createProductWithVariants(
                                        context.session,
                                        product,
                                        variants
                                    )
                                }
                            }
                            refreshKey++
                        },
                        onUpdate = { productId, product ->
                            val updated = withContext(Dispatchers.IO) {
                                context.useCases.updateProduct(
                                    context.session,
                                    productId,
                                    product
                                )
                            }
                            check(updated) { "تعذر العثور على المنتج ضمن المؤسسة الحالية." }
                            refreshKey++
                        }
                    )
                }
                pendingDeactivation?.let { product ->
                    AlertDialog(
                        onDismissRequest = {
                            if (operationProductId == null) pendingDeactivation = null
                        },
                        properties = DialogProperties(
                            dismissOnBackPress = operationProductId == null,
                            dismissOnClickOutside = operationProductId == null
                        ),
                        title = { Text("تعطيل المنتج") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("هل تريد تعطيل المنتج «${product.name}»؟")
                                Text(
                                    "التعطيل لا يحذف المنتج أو بياناته، ويمكن إعادة تفعيله لاحقاً."
                                )
                                if (operationProductId == product.id) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(strokeWidth = 2.dp)
                                        Text("جارٍ تعطيل المنتج...")
                                    }
                                }
                                productActionError?.let {
                                    Text(
                                        text = it,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                enabled = operationProductId == null,
                                onClick = {
                                    if (operationProductId != null) return@Button
                                    productActionError = null
                                    operationProductId = product.id
                                    scope.launch {
                                        try {
                                            val updated = withContext(Dispatchers.IO) {
                                                context.useCases.deactivateProduct(
                                                    context.session,
                                                    product.id
                                                )
                                            }
                                            check(updated) {
                                                "تعذر العثور على المنتج ضمن المؤسسة الحالية."
                                            }
                                            pendingDeactivation = null
                                            refreshKey++
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (error: AuthorizationException) {
                                            productActionError = "لا تملك صلاحية تعطيل المنتجات."
                                        } catch (_: Exception) {
                                            productActionError = "تعذر تعطيل المنتج. لم تتغير حالته."
                                        } finally {
                                            operationProductId = null
                                        }
                                    }
                                }
                            ) {
                                Text("تعطيل")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                enabled = operationProductId == null,
                                onClick = { pendingDeactivation = null }
                            ) {
                                Text("إلغاء")
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingProducts() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator()
        Text("جارٍ تحميل المنتجات...")
    }
}

@Composable
private fun ProductEditorDialog(
    mode: ProductEditorMode,
    onDismiss: () -> Unit,
    onCreate: suspend (CreateProduct, List<CreateProductVariant>?) -> Unit,
    onUpdate: suspend (String, UpdateProduct) -> Unit
) {
    val context = LocalAuthenticatedProductContext.current
    val scope = rememberCoroutineScope()
    val initialProduct = (mode as? ProductEditorMode.Edit)?.product
    var name by remember(mode) { mutableStateOf(initialProduct?.name.orEmpty()) }
    var code by remember(mode) { mutableStateOf(initialProduct?.code.orEmpty()) }
    var barcode by remember(mode) { mutableStateOf(initialProduct?.barcode.orEmpty()) }
    var selectedCategoryId by remember(mode) {
        mutableStateOf(initialProduct?.categoryId)
    }
    var categories by remember(mode) { mutableStateOf<List<ProductCategory>>(emptyList()) }
    var isLoadingCategories by remember(mode) { mutableStateOf(true) }
    var categoriesLoadError by remember(mode) { mutableStateOf(false) }
    var categoriesRetryKey by remember(mode) { mutableStateOf(0) }
    var selectedUnit by remember(mode) {
        mutableStateOf(
            productUnitOptions.firstOrNull { it.id == initialProduct?.baseUnitId }
                ?: productUnitOptions.first()
        )
    }
    var hasVariants by remember(mode) { mutableStateOf(false) }
    var variantDrafts by remember(mode) {
        mutableStateOf<List<VariantDraftUiState>>(emptyList())
    }
    var nextVariantDraftId by remember(mode) { mutableStateOf(0L) }
    fun createVariantDraft(): VariantDraftUiState = VariantDraftUiState(
        id = nextVariantDraftId++,
        sku = "",
        barcode = "",
        name = "",
        attributes = emptyMap()
    )
    var editingVariantDraftId by remember(mode) { mutableStateOf<Long?>(null) }
    var variantSku by remember(mode) { mutableStateOf("") }
    var variantBarcode by remember(mode) { mutableStateOf("") }
    var variantName by remember(mode) { mutableStateOf("") }
    var variantAttributes by remember(mode) {
        mutableStateOf<List<Pair<String, String>>>(emptyList())
    }
    var variantDraftError by remember(mode) { mutableStateOf<String?>(null) }

    fun resetVariantEditor() {
        editingVariantDraftId = null
        variantSku = ""
        variantBarcode = ""
        variantName = ""
        variantAttributes = emptyList()
        variantDraftError = null
    }

    fun editVariantDraft(draft: VariantDraftUiState) {
        editingVariantDraftId = draft.id
        variantSku = draft.sku
        variantBarcode = draft.barcode
        variantName = draft.name
        variantAttributes = draft.attributes.entries.map { it.key to it.value }
        variantDraftError = null
    }

    fun saveVariantDraft() {
        val normalizedSku = variantSku.trim()
        val normalizedName = variantName.trim()
        val normalizedAttributes = variantAttributes.map { (key, value) ->
            key.trim() to value
        }
        val attributeKeys = normalizedAttributes.map { it.first }
        variantDraftError = when {
            normalizedSku.isEmpty() -> "SKU مطلوب للمتغير."
            normalizedName.isEmpty() -> "اسم المتغير مطلوب."
            normalizedAttributes.any { it.first.isEmpty() } ->
                "مفتاح الخاصية لا يمكن أن يكون فارغاً."
            attributeKeys.size != attributeKeys.toSet().size ->
                "لا يمكن تكرار مفتاح الخاصية نفسه."
            else -> null
        }
        if (variantDraftError != null) return

        val draftId = editingVariantDraftId ?: createVariantDraft().id
        val updatedValues = VariantDraftUiState(
            id = draftId,
            sku = normalizedSku,
            barcode = variantBarcode.trim(),
            name = normalizedName,
            attributes = normalizedAttributes.toMap()
        )
        variantDrafts = editingVariantDraftId?.let { draftId ->
            replaceVariantDraft(variantDrafts, updatedValues.copy(id = draftId))
        } ?: (variantDrafts + updatedValues)
        resetVariantEditor()
    }

    fun removeVariantDraft(draftId: Long) {
        variantDrafts = variantDrafts.filterNot { it.id == draftId }
        if (editingVariantDraftId == draftId) resetVariantEditor()
    }

    var unitMenuExpanded by remember { mutableStateOf(false) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var unitError by remember { mutableStateOf<String?>(null) }
    var categoryError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(context, mode, categoriesRetryKey) {
        isLoadingCategories = true
        categoriesLoadError = false
        try {
            categories = withContext(Dispatchers.IO) {
                context.categoryUseCases.getCategories(
                    context.session,
                    includeInactive = true
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            categoriesLoadError = true
        } finally {
            isLoadingCategories = false
        }
    }

    val selectableCategories = categories.filter {
        it.isActive || it.id == initialProduct?.categoryId
    }
    val currentCategoryUnavailable = initialProduct?.categoryId != null &&
        categories.none { it.id == initialProduct.categoryId }
    val selectedCategoryLabel = when {
        selectedCategoryId == null -> "بدون تصنيف"
        else -> selectableCategories.firstOrNull { it.id == selectedCategoryId }?.let {
            if (it.isActive) it.name else "${it.name} (الفئة الحالية - معطّلة)"
        } ?: "الفئة الحالية (غير نشطة أو غير متاحة)"
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !isSaving, dismissOnClickOutside = !isSaving),
        title = {
            Text(if (initialProduct == null) "إضافة منتج" else "تعديل المنتج")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = null
                        saveError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("اسم المنتج *") },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = { nameError?.let { Text(it) } }
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it
                        saveError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("الكود (اختياري)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = barcode,
                    onValueChange = {
                        barcode = it
                        saveError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("الباركود (اختياري)") },
                    singleLine = true
                )
                if (initialProduct == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("نوع المنتج", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!hasVariants) {
                                Button(onClick = { hasVariants = false }) {
                                    Text("منتج عادي")
                                }
                                TextButton(onClick = { hasVariants = true }) {
                                    Text("منتج ذو متغيرات")
                                }
                            } else {
                                TextButton(onClick = { hasVariants = false }) {
                                    Text("منتج عادي")
                                }
                                Button(onClick = { hasVariants = true }) {
                                    Text("منتج ذو متغيرات")
                                }
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box {
                        OutlinedTextField(
                            value = selectedCategoryLabel,
                            onValueChange = {},
                            readOnly = true,
                            enabled = !isSaving && !isLoadingCategories && !categoriesLoadError,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("الفئة") },
                            trailingIcon = {
                                TextButton(
                                    onClick = { categoryMenuExpanded = true },
                                    enabled = !isSaving &&
                                        !isLoadingCategories &&
                                        !categoriesLoadError
                                ) {
                                    Text("اختيار")
                                }
                            },
                            isError = categoryError != null
                        )
                        DropdownMenu(
                            expanded = categoryMenuExpanded,
                            onDismissRequest = { categoryMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("بدون تصنيف") },
                                onClick = {
                                    selectedCategoryId = null
                                    categoryError = null
                                    categoryMenuExpanded = false
                                    saveError = null
                                }
                            )
                            selectableCategories.forEach { category ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (category.isActive) {
                                                category.name
                                            } else {
                                                "${category.name} (الفئة الحالية - معطّلة)"
                                            }
                                        )
                                    },
                                    onClick = {
                                        selectedCategoryId = category.id
                                        categoryError = null
                                        categoryMenuExpanded = false
                                        saveError = null
                                    }
                                )
                            }
                            if (currentCategoryUnavailable) {
                                DropdownMenuItem(
                                    text = { Text("الفئة الحالية (غير نشطة أو غير متاحة)") },
                                    onClick = {
                                        selectedCategoryId = initialProduct.categoryId
                                        categoryError = null
                                        categoryMenuExpanded = false
                                        saveError = null
                                    }
                                )
                            }
                        }
                    }
                    when {
                        isLoadingCategories -> {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(strokeWidth = 2.dp)
                                Text("جارٍ تحميل الفئات...")
                            }
                        }
                        categoriesLoadError -> {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "تعذر تحميل الفئات.",
                                    color = MaterialTheme.colorScheme.error
                                )
                                TextButton(
                                    enabled = !isSaving,
                                    onClick = { categoriesRetryKey++ }
                                ) {
                                    Text("إعادة المحاولة")
                                }
                            }
                        }
                        selectableCategories.none { it.isActive } -> {
                            Text(
                                "لا توجد فئات نشطة. يمكنك المتابعة بدون تصنيف.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    categoryError?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (initialProduct == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box {
                            OutlinedTextField(
                                value = selectedUnit.label,
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("الوحدة الأساسية *") },
                                trailingIcon = {
                                    TextButton(
                                        onClick = { unitMenuExpanded = true },
                                        enabled = !isSaving
                                    ) {
                                        Text("اختيار")
                                    }
                                },
                                isError = unitError != null
                            )
                            DropdownMenu(
                                expanded = unitMenuExpanded,
                                onDismissRequest = { unitMenuExpanded = false }
                            ) {
                                productUnitOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        onClick = {
                                            selectedUnit = option
                                            unitError = null
                                            unitMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        unitError?.let {
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                if (initialProduct == null && hasVariants) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "مسودات المتغيرات",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                TextButton(
                                    enabled = !isSaving,
                                    onClick = { resetVariantEditor() }
                                ) {
                                    Text("متغير جديد")
                                }
                            }
                            OutlinedTextField(
                                value = variantSku,
                                onValueChange = {
                                    variantSku = it
                                    variantDraftError = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("SKU *") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = variantBarcode,
                                onValueChange = { variantBarcode = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("باركود المتغير (اختياري)") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = variantName,
                                onValueChange = {
                                    variantName = it
                                    variantDraftError = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("اسم المتغير *") },
                                singleLine = true
                            )
                            Text(
                                "خصائص المتغير",
                                style = MaterialTheme.typography.titleSmall
                            )
                            variantAttributes.forEachIndexed { index, attribute ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = attribute.first,
                                        onValueChange = { value ->
                                            variantAttributes = variantAttributes.mapIndexed { itemIndex, item ->
                                                if (itemIndex == index) value to item.second else item
                                            }
                                            variantDraftError = null
                                        },
                                        modifier = Modifier.weight(1f),
                                        label = { Text("المفتاح") },
                                        singleLine = true
                                    )
                                    OutlinedTextField(
                                        value = attribute.second,
                                        onValueChange = { value ->
                                            variantAttributes = variantAttributes.mapIndexed { itemIndex, item ->
                                                if (itemIndex == index) item.first to value else item
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        label = { Text("القيمة") },
                                        singleLine = true
                                    )
                                    TextButton(
                                        enabled = !isSaving,
                                        onClick = {
                                            variantAttributes = variantAttributes.filterIndexed { itemIndex, _ ->
                                                itemIndex != index
                                            }
                                        }
                                    ) {
                                        Text("حذف")
                                    }
                                }
                            }
                            TextButton(
                                enabled = !isSaving,
                                onClick = {
                                    variantAttributes = variantAttributes + ("" to "")
                                    variantDraftError = null
                                }
                            ) {
                                Text("إضافة خاصية")
                            }
                            variantDraftError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error)
                            }
                            Button(
                                enabled = !isSaving,
                                onClick = { saveVariantDraft() }
                            ) {
                                Text(
                                    if (editingVariantDraftId == null) {
                                        "إضافة المسودة"
                                    } else {
                                        "تحديث المسودة"
                                    }
                                )
                            }
                            if (variantDrafts.isEmpty()) {
                                Text(
                                    "لم تتم إضافة متغيرات بعد.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                variantDrafts.forEach { draft ->
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(draft.name)
                                                Text(
                                                    "SKU: ${draft.sku}",
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                                if (draft.attributes.isNotEmpty()) {
                                                    Text(
                                                        draft.attributes.entries.joinToString { "${it.key}: ${it.value}" },
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                }
                                            }
                                            Row {
                                                TextButton(
                                                    enabled = !isSaving,
                                                    onClick = { editVariantDraft(draft) }
                                                ) {
                                                    Text("تعديل")
                                                }
                                                TextButton(
                                                    enabled = !isSaving,
                                                    onClick = { removeVariantDraft(draft.id) }
                                                ) {
                                                    Text("حذف")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Text(
                                "سيتم تفعيل حفظ المنتجات ذات المتغيرات في خطوة الربط التالية.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                saveError?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isSaving && !isLoadingCategories && !categoriesLoadError,
                onClick = {
                    if (isSaving) return@Button
                    nameError = if (name.isBlank()) "اسم المنتج مطلوب." else null
                    unitError = if (initialProduct == null && selectedUnit.id.isBlank()) {
                        "اختر الوحدة الأساسية."
                    } else {
                        null
                    }
                    val categoryIsAllowed = selectedCategoryId == null ||
                        selectableCategories.any { it.id == selectedCategoryId && it.isActive } ||
                        selectedCategoryId == initialProduct?.categoryId
                    categoryError = if (!categoryIsAllowed) {
                        "الفئة المختارة غير متاحة."
                    } else {
                        null
                    }
                    val variantsError = if (initialProduct == null && hasVariants) {
                        when {
                            variantDrafts.isEmpty() -> "أضف متغيراً واحداً على الأقل قبل الحفظ."
                            variantDrafts.any { it.sku.trim().isEmpty() } ->
                                "كل متغير يجب أن يحتوي على SKU."
                            variantDrafts.any { it.name.trim().isEmpty() } ->
                                "كل متغير يجب أن يحتوي على اسم."
                            else -> null
                        }
                    } else {
                        null
                    }
                    if (nameError != null || unitError != null || categoryError != null) {
                        return@Button
                    }
                    if (variantsError != null) {
                        saveError = variantsError
                        return@Button
                    }

                    isSaving = true
                    saveError = null
                    scope.launch {
                        try {
                            if (initialProduct == null) {
                                val createVariants = if (hasVariants) {
                                    variantDrafts.map { draft ->
                                        CreateProductVariant(
                                            sku = draft.sku,
                                            barcode = draft.barcode.takeIf(String::isNotEmpty),
                                            name = draft.name,
                                            attributes = VariantAttributes(draft.attributes)
                                        )
                                    }
                                } else {
                                    null
                                }
                                onCreate(
                                    CreateProduct(
                                        name = name.trim(),
                                        baseUnitId = selectedUnit.id,
                                        categoryId = selectedCategoryId,
                                        code = code.trim().takeIf(String::isNotEmpty),
                                        barcode = barcode.trim().takeIf(String::isNotEmpty)
                                    ),
                                    createVariants
                                )
                            } else {
                                onUpdate(
                                    initialProduct.id,
                                    UpdateProduct(
                                        name = name.trim(),
                                        categoryId = selectedCategoryId,
                                        code = code.trim().takeIf(String::isNotEmpty),
                                        barcode = barcode.trim().takeIf(String::isNotEmpty)
                                    )
                                )
                            }
                            onDismiss()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: AuthorizationException) {
                            saveError = "لا تملك صلاحية تنفيذ هذه العملية."
                            isSaving = false
                        } catch (_: Exception) {
                            saveError = "تعذر حفظ المنتج. تحقق من البيانات وحاول مرة أخرى."
                            isSaving = false
                        }
                    }
                }
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp
                    )
                }
                Text(if (isSaving) "جارٍ الحفظ..." else "حفظ")
            }
        },
        dismissButton = {
            TextButton(enabled = !isSaving, onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

@Composable
private fun ProductLoadError(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "تعذر تحميل المنتجات. تحقق من الاتصال بقاعدة البيانات ثم أعد المحاولة.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge
        )
        Button(onClick = onRetry) {
            Text("إعادة المحاولة")
        }
    }
}

@Composable
private fun ProductEmptyState(isSearch: Boolean) {
    Text(
        text = if (isSearch) "لا توجد نتائج مطابقة للبحث." else "لا توجد منتجات حالياً.",
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ProductCard(
    product: Product,
    canUpdate: Boolean,
    canDeactivate: Boolean,
    isOperationInProgress: Boolean,
    isAnyOperationInProgress: Boolean,
    onEdit: () -> Unit,
    onDeactivate: () -> Unit,
    onReactivate: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (product.isActive) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ) {
                    Text(
                        text = if (product.isActive) "نشط" else "معطل",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (product.isActive) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            ProductDetail("الوحدة", product.baseUnitId.toArabicUnitName())
            product.code?.let { ProductDetail("الكود", it) }
            product.barcode?.let { ProductDetail("الباركود", it) }
            if (canUpdate) {
                TextButton(enabled = !isAnyOperationInProgress, onClick = onEdit) {
                    Text("تعديل")
                }
            }
            if (canDeactivate) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (product.isActive) {
                        TextButton(
                            enabled = !isAnyOperationInProgress,
                            onClick = onDeactivate
                        ) {
                            Text("تعطيل")
                        }
                    } else {
                        TextButton(
                            enabled = !isAnyOperationInProgress,
                            onClick = onReactivate
                        ) {
                            Text("إعادة التفعيل")
                        }
                    }
                    if (isOperationInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(8.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductDetail(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun String.toArabicUnitName(): String = when (this) {
    "UOM_PIECE" -> "قطعة"
    "UOM_KG" -> "كيلوغرام"
    "UOM_GRAM" -> "غرام"
    "UOM_METER" -> "متر"
    "UOM_LITER" -> "لتر"
    else -> "وحدة غير معروفة"
}
