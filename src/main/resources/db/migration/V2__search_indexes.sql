-- Expression indexes that match the actual query predicates, plus the
-- keyset-pagination index. The V1 indexes were on raw columns, while the
-- queries compare lower()/coalesce() expressions, so the planner could not
-- use them.

-- Client lexical search: predicates use lower(email) % ?, lower(email) ~ ?
-- with pg_trgm; the GIN trgm opclass serves both operators on these exact
-- expressions.
DROP INDEX IF EXISTS clients_email_trgm_idx;
DROP INDEX IF EXISTS clients_first_name_trgm_idx;
DROP INDEX IF EXISTS clients_last_name_trgm_idx;
DROP INDEX IF EXISTS clients_description_trgm_idx;
CREATE INDEX clients_email_lower_trgm_idx ON clients USING gin (lower(email) gin_trgm_ops);
CREATE INDEX clients_first_name_lower_trgm_idx ON clients USING gin (lower(first_name) gin_trgm_ops);
CREATE INDEX clients_last_name_lower_trgm_idx ON clients USING gin (lower(last_name) gin_trgm_ops);
CREATE INDEX clients_description_lower_trgm_idx ON clients USING gin (lower(coalesce(description, '')) gin_trgm_ops);

-- Document lexical search over title and content.
DROP INDEX IF EXISTS documents_title_trgm_idx;
DROP INDEX IF EXISTS documents_content_trgm_idx;
CREATE INDEX documents_title_lower_trgm_idx ON documents USING gin (lower(title) gin_trgm_ops);
CREATE INDEX documents_content_lower_trgm_idx ON documents USING gin (lower(content) gin_trgm_ops);

-- Keyset pagination of a client's documents: created_at DESC, id DESC.
CREATE INDEX documents_client_created_idx ON documents (client_id, created_at DESC, id DESC);
