package com.storemanager.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.storemanager.core.data.AuthorizationException
import com.storemanager.core.data.PermissionCodes
import com.storemanager.core.data.PermissionGuard
import com.storemanager.core.domain.model.CreateProductCategory
import com.storemanager.core.domain.model.ProductCategory
import com.storemanager.core.domain.model.UpdateProductCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface CategoryEditorMode {
    data object Create : CategoryEditorMode
    data class Edit(val category: ProductCategory) : CategoryEditorMode
}

private enum class CategoryActivityFilter(val label: String) {
    ALL("الكل"),
    ACTIVE("النشطة"),
    INACTIVE("المعطّلة")
}

private data class HierarchicalCategory(
    val category: ProductCategory,
    val depth: Int
)

@Composable
fun ProductCategoryManagementScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalAuthenticatedProductContext.current
    val canView = PermissionGuard.hasPermission(context.session, PermissionCodes.PRODUCT_VIEW)
    val canCreate = PermissionGuard.hasPermission(context.session, PermissionCodes.CATEGORY_CREATE)
    val canUpdate = PermissionGuard.hasPermission(context.session, PermissionCodes.CATEGORY_UPDATE)
    val canDeactivate = PermissionGuard.hasPermission(context.session, PermissionCodes.CATEGORY_DEACTIVATE)
    var categories by remember(context.session) { mutableStateOf<List<ProductCategory>>(emptyList()) }
    var isLoading by remember(context.session) { mutableStateOf(false) }
    var loadError by remember(context.session) { mutableStateOf(false) }
    var retryKey by remember(context.session) { mutableStateOf(0) }
    var refreshKey by remember(context.session) { mutableStateOf(0) }
    var searchText by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(CategoryActivityFilter.ALL) }
    var filterExpanded by remember { mutableStateOf(false) }
    var editorMode by remember { mutableStateOf<CategoryEditorMode?>(null) }
    var pendingDeactivation by remember { mutableStateOf<ProductCategory?>(null) }
    var operationCategoryId by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(context.session, retryKey, refreshKey) {
        if (!canView) return@LaunchedEffect
        isLoading = true
        loadError = false
        try {
            categories = withContext(Dispatchers.IO) {
                context.categoryUseCases.getCategories(context.session, includeInactive = true)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadError = true
        } finally {
            isLoading = false
        }
    }

    val categoriesById = categories.associateBy(ProductCategory::id)
    val hierarchy = remember(categories) { flattenCategories(categories) }
    val filteredCategories = hierarchy.filter { entry ->
        val category = entry.category
        val matchesQuery = searchText.isBlank() ||
            category.name.contains(searchText.trim(), ignoreCase = true) ||
            category.code.orEmpty().contains(searchText.trim(), ignoreCase = true)
        val matchesActivity = when (filter) {
            CategoryActivityFilter.ALL -> true
            CategoryActivityFilter.ACTIVE -> category.isActive
            CategoryActivityFilter.INACTIVE -> !category.isActive
        }
        matchesQuery && matchesActivity
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "إدارة فئات المنتجات",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "تنظيم الفئات والفئات الفرعية للمنتجات.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onBack) { Text("العودة إلى المنتجات") }
                        if (canView && canCreate) {
                            Button(
                                enabled = !isLoading && !loadError,
                                onClick = { editorMode = CategoryEditorMode.Create }
                            ) {
                                Text("إضافة فئة")
                            }
                        }
                    }
                }

                if (!canView) {
                    Text(
                        "لا تملك صلاحية عرض الفئات.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge
                    )
                } else {
                    CategorySummary(categories = categories, isLoading = isLoading, loadError = loadError)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchText,
                            onValueChange = { searchText = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("بحث بالاسم أو الكود") },
                            singleLine = true
                        )
                        Box {
                            TextButton(onClick = { filterExpanded = true }) {
                                Text("التصفية: ${filter.label}")
                            }
                            DropdownMenu(
                                expanded = filterExpanded,
                                onDismissRequest = { filterExpanded = false }
                            ) {
                                CategoryActivityFilter.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        onClick = {
                                            filter = option
                                            filterExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    actionError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }

                    when {
                        isLoading -> CategoryLoadingState()
                        loadError -> CategoryLoadError(onRetry = { retryKey++ })
                        categories.isEmpty() -> CategoryEmptyState(
                            message = "لا توجد فئات بعد. يمكنك إضافة فئة جديدة."
                        )
                        filteredCategories.isEmpty() -> CategoryEmptyState(
                            message = "لا توجد فئات تطابق البحث أو التصفية."
                        )
                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(filteredCategories, key = { it.category.id }) { item ->
                                CategoryCard(
                                    item = item,
                                    parentName = item.category.parentId
                                        ?.let(categoriesById::get)?.name,
                                    canUpdate = canUpdate,
                                    canDeactivate = canDeactivate,
                                    isBusy = operationCategoryId == item.category.id,
                                    actionsEnabled = operationCategoryId == null,
                                    onEdit = { editorMode = CategoryEditorMode.Edit(item.category) },
                                    onDeactivate = {
                                        actionError = null
                                        pendingDeactivation = item.category
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        editorMode?.let { mode ->
            CategoryEditorDialog(
                mode = mode,
                categories = categories,
                onDismiss = { if (operationCategoryId == null) editorMode = null },
                onSaved = { refreshKey++ }
            )
        }

        pendingDeactivation?.let { category ->
            AlertDialog(
            onDismissRequest = { if (operationCategoryId == null) pendingDeactivation = null },
            properties = DialogProperties(
                dismissOnBackPress = operationCategoryId == null,
                dismissOnClickOutside = operationCategoryId == null
            ),
            title = { Text("تعطيل الفئة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("هل تريد تعطيل الفئة «${category.name}»؟")
                    Text("التعطيل لا يحذف الفئة ولا المنتجات المرتبطة بها.")
                    if (operationCategoryId == category.id) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                            Text("جارٍ تعطيل الفئة...")
                        }
                    }
                    actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    enabled = operationCategoryId == null,
                    onClick = {
                        if (operationCategoryId != null) return@Button
                        actionError = null
                        operationCategoryId = category.id
                        scope.launch {
                            try {
                                val updated = withContext(Dispatchers.IO) {
                                    context.categoryUseCases.deactivateCategory(
                                        context.session,
                                        category.id
                                    )
                                }
                                check(updated) { "تعذر العثور على الفئة ضمن المؤسسة الحالية." }
                                pendingDeactivation = null
                                refreshKey++
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: AuthorizationException) {
                                actionError = "لا تملك صلاحية تعطيل الفئات."
                            } catch (_: Exception) {
                                actionError = "تعذر تعطيل الفئة. لم تتغير حالتها."
                            } finally {
                                operationCategoryId = null
                            }
                        }
                    }
                ) { Text("تعطيل") }
            },
            dismissButton = {
                TextButton(
                    enabled = operationCategoryId == null,
                    onClick = { pendingDeactivation = null }
                ) { Text("إلغاء") }
            }
            )
        }
    }
}

@Composable
private fun CategorySummary(
    categories: List<ProductCategory>,
    isLoading: Boolean,
    loadError: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SummaryCard("إجمالي الفئات", if (isLoading || loadError) "—" else "${categories.size}", Modifier.weight(1f))
        SummaryCard(
            "النشطة",
            if (isLoading || loadError) "—" else "${categories.count(ProductCategory::isActive)}",
            Modifier.weight(1f)
        )
        SummaryCard(
            "المعطّلة",
            if (isLoading || loadError) "—" else "${categories.count { !it.isActive }}",
            Modifier.weight(1f)
        )
    }
}

@Composable
private fun SummaryCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun CategoryCard(
    item: HierarchicalCategory,
    parentName: String?,
    canUpdate: Boolean,
    canDeactivate: Boolean,
    isBusy: Boolean,
    actionsEnabled: Boolean,
    onEdit: () -> Unit,
    onDeactivate: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (item.depth * 20).dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        item.category.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        if (item.category.isActive) "نشطة" else "معطّلة",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (item.category.isActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
                item.category.code?.let {
                    Text("الكود: $it", style = MaterialTheme.typography.bodySmall)
                }
                if (parentName != null) {
                    Text(
                        "الفئة الأب: $parentName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (isBusy) CircularProgressIndicator(strokeWidth = 2.dp)
            if (canUpdate) {
                TextButton(enabled = actionsEnabled, onClick = onEdit) { Text("تعديل") }
            }
            if (canDeactivate && item.category.isActive) {
                TextButton(enabled = actionsEnabled, onClick = onDeactivate) { Text("تعطيل") }
            }
        }
    }
}

@Composable
private fun CategoryLoadingState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator()
        Text("جارٍ تحميل الفئات...")
    }
}

@Composable
private fun CategoryLoadError(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("تعذر تحميل الفئات.", color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry) { Text("إعادة المحاولة") }
    }
}

@Composable
private fun CategoryEmptyState(message: String) {
    Text(
        message,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge
    )
}

@Composable
private fun CategoryEditorDialog(
    mode: CategoryEditorMode,
    categories: List<ProductCategory>,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalAuthenticatedProductContext.current
    val scope = rememberCoroutineScope()
    val editingCategory = (mode as? CategoryEditorMode.Edit)?.category
    var name by remember(mode) { mutableStateOf(editingCategory?.name.orEmpty()) }
    var code by remember(mode) { mutableStateOf(editingCategory?.code.orEmpty()) }
    var parentId by remember(mode) { mutableStateOf(editingCategory?.parentId) }
    var parentMenuExpanded by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val forbiddenParentIds = remember(categories, editingCategory?.id) {
        editingCategory?.let { descendantsOf(it.id, categories) + it.id }.orEmpty()
    }
    val currentParent = categories.firstOrNull { it.id == editingCategory?.parentId }
    val parentOptions = categories.filter { category ->
        category.id !in forbiddenParentIds &&
            (category.isActive || category.id == currentParent?.id)
    }
    val parentLabel = when {
        parentId == null -> "بدون فئة أب"
        else -> parentOptions.firstOrNull { it.id == parentId }?.name
            ?: "الفئة الأب الحالية غير متاحة"
    }
    val parentSelectionValid = parentId == null ||
        parentOptions.any { it.id == parentId }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !isSaving, dismissOnClickOutside = !isSaving),
        title = { Text(if (editingCategory == null) "إضافة فئة" else "تعديل الفئة") },
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
                    label = { Text("اسم الفئة *") },
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
                    label = { Text("كود الفئة (اختياري)") },
                    singleLine = true
                )
                Box {
                    OutlinedTextField(
                        value = parentLabel,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("الفئة الأب (اختياري)") },
                        trailingIcon = {
                            TextButton(
                                enabled = !isSaving,
                                onClick = { parentMenuExpanded = true }
                            ) { Text("اختيار") }
                        },
                        isError = !parentSelectionValid
                    )
                    DropdownMenu(
                        expanded = parentMenuExpanded,
                        onDismissRequest = { parentMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("بدون فئة أب") },
                            onClick = {
                                parentId = null
                                parentMenuExpanded = false
                                saveError = null
                            }
                        )
                        parentOptions.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.name) },
                                onClick = {
                                    parentId = category.id
                                    parentMenuExpanded = false
                                    saveError = null
                                }
                            )
                        }
                    }
                }
                if (!parentSelectionValid) {
                    Text(
                        "الفئة الأب الحالية لم تعد متاحة. اختر فئة أخرى أو أزلها.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                saveError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isSaving,
                onClick = {
                    if (isSaving) return@Button
                    nameError = if (name.isBlank()) "اسم الفئة مطلوب." else null
                    if (nameError != null || !parentSelectionValid) return@Button

                    isSaving = true
                    saveError = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                if (editingCategory == null) {
                                    context.categoryUseCases.createCategory(
                                        context.session,
                                        CreateProductCategory(
                                            name = name.trim(),
                                            code = code.trim().takeIf(String::isNotEmpty),
                                            parentId = parentId
                                        )
                                    )
                                } else {
                                    val updated = context.categoryUseCases.updateCategory(
                                        context.session,
                                        editingCategory.id,
                                        UpdateProductCategory(
                                            name = name.trim(),
                                            code = code.trim().takeIf(String::isNotEmpty),
                                            parentId = parentId
                                        )
                                    )
                                    check(updated) { "تعذر العثور على الفئة ضمن المؤسسة الحالية." }
                                }
                            }
                            onSaved()
                            onDismiss()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: AuthorizationException) {
                            saveError = "لا تملك صلاحية تنفيذ هذه العملية."
                            isSaving = false
                        } catch (_: Exception) {
                            saveError = "تعذر حفظ الفئة. تحقق من البيانات وحاول مرة أخرى."
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
                Text("حفظ")
            }
        },
        dismissButton = {
            TextButton(enabled = !isSaving, onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

private fun flattenCategories(categories: List<ProductCategory>): List<HierarchicalCategory> {
    val byId = categories.associateBy(ProductCategory::id)
    val children = categories.groupBy(ProductCategory::parentId)
        .mapValues { (_, entries) -> entries.sortedBy { it.name.lowercase() } }
    val visited = mutableSetOf<String>()
    val result = mutableListOf<HierarchicalCategory>()

    fun visit(category: ProductCategory, depth: Int) {
        if (!visited.add(category.id)) return
        result += HierarchicalCategory(category, depth)
        children[category.id].orEmpty().forEach { child ->
            visit(child, depth + 1)
        }
    }

    val roots = categories
        .filter { it.parentId == null || it.parentId !in byId }
        .sortedBy { it.name.lowercase() }
    roots.forEach { visit(it, 0) }
    categories.sortedBy { it.name.lowercase() }.forEach { category ->
        if (category.id !in visited) visit(category, 0)
    }
    return result
}

private fun descendantsOf(categoryId: String, categories: List<ProductCategory>): Set<String> {
    val descendants = mutableSetOf<String>()
    var frontier = setOf(categoryId)
    while (frontier.isNotEmpty()) {
        val next = categories
            .asSequence()
            .filter { it.parentId in frontier && it.id !in descendants }
            .map(ProductCategory::id)
            .toSet()
        if (next.isEmpty()) break
        descendants += next
        frontier = next
    }
    return descendants
}
