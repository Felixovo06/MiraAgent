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
    arguments            jsonb,
    status               text        not null,
    model_visible_content text,
    error_message        text,
    started_at           timestamptz not null,
    finished_at          timestamptz
);

create index if not exists idx_tool_executions_run_id on tool_executions(run_id);
create index if not exists idx_tool_executions_session_id on tool_executions(session_id);
