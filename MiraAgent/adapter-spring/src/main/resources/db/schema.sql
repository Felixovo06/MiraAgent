-- MiraAgent P0 schema

create table if not exists sessions (
    id          text        primary key,
    user_id     text        not null,
    character_id text,
    title       text,
    source      text,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    last_message_at timestamptz
);

create index if not exists idx_sessions_user_id on sessions(user_id);
create index if not exists idx_sessions_character_id on sessions(character_id);
create index if not exists idx_sessions_last_message_at on sessions(last_message_at desc);

create table if not exists messages (
    id           text        primary key,
    session_id   text        not null references sessions(id),
    role         text        not null,
    content      text,
    tool_call_id text,
    tool_name    text,
    tool_calls   jsonb,
    metadata     jsonb,
    created_at   timestamptz not null default now()
);

create index if not exists idx_messages_session_id on messages(session_id);
create index if not exists idx_messages_created_at on messages(created_at);

create table if not exists agent_traces (
    id          text        primary key,
    run_id      text        not null,
    session_id  text        not null,
    step_index  integer     not null,
    event_type  text        not null,
    payload     jsonb,
    created_at  timestamptz not null default now()
);

create index if not exists idx_agent_traces_run_id on agent_traces(run_id);
create index if not exists idx_agent_traces_session_id on agent_traces(session_id);

create table if not exists tool_executions (
    id                   text        primary key,
    run_id               text        not null,
    session_id           text        not null,
    tool_call_id         text        not null,
    tool_name            text        not null,
    arguments            text,
    status               text        not null,
    model_visible_content text,
    error_message        text,
    started_at           timestamptz not null,
    finished_at          timestamptz
);

create index if not exists idx_tool_executions_run_id on tool_executions(run_id);
create index if not exists idx_tool_executions_session_id on tool_executions(session_id);

-- P1 Memory Index
create extension if not exists pg_trgm;

create table if not exists memory_index (
    id                text        primary key,
    user_id           text        not null,
    character_id      text,
    scope             text        not null,
    category          text        not null,
    content_preview   text,
    source_uri        text,
    confidence        integer     not null default 80,
    source_session_id text,
    source_message_id text,
    retrieval_terms   jsonb,
    embedding_ref     text,
    archived_at       timestamptz,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);

create index if not exists idx_memory_index_user_id on memory_index(user_id, scope, category) where archived_at is null;
create index if not exists idx_memory_index_preview_trgm on memory_index using gin(content_preview gin_trgm_ops);
create index if not exists idx_memory_index_fts on memory_index using gin(to_tsvector('simple', coalesce(content_preview, '')));

-- P1 Step5: pgvector embedding
-- Optional deployment step when pgvector is installed:
-- create extension if not exists vector;
-- alter table memory_index add column if not exists embedding vector(1536);
-- create index if not exists idx_memory_index_embedding on memory_index using ivfflat (embedding vector_cosine_ops) with (lists = 100);

-- P2 Skills 索引表（文件 metadata.json 为事实源，本表仅索引/状态/统计/来源）
create table if not exists skills (
    id                text        primary key,
    name              text        not null,
    description       text,
    status            text        not null default 'ACTIVE',
    tags              jsonb,
    pinned            boolean     not null default false,
    use_count         integer     not null default 0,
    version           integer     not null default 1,
    source_uri        text,
    source_trace_id   text,
    source_session_id text,
    embedding_ref     text,
    last_used_at      timestamptz,
    archived_at       timestamptz,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);

create index if not exists idx_skills_status on skills(status) where archived_at is null;
create index if not exists idx_skills_name_trgm on skills using gin(name gin_trgm_ops);

-- P2 Step4: skill 去重向量列（pgvector 已装时启用）
-- alter table skills add column if not exists embedding vector(1536);
-- create index if not exists idx_skills_embedding on skills using ivfflat (embedding vector_cosine_ops) with (lists = 100);
