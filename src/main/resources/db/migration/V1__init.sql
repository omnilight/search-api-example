-- Extensions: pgvector for semantic search, pg_trgm for fuzzy lexical search.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE clients (
    id           uuid PRIMARY KEY,
    first_name   text NOT NULL,
    last_name    text NOT NULL,
    email        text NOT NULL,
    description  text,
    social_links text[] NOT NULL DEFAULT '{}',
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT clients_email_key UNIQUE (email)
);

CREATE TABLE documents (
    id           uuid PRIMARY KEY,
    client_id    uuid NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    title        text NOT NULL,
    content      text NOT NULL,
    summary      text,
    index_status text NOT NULL DEFAULT 'pending', -- pending | indexing | indexed | failed
    index_error  text,
    attempts     int NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now()
);

-- One embedding per chunk; the title is stored as chunk_no 0 so title-only
-- documents are still reachable via semantic search.
CREATE TABLE document_chunks (
    document_id uuid NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    chunk_no    int NOT NULL,
    content     text NOT NULL,
    embedding   vector(768) NOT NULL, -- 768 = nomic-embed-text output dimension
    PRIMARY KEY (document_id, chunk_no)
);

-- Lexical search indexes.
CREATE INDEX clients_email_trgm_idx ON clients USING gin (email gin_trgm_ops);
CREATE INDEX clients_first_name_trgm_idx ON clients USING gin (first_name gin_trgm_ops);
CREATE INDEX clients_last_name_trgm_idx ON clients USING gin (last_name gin_trgm_ops);
CREATE INDEX clients_description_trgm_idx ON clients USING gin (description gin_trgm_ops);
CREATE INDEX documents_title_trgm_idx ON documents USING gin (title gin_trgm_ops);
CREATE INDEX documents_content_trgm_idx ON documents USING gin (content gin_trgm_ops);

-- Semantic search index (cosine distance).
CREATE INDEX document_chunks_embedding_idx ON document_chunks USING hnsw (embedding vector_cosine_ops);

-- Worker queue: only pending documents need scanning.
CREATE INDEX documents_pending_idx ON documents (created_at) WHERE index_status = 'pending';
