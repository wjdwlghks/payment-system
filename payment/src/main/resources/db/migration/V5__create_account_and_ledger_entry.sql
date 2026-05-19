CREATE TABLE account (
    id BIGINT NOT NULL AUTO_INCREMENT,
    account_type VARCHAR(40) NOT NULL,
    account_class VARCHAR(20) NOT NULL,
    merchant_id VARCHAR(100) NOT NULL,
    balance BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_type_merchant_id (account_type, merchant_id)
);

INSERT INTO account (
    account_type,
    account_class,
    merchant_id,
    balance,
    created_at,
    updated_at
) VALUES
    ('CARD_NETWORK_RECEIVABLE', 'ASSET', 'GLOBAL', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
    ('BANK_ACCOUNT', 'ASSET', 'GLOBAL', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
    ('FEE_REVENUE', 'REVENUE', 'GLOBAL', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));

CREATE TABLE ledger_posting (
    id BIGINT NOT NULL AUTO_INCREMENT,
    posting_type VARCHAR(30) NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    source_id VARCHAR(64) NOT NULL,
    total_debit BIGINT NOT NULL,
    total_credit BIGINT NOT NULL,
    posted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ledger_posting_source (posting_type, source_type, source_id),
    CONSTRAINT chk_ledger_posting_total_debit_positive CHECK (total_debit > 0),
    CONSTRAINT chk_ledger_posting_total_credit_positive CHECK (total_credit > 0),
    CONSTRAINT chk_ledger_posting_balanced CHECK (total_debit = total_credit)
);

CREATE TABLE ledger_entry (
    id BIGINT NOT NULL AUTO_INCREMENT,
    posting_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    direction VARCHAR(10) NOT NULL,
    amount BIGINT NOT NULL,
    entry_type VARCHAR(30) NOT NULL,
    posted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ledger_entry_posting_id (posting_id),
    KEY idx_ledger_entry_account_posted_at (account_id, posted_at),
    CONSTRAINT fk_ledger_entry_posting
        FOREIGN KEY (posting_id) REFERENCES ledger_posting (id),
    CONSTRAINT fk_ledger_entry_account
        FOREIGN KEY (account_id) REFERENCES account (id)
);
