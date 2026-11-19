-- Display label for the acquiring bank on each settlement batch. Nullable: rows created before
-- this migration are left NULL (not backfilled), because the configured name is not known to SQL.
ALTER TABLE settlement_batches ADD COLUMN acquirer_name VARCHAR(100);
