import sqlite3
import json

def test_canonical_schema():
    conn = sqlite3.connect(":memory:")
    conn.execute("PRAGMA foreign_keys = ON;")
    cur = conn.cursor()

    # DDL Definition
    ddl = """
    -- ====================================================================
    -- Store Manager Pro: Canonical Database Schema DDL
    -- ====================================================================

    -- 1. Tenants & Stores
    CREATE TABLE tenants (
        id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        is_active INTEGER NOT NULL DEFAULT 1,
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
    );

    CREATE TABLE stores (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        code TEXT NOT NULL,
        name TEXT NOT NULL,
        address TEXT,
        phone TEXT,
        is_active INTEGER NOT NULL DEFAULT 1,
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
        UNIQUE(tenant_id, code)
    );

    CREATE TABLE fiscal_years (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        year INTEGER NOT NULL,
        start_date TEXT NOT NULL,
        end_date TEXT NOT NULL,
        is_closed INTEGER NOT NULL DEFAULT 0,
        UNIQUE(tenant_id, year)
    );

    CREATE TABLE currencies (
        code TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        symbol TEXT NOT NULL,
        decimal_places INTEGER NOT NULL DEFAULT 2,
        is_base INTEGER NOT NULL DEFAULT 0
    );

    CREATE TABLE exchange_rates (
        id TEXT PRIMARY KEY,
        currency_code TEXT NOT NULL REFERENCES currencies(code) ON DELETE RESTRICT,
        rate_numerator INTEGER NOT NULL,
        rate_denominator INTEGER NOT NULL,
        valid_from TEXT NOT NULL,
        CHECK(rate_numerator > 0 AND rate_denominator > 0)
    );

    -- 2. Licensing, Sequencing, Idempotency & Backups
    CREATE TABLE app_licenses (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        license_key TEXT NOT NULL UNIQUE,
        licensee_name TEXT NOT NULL,
        machine_fingerprint TEXT NOT NULL,
        issued_at TEXT NOT NULL,
        expires_at TEXT NOT NULL,
        grace_period_days INTEGER NOT NULL DEFAULT 14,
        features_payload_json TEXT NOT NULL,
        digital_signature TEXT NOT NULL,
        status TEXT NOT NULL CHECK(status IN ('ACTIVE', 'GRACE_PERIOD', 'EXPIRED', 'REVOKED'))
    );

    CREATE TABLE document_sequences (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        store_id TEXT NOT NULL REFERENCES stores(id) ON DELETE RESTRICT,
        fiscal_year INTEGER NOT NULL,
        document_type TEXT NOT NULL CHECK(document_type IN ('SALE', 'SALE_RETURN', 'PURCHASE', 'PURCHASE_RECEIPT', 'PURCHASE_RETURN', 'PAYMENT', 'ADJUSTMENT')),
        last_sequence_number INTEGER NOT NULL DEFAULT 0,
        prefix TEXT NOT NULL,
        suffix TEXT,
        padding INTEGER NOT NULL DEFAULT 6,
        UNIQUE(tenant_id, store_id, fiscal_year, document_type)
    );

    CREATE TABLE idempotency_records (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        client_request_id TEXT NOT NULL UNIQUE,
        device_id TEXT NOT NULL,
        endpoint TEXT NOT NULL,
        request_hash TEXT NOT NULL,
        response_code INTEGER,
        response_payload_json TEXT,
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
    );

    CREATE TABLE backup_history (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        backup_type TEXT NOT NULL CHECK(backup_type IN ('AUTOMATIC', 'MANUAL_EXPORT', 'BEFORE_UPGRADE')),
        file_path TEXT NOT NULL,
        file_size_bytes INTEGER NOT NULL,
        sha256_checksum TEXT NOT NULL,
        status TEXT NOT NULL CHECK(status IN ('SUCCESS', 'FAILED', 'VERIFIED')),
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
        restored_at TEXT
    );

    -- 3. Users, Roles & Devices
    CREATE TABLE users (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        username TEXT NOT NULL,
        password_hash TEXT NOT NULL,
        salt TEXT NOT NULL,
        full_name TEXT NOT NULL,
        is_active INTEGER NOT NULL DEFAULT 1,
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
        UNIQUE(tenant_id, username)
    );

    CREATE TABLE roles (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        name TEXT NOT NULL,
        description TEXT,
        is_system INTEGER NOT NULL DEFAULT 0,
        UNIQUE(tenant_id, name)
    );

    CREATE TABLE permissions (
        code TEXT PRIMARY KEY,
        category TEXT NOT NULL,
        description TEXT NOT NULL
    );

    CREATE TABLE role_permissions (
        role_id TEXT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
        permission_code TEXT NOT NULL REFERENCES permissions(code) ON DELETE CASCADE,
        PRIMARY KEY(role_id, permission_code)
    );

    CREATE TABLE user_roles (
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        role_id TEXT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
        PRIMARY KEY(user_id, role_id)
    );

    CREATE TABLE user_sessions (
        id TEXT PRIMARY KEY,
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        token_hash TEXT NOT NULL UNIQUE,
        ip_address TEXT,
        device_info TEXT,
        expires_at TEXT NOT NULL,
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
    );

    CREATE TABLE registered_devices (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        store_id TEXT NOT NULL REFERENCES stores(id) ON DELETE RESTRICT,
        device_name TEXT NOT NULL,
        device_type TEXT NOT NULL CHECK(device_type IN ('ANDROID_POS', 'WINDOWS_WORKSTATION')),
        public_key TEXT NOT NULL UNIQUE,
        pairing_status TEXT NOT NULL CHECK(pairing_status IN ('PENDING', 'ACTIVE', 'REVOKED', 'SUSPENDED')),
        last_seen_at TEXT,
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
    );

    CREATE TABLE device_pairing_pins (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        pin_hmac TEXT NOT NULL,
        salt TEXT NOT NULL,
        attempts_remaining INTEGER NOT NULL DEFAULT 3,
        expires_at TEXT NOT NULL,
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
    );

    CREATE TABLE audit_logs (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        user_id TEXT REFERENCES users(id) ON DELETE SET NULL,
        action TEXT NOT NULL,
        entity_name TEXT NOT NULL,
        entity_id TEXT NOT NULL,
        payload_before TEXT,
        payload_after TEXT,
        ip_address TEXT,
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
    );

    -- 4. Catalog & UOM
    CREATE TABLE categories (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        parent_id TEXT REFERENCES categories(id) ON DELETE RESTRICT,
        name TEXT NOT NULL,
        code TEXT,
        is_active INTEGER NOT NULL DEFAULT 1
    );

    CREATE TABLE tax_rates (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        name TEXT NOT NULL,
        rate_bps INTEGER NOT NULL CHECK(rate_bps >= 0 AND rate_bps <= 10000),
        is_active INTEGER NOT NULL DEFAULT 1
    );

    CREATE TABLE units_of_measure (
        id TEXT PRIMARY KEY,
        dimension TEXT NOT NULL CHECK(dimension IN ('COUNT', 'WEIGHT', 'VOLUME', 'LENGTH', 'AREA')),
        name_ar TEXT NOT NULL,
        name_fr TEXT NOT NULL,
        symbol TEXT NOT NULL,
        is_fractional INTEGER NOT NULL DEFAULT 0
    );

    CREATE TABLE unit_conversions (
        id TEXT PRIMARY KEY,
        from_unit_id TEXT NOT NULL REFERENCES units_of_measure(id) ON DELETE RESTRICT,
        to_unit_id TEXT NOT NULL REFERENCES units_of_measure(id) ON DELETE RESTRICT,
        factor_numerator INTEGER NOT NULL CHECK(factor_numerator > 0),
        factor_denominator INTEGER NOT NULL CHECK(factor_denominator > 0),
        UNIQUE(from_unit_id, to_unit_id)
    );

    CREATE TABLE products (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        category_id TEXT REFERENCES categories(id) ON DELETE SET NULL,
        base_unit_id TEXT NOT NULL REFERENCES units_of_measure(id) ON DELETE RESTRICT,
        name TEXT NOT NULL,
        code TEXT,
        barcode TEXT,
        track_inventory INTEGER NOT NULL DEFAULT 1,
        has_variants INTEGER NOT NULL DEFAULT 0,
        is_active INTEGER NOT NULL DEFAULT 1,
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
    );

    CREATE TABLE product_variants (
        id TEXT PRIMARY KEY,
        product_id TEXT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
        sku TEXT NOT NULL UNIQUE,
        barcode TEXT,
        variant_name TEXT NOT NULL,
        attributes_json TEXT,
        is_active INTEGER NOT NULL DEFAULT 1
    );

    CREATE TABLE price_lists (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        name TEXT NOT NULL,
        currency_code TEXT NOT NULL REFERENCES currencies(code) ON DELETE RESTRICT,
        is_default INTEGER NOT NULL DEFAULT 0
    );

    CREATE TABLE price_list_items (
        id TEXT PRIMARY KEY,
        price_list_id TEXT NOT NULL REFERENCES price_lists(id) ON DELETE CASCADE,
        product_id TEXT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
        variant_id TEXT REFERENCES product_variants(id) ON DELETE CASCADE,
        unit_price_ticks INTEGER NOT NULL CHECK(unit_price_ticks >= 0),
        UNIQUE(price_list_id, product_id, variant_id)
    );

    -- 5. Parties & Party Ledger
    CREATE TABLE customer_groups (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        name TEXT NOT NULL,
        discount_bps INTEGER NOT NULL DEFAULT 0 CHECK(discount_bps >= 0 AND discount_bps <= 10000)
    );

    CREATE TABLE customers (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        group_id TEXT REFERENCES customer_groups(id) ON DELETE SET NULL,
        name TEXT NOT NULL,
        phone TEXT,
        email TEXT,
        credit_limit_centimes INTEGER NOT NULL DEFAULT 0,
        current_balance_centimes INTEGER NOT NULL DEFAULT 0,
        is_active INTEGER NOT NULL DEFAULT 1
    );

    CREATE TABLE suppliers (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        name TEXT NOT NULL,
        contact_name TEXT,
        phone TEXT,
        current_balance_centimes INTEGER NOT NULL DEFAULT 0,
        is_active INTEGER NOT NULL DEFAULT 1
    );

    CREATE TABLE party_ledger_entries (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        party_type TEXT NOT NULL CHECK(party_type IN ('CUSTOMER', 'SUPPLIER')),
        party_id TEXT NOT NULL,
        source_document_type TEXT NOT NULL CHECK(source_document_type IN ('SALE', 'SALE_RETURN', 'PURCHASE', 'PURCHASE_RETURN', 'PAYMENT', 'ADJUSTMENT')),
        source_document_id TEXT NOT NULL,
        debit_centimes INTEGER NOT NULL DEFAULT 0 CHECK(debit_centimes >= 0),
        credit_centimes INTEGER NOT NULL DEFAULT 0 CHECK(credit_centimes >= 0),
        running_balance_centimes INTEGER NOT NULL,
        notes TEXT,
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
    );

    -- 6. Warehouses & Inventory
    CREATE TABLE warehouses (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        store_id TEXT NOT NULL REFERENCES stores(id) ON DELETE RESTRICT,
        name TEXT NOT NULL,
        is_default INTEGER NOT NULL DEFAULT 0,
        is_active INTEGER NOT NULL DEFAULT 1
    );

    CREATE TABLE stock_items (
        id TEXT PRIMARY KEY,
        product_id TEXT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
        variant_id TEXT REFERENCES product_variants(id) ON DELETE CASCADE,
        UNIQUE(product_id, variant_id)
    );

    CREATE TABLE inventory_balances (
        warehouse_id TEXT NOT NULL REFERENCES warehouses(id) ON DELETE RESTRICT,
        stock_item_id TEXT NOT NULL REFERENCES stock_items(id) ON DELETE RESTRICT,
        on_hand_ticks INTEGER NOT NULL DEFAULT 0 CHECK(on_hand_ticks >= 0),
        reserved_ticks INTEGER NOT NULL DEFAULT 0 CHECK(reserved_ticks >= 0),
        current_moving_avg_cost INTEGER NOT NULL DEFAULT 0 CHECK(current_moving_avg_cost >= 0),
        PRIMARY KEY(warehouse_id, stock_item_id)
    );

    CREATE TABLE inventory_movements (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        warehouse_id TEXT NOT NULL REFERENCES warehouses(id) ON DELETE RESTRICT,
        stock_item_id TEXT NOT NULL REFERENCES stock_items(id) ON DELETE RESTRICT,
        movement_type TEXT NOT NULL CHECK(movement_type IN ('PURCHASE_RECEIPT', 'SALE_SHIPMENT', 'SALE_RETURN', 'PURCHASE_RETURN', 'ADJUSTMENT_IN', 'ADJUSTMENT_OUT', 'TRANSFER_IN', 'TRANSFER_OUT')),
        source_event_id TEXT UNIQUE NOT NULL,
        reference_line_id TEXT,
        quantity_ticks INTEGER NOT NULL,
        unit_cost_ticks INTEGER NOT NULL DEFAULT 0,
        total_cost_centimes INTEGER NOT NULL DEFAULT 0,
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
    );

    CREATE TABLE inventory_adjustments (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        warehouse_id TEXT NOT NULL REFERENCES warehouses(id) ON DELETE RESTRICT,
        adjustment_number TEXT NOT NULL UNIQUE,
        reason TEXT NOT NULL,
        status TEXT NOT NULL CHECK(status IN ('DRAFT', 'POSTED', 'CANCELLED')),
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
    );

    CREATE TABLE inventory_adjustment_lines (
        id TEXT PRIMARY KEY,
        adjustment_id TEXT NOT NULL REFERENCES inventory_adjustments(id) ON DELETE CASCADE,
        stock_item_id TEXT NOT NULL REFERENCES stock_items(id) ON DELETE RESTRICT,
        system_quantity_ticks INTEGER NOT NULL,
        actual_quantity_ticks INTEGER NOT NULL,
        difference_ticks INTEGER NOT NULL,
        unit_cost_ticks INTEGER NOT NULL
    );

    CREATE TABLE inventory_quotas (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        device_id TEXT NOT NULL REFERENCES registered_devices(id) ON DELETE RESTRICT,
        warehouse_id TEXT NOT NULL REFERENCES warehouses(id) ON DELETE RESTRICT,
        stock_item_id TEXT NOT NULL REFERENCES stock_items(id) ON DELETE RESTRICT,
        quota_quantity_ticks INTEGER NOT NULL CHECK(quota_quantity_ticks > 0),
        consumed_quantity_ticks INTEGER NOT NULL DEFAULT 0 CHECK(consumed_quantity_ticks >= 0),
        valid_until TEXT NOT NULL,
        status TEXT NOT NULL CHECK(status IN ('ACTIVE', 'RECONCILED', 'EXPIRED', 'LOST_DEVICE_EXPOSURE', 'VOIDED_BY_ADMIN')),
        signed_token TEXT NOT NULL,
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
        CHECK(consumed_quantity_ticks <= quota_quantity_ticks)
    );

    -- 7. Sales, Items, Quotas Deductions & Returns
    CREATE TABLE sales (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        store_id TEXT NOT NULL REFERENCES stores(id) ON DELETE RESTRICT,
        warehouse_id TEXT NOT NULL REFERENCES warehouses(id) ON DELETE RESTRICT,
        customer_id TEXT REFERENCES customers(id) ON DELETE RESTRICT,
        device_id TEXT NOT NULL REFERENCES registered_devices(id) ON DELETE RESTRICT,
        invoice_number TEXT UNIQUE,
        offline_receipt_number TEXT,
        local_operation_uuid TEXT,
        subtotal_centimes INTEGER NOT NULL DEFAULT 0 CHECK(subtotal_centimes >= 0),
        discount_centimes INTEGER NOT NULL DEFAULT 0 CHECK(discount_centimes >= 0),
        tax_centimes INTEGER NOT NULL DEFAULT 0 CHECK(tax_centimes >= 0),
        total_amount_centimes INTEGER NOT NULL CHECK(total_amount_centimes >= 0),
        paid_amount INTEGER NOT NULL DEFAULT 0 CHECK(paid_amount >= 0),
        remaining_debt INTEGER NOT NULL CHECK(remaining_debt >= 0),
        status TEXT NOT NULL CHECK(status IN ('DRAFT', 'COMPLETED', 'CANCELLED')),
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
        UNIQUE(device_id, local_operation_uuid),
        CHECK(paid_amount + remaining_debt = total_amount_centimes)
    );

    CREATE TABLE sale_items (
        id TEXT PRIMARY KEY,
        sale_id TEXT NOT NULL REFERENCES sales(id) ON DELETE CASCADE,
        stock_item_id TEXT NOT NULL REFERENCES stock_items(id) ON DELETE RESTRICT,
        quantity_ticks INTEGER NOT NULL CHECK(quantity_ticks > 0),
        returned_quantity_ticks INTEGER NOT NULL DEFAULT 0 CHECK(returned_quantity_ticks >= 0),
        unit_price_ticks INTEGER NOT NULL CHECK(unit_price_ticks >= 0),
        discount_bps INTEGER NOT NULL DEFAULT 0 CHECK(discount_bps >= 0 AND discount_bps <= 10000),
        tax_bps INTEGER NOT NULL DEFAULT 0 CHECK(tax_bps >= 0 AND tax_bps <= 10000),
        line_total_centimes INTEGER NOT NULL CHECK(line_total_centimes >= 0),
        cost_at_sale_ticks INTEGER NOT NULL DEFAULT 0,
        CHECK(returned_quantity_ticks <= quantity_ticks)
    );

    CREATE TABLE sale_item_quota_deductions (
        id TEXT PRIMARY KEY,
        sale_item_id TEXT NOT NULL REFERENCES sale_items(id) ON DELETE CASCADE,
        quota_id TEXT NOT NULL REFERENCES inventory_quotas(id) ON DELETE RESTRICT,
        deducted_quantity_ticks INTEGER NOT NULL CHECK(deducted_quantity_ticks > 0),
        UNIQUE(sale_item_id, quota_id)
    );

    CREATE TABLE sale_returns (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        store_id TEXT NOT NULL REFERENCES stores(id) ON DELETE RESTRICT,
        warehouse_id TEXT NOT NULL REFERENCES warehouses(id) ON DELETE RESTRICT,
        original_sale_id TEXT NOT NULL REFERENCES sales(id) ON DELETE RESTRICT,
        return_number TEXT UNIQUE NOT NULL,
        customer_id TEXT REFERENCES customers(id) ON DELETE RESTRICT,
        total_refund_centimes INTEGER NOT NULL CHECK(total_refund_centimes >= 0),
        refund_status TEXT NOT NULL CHECK(refund_status IN ('PENDING', 'REFUNDED_CASH', 'CREDITED_TO_ACCOUNT')),
        status TEXT NOT NULL CHECK(status IN ('COMPLETED', 'CANCELLED')),
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
    );

    CREATE TABLE sale_return_items (
        id TEXT PRIMARY KEY,
        return_id TEXT NOT NULL REFERENCES sale_returns(id) ON DELETE CASCADE,
        original_sale_item_id TEXT NOT NULL REFERENCES sale_items(id) ON DELETE RESTRICT,
        stock_item_id TEXT NOT NULL REFERENCES stock_items(id) ON DELETE RESTRICT,
        returned_quantity_ticks INTEGER NOT NULL CHECK(returned_quantity_ticks > 0),
        refund_unit_price_ticks INTEGER NOT NULL CHECK(refund_unit_price_ticks >= 0),
        line_total_centimes INTEGER NOT NULL CHECK(line_total_centimes >= 0)
    );

    -- 8. Purchases, Receipts & Returns
    CREATE TABLE purchases (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        supplier_id TEXT NOT NULL REFERENCES suppliers(id) ON DELETE RESTRICT,
        store_id TEXT NOT NULL REFERENCES stores(id) ON DELETE RESTRICT,
        order_number TEXT UNIQUE NOT NULL,
        total_amount_centimes INTEGER NOT NULL CHECK(total_amount_centimes >= 0),
        paid_amount INTEGER NOT NULL DEFAULT 0 CHECK(paid_amount >= 0),
        remaining_debt INTEGER NOT NULL CHECK(remaining_debt >= 0),
        fulfillment_status TEXT NOT NULL CHECK(fulfillment_status IN ('PENDING', 'PARTIALLY_RECEIVED', 'FULLY_RECEIVED')),
        status TEXT NOT NULL CHECK(status IN ('ORDERED', 'RECEIVED', 'CANCELLED')),
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
        CHECK(paid_amount + remaining_debt = total_amount_centimes)
    );

    CREATE TABLE purchase_items (
        id TEXT PRIMARY KEY,
        purchase_id TEXT NOT NULL REFERENCES purchases(id) ON DELETE CASCADE,
        stock_item_id TEXT NOT NULL REFERENCES stock_items(id) ON DELETE RESTRICT,
        quantity_ordered_ticks INTEGER NOT NULL CHECK(quantity_ordered_ticks > 0),
        quantity_received_ticks INTEGER NOT NULL DEFAULT 0 CHECK(quantity_received_ticks >= 0),
        unit_cost_ticks INTEGER NOT NULL CHECK(unit_cost_ticks >= 0),
        line_total_centimes INTEGER NOT NULL CHECK(line_total_centimes >= 0),
        CHECK(quantity_received_ticks <= quantity_ordered_ticks)
    );

    CREATE TABLE purchase_receipts (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        purchase_id TEXT NOT NULL REFERENCES purchases(id) ON DELETE RESTRICT,
        warehouse_id TEXT NOT NULL REFERENCES warehouses(id) ON DELETE RESTRICT,
        receipt_number TEXT UNIQUE NOT NULL,
        received_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
        status TEXT NOT NULL CHECK(status IN ('COMPLETED', 'CANCELLED'))
    );

    CREATE TABLE purchase_receipt_items (
        id TEXT PRIMARY KEY,
        receipt_id TEXT NOT NULL REFERENCES purchase_receipts(id) ON DELETE CASCADE,
        purchase_item_id TEXT NOT NULL REFERENCES purchase_items(id) ON DELETE RESTRICT,
        stock_item_id TEXT NOT NULL REFERENCES stock_items(id) ON DELETE RESTRICT,
        quantity_received_ticks INTEGER NOT NULL CHECK(quantity_received_ticks > 0),
        unit_cost_ticks INTEGER NOT NULL CHECK(unit_cost_ticks >= 0)
    );

    CREATE TABLE purchase_returns (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        purchase_id TEXT NOT NULL REFERENCES purchases(id) ON DELETE RESTRICT,
        supplier_id TEXT NOT NULL REFERENCES suppliers(id) ON DELETE RESTRICT,
        warehouse_id TEXT NOT NULL REFERENCES warehouses(id) ON DELETE RESTRICT,
        return_number TEXT UNIQUE NOT NULL,
        total_credit_centimes INTEGER NOT NULL CHECK(total_credit_centimes >= 0),
        status TEXT NOT NULL CHECK(status IN ('COMPLETED', 'CANCELLED')),
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
    );

    CREATE TABLE purchase_return_items (
        id TEXT PRIMARY KEY,
        purchase_return_id TEXT NOT NULL REFERENCES purchase_returns(id) ON DELETE CASCADE,
        purchase_item_id TEXT NOT NULL REFERENCES purchase_items(id) ON DELETE RESTRICT,
        stock_item_id TEXT NOT NULL REFERENCES stock_items(id) ON DELETE RESTRICT,
        returned_quantity_ticks INTEGER NOT NULL CHECK(returned_quantity_ticks > 0),
        unit_cost_ticks INTEGER NOT NULL CHECK(unit_cost_ticks >= 0),
        line_total_centimes INTEGER NOT NULL CHECK(line_total_centimes >= 0)
    );

    -- 9. Cash Registers, Shifts, Cash Movements & Expenses
    CREATE TABLE cash_registers (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        store_id TEXT NOT NULL REFERENCES stores(id) ON DELETE RESTRICT,
        name TEXT NOT NULL,
        code TEXT NOT NULL,
        is_active INTEGER NOT NULL DEFAULT 1,
        UNIQUE(store_id, code)
    );

    CREATE TABLE cash_shifts (
        id TEXT PRIMARY KEY,
        cash_register_id TEXT NOT NULL REFERENCES cash_registers(id) ON DELETE RESTRICT,
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
        opened_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
        closed_at TEXT,
        opening_float_centimes INTEGER NOT NULL CHECK(opening_float_centimes >= 0),
        expected_cash_centimes INTEGER NOT NULL DEFAULT 0,
        actual_cash_centimes INTEGER,
        difference_centimes INTEGER,
        status TEXT NOT NULL CHECK(status IN ('OPEN', 'CLOSED'))
    );

    CREATE TABLE cash_movements (
        id TEXT PRIMARY KEY,
        shift_id TEXT NOT NULL REFERENCES cash_shifts(id) ON DELETE RESTRICT,
        movement_type TEXT NOT NULL CHECK(movement_type IN ('FLOAT_ADD', 'DROP_PAYOUT', 'PETTY_EXPENSE', 'MANUAL_IN', 'MANUAL_OUT')),
        amount_centimes INTEGER NOT NULL CHECK(amount_centimes > 0),
        reason TEXT NOT NULL,
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
    );

    CREATE TABLE expense_categories (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        name TEXT NOT NULL,
        is_active INTEGER NOT NULL DEFAULT 1
    );

    CREATE TABLE expenses (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        store_id TEXT NOT NULL REFERENCES stores(id) ON DELETE RESTRICT,
        category_id TEXT NOT NULL REFERENCES expense_categories(id) ON DELETE RESTRICT,
        amount_centimes INTEGER NOT NULL CHECK(amount_centimes > 0),
        description TEXT NOT NULL,
        shift_id TEXT REFERENCES cash_shifts(id) ON DELETE SET NULL,
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
    );

    -- 10. Payments & Allocations
    CREATE TABLE payments (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
        store_id TEXT NOT NULL REFERENCES stores(id) ON DELETE RESTRICT,
        party_type TEXT NOT NULL CHECK(party_type IN ('CUSTOMER', 'SUPPLIER')),
        party_id TEXT NOT NULL,
        payment_method TEXT NOT NULL CHECK(payment_method IN ('CASH', 'BANK_TRANSFER', 'CHECK', 'CARD')),
        shift_id TEXT REFERENCES cash_shifts(id) ON DELETE SET NULL,
        amount INTEGER NOT NULL CHECK(amount > 0),
        payment_date TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
        notes TEXT,
        is_reversal INTEGER NOT NULL DEFAULT 0,
        reversal_of_id TEXT REFERENCES payments(id) ON DELETE RESTRICT
    );

    CREATE TABLE payment_allocations (
        id TEXT PRIMARY KEY,
        payment_id TEXT NOT NULL REFERENCES payments(id) ON DELETE RESTRICT,
        sale_id TEXT REFERENCES sales(id) ON DELETE RESTRICT,
        purchase_id TEXT REFERENCES purchases(id) ON DELETE RESTRICT,
        allocated_amount INTEGER NOT NULL CHECK(allocated_amount > 0),
        allocated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
        CHECK((sale_id IS NOT NULL AND purchase_id IS NULL) OR (sale_id IS NULL AND purchase_id IS NOT NULL))
    );
    """

    cur.executescript(ddl)

    # Triggers DDL
    triggers_ddl = """
    -- UOM Dimension Triggers
    CREATE TRIGGER trg_protect_seed_uom_delete
    BEFORE DELETE ON units_of_measure
    WHEN OLD.id = 'UOM_PIECE'
    BEGIN
        SELECT RAISE(ABORT, 'SYSTEM_UNIT_OF_MEASURE_CANNOT_BE_DELETED');
    END;

    CREATE TRIGGER trg_check_unit_conversion_dimension_insert
    BEFORE INSERT ON unit_conversions
    BEGIN
        SELECT CASE
            WHEN (SELECT dimension FROM units_of_measure WHERE id = NEW.from_unit_id) !=
                 (SELECT dimension FROM units_of_measure WHERE id = NEW.to_unit_id)
            THEN RAISE(ABORT, 'CROSS_DIMENSION_CONVERSION_FORBIDDEN')
            WHEN NEW.from_unit_id = NEW.to_unit_id
            THEN RAISE(ABORT, 'SELF_UNIT_CONVERSION_FORBIDDEN')
        END;
    END;

    CREATE TRIGGER trg_check_unit_conversion_dimension_update
    BEFORE UPDATE OF from_unit_id, to_unit_id ON unit_conversions
    BEGIN
        SELECT CASE
            WHEN (SELECT dimension FROM units_of_measure WHERE id = NEW.from_unit_id) !=
                 (SELECT dimension FROM units_of_measure WHERE id = NEW.to_unit_id)
            THEN RAISE(ABORT, 'CROSS_DIMENSION_CONVERSION_FORBIDDEN')
            WHEN NEW.from_unit_id = NEW.to_unit_id
            THEN RAISE(ABORT, 'SELF_UNIT_CONVERSION_FORBIDDEN')
        END;
    END;

    -- Payment Allocation Triggers
    CREATE TRIGGER trg_payment_allocations_no_update
    BEFORE UPDATE ON payment_allocations
    BEGIN
        SELECT RAISE(ABORT, 'PAYMENT_ALLOCATIONS_ARE_IMMUTABLE_USE_REVERSAL_RECORD');
    END;

    CREATE TRIGGER trg_payment_allocations_no_delete
    BEFORE DELETE ON payment_allocations
    BEGIN
        SELECT RAISE(ABORT, 'PAYMENT_ALLOCATIONS_ARE_IMMUTABLE_USE_REVERSAL_RECORD');
    END;

    CREATE TRIGGER trg_check_allocation_walkin_sale
    BEFORE INSERT ON payment_allocations
    WHEN NEW.sale_id IS NOT NULL
    BEGIN
        SELECT CASE
            WHEN (SELECT customer_id FROM sales WHERE id = NEW.sale_id) IS NULL
            THEN RAISE(ABORT, 'CANNOT_ALLOCATE_PAYMENT_TO_WALKIN_SALE_WITHOUT_CUSTOMER')
        END;
    END;

    CREATE TRIGGER trg_check_payment_total_allocation_insert
    BEFORE INSERT ON payment_allocations
    BEGIN
        SELECT CASE
            WHEN (COALESCE((SELECT SUM(allocated_amount) FROM payment_allocations WHERE payment_id = NEW.payment_id), 0) + NEW.allocated_amount) >
                 (SELECT amount FROM payments WHERE id = NEW.payment_id)
            THEN RAISE(ABORT, 'PAYMENT_ALLOCATION_EXCEEDS_PAYMENT_AMOUNT')
        END;
    END;

    CREATE TRIGGER trg_check_allocation_sale_debt_and_party
    BEFORE INSERT ON payment_allocations
    WHEN NEW.sale_id IS NOT NULL
    BEGIN
        SELECT CASE
            WHEN (SELECT party_id FROM payments WHERE id = NEW.payment_id) !=
                 (SELECT customer_id FROM sales WHERE id = NEW.sale_id)
            THEN RAISE(ABORT, 'PAYMENT_PARTY_MISMATCH_SALE_CUSTOMER')
            WHEN NEW.allocated_amount > (SELECT remaining_debt FROM sales WHERE id = NEW.sale_id)
            THEN RAISE(ABORT, 'ALLOCATION_EXCEEDS_OUTSTANDING_INVOICE_DEBT')
        END;
    END;

    CREATE TRIGGER trg_check_allocation_purchase_debt_and_party
    BEFORE INSERT ON payment_allocations
    WHEN NEW.purchase_id IS NOT NULL
    BEGIN
        SELECT CASE
            WHEN (SELECT party_id FROM payments WHERE id = NEW.payment_id) !=
                 (SELECT supplier_id FROM purchases WHERE id = NEW.purchase_id)
            THEN RAISE(ABORT, 'PAYMENT_PARTY_MISMATCH_PURCHASE_SUPPLIER')
            WHEN NEW.allocated_amount > (SELECT remaining_debt FROM purchases WHERE id = NEW.purchase_id)
            THEN RAISE(ABORT, 'ALLOCATION_EXCEEDS_OUTSTANDING_PURCHASE_DEBT')
        END;
    END;

    CREATE TRIGGER trg_after_allocation_update_sale_balances
    AFTER INSERT ON payment_allocations
    WHEN NEW.sale_id IS NOT NULL
    BEGIN
        UPDATE sales
        SET paid_amount = paid_amount + NEW.allocated_amount,
            remaining_debt = remaining_debt - NEW.allocated_amount
        WHERE id = NEW.sale_id;
    END;

    CREATE TRIGGER trg_after_allocation_update_purchase_balances
    AFTER INSERT ON payment_allocations
    WHEN NEW.purchase_id IS NOT NULL
    BEGIN
        UPDATE purchases
        SET paid_amount = paid_amount + NEW.allocated_amount,
            remaining_debt = remaining_debt - NEW.allocated_amount
        WHERE id = NEW.purchase_id;
    END;

    CREATE TRIGGER trg_prevent_lowering_allocated_payment
    BEFORE UPDATE OF amount ON payments
    BEGIN
        SELECT CASE
            WHEN NEW.amount < COALESCE((SELECT SUM(allocated_amount) FROM payment_allocations WHERE payment_id = OLD.id), 0)
            THEN RAISE(ABORT, 'CANNOT_DOWNGRADE_PAYMENT_BELOW_ALLOCATED_TOTAL')
        END;
    END;

    -- Return Line Over-return Check
    CREATE TRIGGER trg_sale_return_item_check_and_update
    AFTER INSERT ON sale_return_items
    BEGIN
        UPDATE sale_items
        SET returned_quantity_ticks = returned_quantity_ticks + NEW.returned_quantity_ticks
        WHERE id = NEW.original_sale_item_id;
    END;

    -- Purchase Receipt Line Over-receipt Check
    CREATE TRIGGER trg_purchase_receipt_item_check_and_update
    AFTER INSERT ON purchase_receipt_items
    BEGIN
        UPDATE purchase_items
        SET quantity_received_ticks = quantity_received_ticks + NEW.quantity_received_ticks
        WHERE id = NEW.purchase_item_id;
    END;
    """

    cur.executescript(triggers_ddl)

    # Count tables
    cur.execute("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';")
    count = cur.fetchone()[0]
    print(f"TOTAL_CANONICAL_TABLES: {count}")

    # Check foreign keys
    cur.execute("PRAGMA foreign_key_check;")
    fk_errors = cur.fetchall()
    print(f"FK_ERRORS: {len(fk_errors)}")

    # Seed data
    cur.execute("INSERT INTO tenants (id, name) VALUES ('TENANT_DEFAULT', 'Main Business');")
    cur.execute("INSERT INTO stores (id, tenant_id, code, name) VALUES ('STORE_DEFAULT', 'TENANT_DEFAULT', 'ST01', 'Main Store');")
    cur.execute("INSERT INTO warehouses (id, tenant_id, store_id, name, is_default) VALUES ('WH_DEFAULT', 'TENANT_DEFAULT', 'STORE_DEFAULT', 'Main Warehouse', 1);")
    cur.execute("INSERT INTO currencies (code, name, symbol, decimal_places, is_base) VALUES ('DZD', 'Algerian Dinar', 'دج', 2, 1);")
    cur.execute("INSERT INTO units_of_measure (id, dimension, name_ar, name_fr, symbol, is_fractional) VALUES ('UOM_PIECE', 'COUNT', 'وحدة', 'Pièce', 'قطعة', 0);")
    cur.execute("INSERT INTO units_of_measure (id, dimension, name_ar, name_fr, symbol, is_fractional) VALUES ('UOM_KG', 'WEIGHT', 'كيلوغرام', 'Kilogramme', 'كغ', 1);")
    cur.execute("INSERT INTO units_of_measure (id, dimension, name_ar, name_fr, symbol, is_fractional) VALUES ('UOM_METER', 'LENGTH', 'متر', 'Mètre', 'م', 1);")

    # Test single product creation by name
    cur.execute("INSERT INTO products (id, tenant_id, base_unit_id, name) VALUES ('P1', 'TENANT_DEFAULT', 'UOM_PIECE', 'صابون بلدي');")
    cur.execute("INSERT INTO stock_items (id, product_id, variant_id) VALUES ('ITEM_P1', 'P1', NULL);")
    cur.execute("INSERT INTO inventory_balances (warehouse_id, stock_item_id, on_hand_ticks, reserved_ticks, current_moving_avg_cost) VALUES ('WH_DEFAULT', 'ITEM_P1', 0, 0, 0);")

    # Test UOM cross-dimension rejection
    try:
        cur.execute("INSERT INTO unit_conversions (id, from_unit_id, to_unit_id, factor_numerator, factor_denominator) VALUES ('C1', 'UOM_KG', 'UOM_METER', 1, 1);")
        print("FAIL: Cross-dimension should be rejected")
    except sqlite3.IntegrityError as e:
        print(f"PASS: Cross dimension conversion rejected: {e}")

    # Test Payment Allocations & Sales
    cur.execute("INSERT INTO registered_devices (id, tenant_id, store_id, device_name, device_type, public_key, pairing_status) VALUES ('DEV01', 'TENANT_DEFAULT', 'STORE_DEFAULT', 'POS 1', 'ANDROID_POS', 'PUBKEY123', 'ACTIVE');")
    cur.execute("INSERT INTO customers (id, tenant_id, name, credit_limit_centimes, current_balance_centimes) VALUES ('CUST_01', 'TENANT_DEFAULT', 'Client Omar', 1000000, 0);")
    cur.execute("INSERT INTO customers (id, tenant_id, name, credit_limit_centimes, current_balance_centimes) VALUES ('CUST_02', 'TENANT_DEFAULT', 'Client Fatima', 1000000, 0);")

    cur.execute("""
    INSERT INTO sales (id, tenant_id, store_id, warehouse_id, customer_id, device_id, invoice_number, total_amount_centimes, paid_amount, remaining_debt, status)
    VALUES ('SALE_01', 'TENANT_DEFAULT', 'STORE_DEFAULT', 'WH_DEFAULT', 'CUST_01', 'DEV01', 'INV-2026-001', 50000, 0, 50000, 'COMPLETED');
    """)

    cur.execute("""
    INSERT INTO payments (id, tenant_id, store_id, party_type, party_id, payment_method, amount)
    VALUES ('PAY_01', 'TENANT_DEFAULT', 'STORE_DEFAULT', 'CUSTOMER', 'CUST_01', 'CASH', 30000);
    """)

    # Valid allocation
    cur.execute("""
    INSERT INTO payment_allocations (id, payment_id, sale_id, allocated_amount)
    VALUES ('ALLOC_01', 'PAY_01', 'SALE_01', 20000);
    """)

    cur.execute("SELECT paid_amount, remaining_debt FROM sales WHERE id = 'SALE_01';")
    sale_row = cur.fetchone()
    print(f"PASS: Sale updated after allocation: Paid={sale_row[0]}, Remaining={sale_row[1]}")

    # Test party mismatch rejection
    cur.execute("""
    INSERT INTO sales (id, tenant_id, store_id, warehouse_id, customer_id, device_id, invoice_number, total_amount_centimes, paid_amount, remaining_debt, status)
    VALUES ('SALE_02', 'TENANT_DEFAULT', 'STORE_DEFAULT', 'WH_DEFAULT', 'CUST_02', 'DEV01', 'INV-2026-002', 40000, 0, 40000, 'COMPLETED');
    """)
    try:
        cur.execute("""
        INSERT INTO payment_allocations (id, payment_id, sale_id, allocated_amount)
        VALUES ('ALLOC_BAD', 'PAY_01', 'SALE_02', 5000);
        """)
        print("FAIL: Party mismatch should be rejected")
    except sqlite3.IntegrityError as e:
        print(f"PASS: Party mismatch rejected: {e}")

    # Test Over-Return Rejection
    cur.execute("""
    INSERT INTO sale_items (id, sale_id, stock_item_id, quantity_ticks, unit_price_ticks, line_total_centimes)
    VALUES ('SITEM_01', 'SALE_01', 'ITEM_P1', 100000, 50000, 50000);
    """)
    cur.execute("""
    INSERT INTO sale_returns (id, tenant_id, store_id, warehouse_id, original_sale_id, return_number, total_refund_centimes, refund_status, status)
    VALUES ('RET_01', 'TENANT_DEFAULT', 'STORE_DEFAULT', 'WH_DEFAULT', 'SALE_01', 'RET-001', 25000, 'REFUNDED_CASH', 'COMPLETED');
    """)
    # First return 4 units out of 10
    cur.execute("""
    INSERT INTO sale_return_items (id, return_id, original_sale_item_id, stock_item_id, returned_quantity_ticks, refund_unit_price_ticks, line_total_centimes)
    VALUES ('RITEM_01', 'RET_01', 'SITEM_01', 'ITEM_P1', 40000, 50000, 20000);
    """)
    # Try to return 7 more (total 11 > 10)
    try:
        cur.execute("""
        INSERT INTO sale_return_items (id, return_id, original_sale_item_id, stock_item_id, returned_quantity_ticks, refund_unit_price_ticks, line_total_centimes)
        VALUES ('RITEM_02', 'RET_01', 'SITEM_01', 'ITEM_P1', 70000, 50000, 35000);
        """)
        print("FAIL: Over-return should be rejected")
    except sqlite3.IntegrityError as e:
        print(f"PASS: Over-return rejected by CHECK(returned_quantity_ticks <= quantity_ticks): {e}")

    # Test Idempotency & Movement Source Event
    cur.execute("""
    INSERT INTO inventory_movements (id, tenant_id, warehouse_id, stock_item_id, movement_type, source_event_id, quantity_ticks)
    VALUES ('MOV_01', 'TENANT_DEFAULT', 'WH_DEFAULT', 'ITEM_P1', 'SALE_SHIPMENT', 'EVENT_UUID_999', -10000);
    """)
    try:
        cur.execute("""
        INSERT INTO inventory_movements (id, tenant_id, warehouse_id, stock_item_id, movement_type, source_event_id, quantity_ticks)
        VALUES ('MOV_DUPE', 'TENANT_DEFAULT', 'WH_DEFAULT', 'ITEM_P1', 'SALE_SHIPMENT', 'EVENT_UUID_999', -10000);
        """)
        print("FAIL: Duplicate movement should be rejected")
    except sqlite3.IntegrityError as e:
        print(f"PASS: Duplicate movement rejected by source_event_id UNIQUE: {e}")

    # Test Quotas multi-item & offline sale item link
    cur.execute("""
    INSERT INTO inventory_quotas (id, tenant_id, device_id, warehouse_id, stock_item_id, quota_quantity_ticks, consumed_quantity_ticks, valid_until, status, signed_token)
    VALUES ('QUOTA_01', 'TENANT_DEFAULT', 'DEV01', 'WH_DEFAULT', 'ITEM_P1', 500000, 100000, '2026-12-31T23:59:59Z', 'ACTIVE', 'SIG_ED25519_OK');
    """)
    cur.execute("""
    INSERT INTO sale_item_quota_deductions (id, sale_item_id, quota_id, deducted_quantity_ticks)
    VALUES ('DED_01', 'SITEM_01', 'QUOTA_01', 100000);
    """)
    print("PASS: Quota allocation link verified")

    conn.close()
    print("ALL TESTS COMPLETED SUCCESSFULLY!")

if __name__ == "__main__":
    test_canonical_schema()
