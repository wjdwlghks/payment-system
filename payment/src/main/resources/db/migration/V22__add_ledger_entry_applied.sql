-- 기존 행은 account.balance에 이미 반영됐으므로 applied=TRUE
-- 이후 신규 INSERT 행은 JPA 엔티티 기본값(false)으로 삽입됨
ALTER TABLE ledger_entry ADD COLUMN applied BOOLEAN NOT NULL DEFAULT TRUE;
CREATE INDEX idx_ledger_entry_unapplied ON ledger_entry (applied, account_id);
