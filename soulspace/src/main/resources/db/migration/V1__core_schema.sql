CREATE TABLE users (
    id UUID PRIMARY KEY,
    username VARCHAR(32) NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9]{3,32}$'),
    password_hash TEXT NOT NULL,
    fullname VARCHAR(100) NOT NULL CHECK (char_length(btrim(fullname)) BETWEEN 1 AND 100),
    dob DATE,
    avatar_media_id UUID UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE media_assets (
    id UUID PRIMARY KEY,
    owner_id UUID REFERENCES users(id) ON DELETE SET NULL,
    purpose VARCHAR(16) NOT NULL CHECK (purpose IN ('AVATAR', 'BLOG', 'STANDALONE')),
    state VARCHAR(20) NOT NULL CHECK (state IN ('PENDING', 'ATTACHED', 'DELETE_PENDING')),
    provider_public_id TEXT NOT NULL UNIQUE CHECK (length(btrim(provider_public_id)) > 0),
    provider_asset_id TEXT UNIQUE,
    format VARCHAR(8),
    size_bytes BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT media_provider_asset_id_nonempty CHECK (
        provider_asset_id IS NULL OR length(btrim(provider_asset_id)) > 0
    ),
    CONSTRAINT media_format CHECK (format IS NULL OR format IN ('jpeg', 'png')),
    CONSTRAINT media_size CHECK (size_bytes IS NULL OR size_bytes > 0),
    CONSTRAINT confirmed_media_metadata CHECK (
        (provider_asset_id IS NULL AND format IS NULL AND size_bytes IS NULL)
        OR (provider_asset_id IS NOT NULL AND format IS NOT NULL AND size_bytes IS NOT NULL AND size_bytes > 0)
    ),
    CONSTRAINT attached_media_confirmed CHECK (
        state <> 'ATTACHED' OR provider_asset_id IS NOT NULL
    )
);
ALTER TABLE users ADD CONSTRAINT users_avatar_fk
    FOREIGN KEY (avatar_media_id) REFERENCES media_assets(id) ON DELETE SET NULL;

CREATE TABLE blogs (
    id UUID PRIMARY KEY,
    author_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content TEXT NOT NULL DEFAULT '' CHECK (char_length(content) <= 5000),
    published BOOLEAN NOT NULL DEFAULT true,
    likes_count BIGINT NOT NULL DEFAULT 0 CHECK (likes_count >= 0),
    comments_count BIGINT NOT NULL DEFAULT 0 CHECK (comments_count >= 0),
    shares_count BIGINT NOT NULL DEFAULT 0 CHECK (shares_count >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    category_mode VARCHAR(6)  NOT NULL DEFAULT 'AUTO' CHECK (category_mode IN ('AUTO', 'MANUAL'))
);
CREATE TABLE categories
(
    id   UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL CHECK (char_length(btrim(name)) BETWEEN 1 AND 100),
    slug VARCHAR(100) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
)
    );
CREATE TABLE blog_categories
(
    blog_id         UUID       NOT NULL REFERENCES blogs (id) ON DELETE CASCADE,
    category_id     UUID       NOT NULL REFERENCES categories (id) ON DELETE RESTRICT,
    category_source VARCHAR(6) NOT NULL CHECK (category_source IN ('AUTO', 'MANUAL')),
    confidence      DOUBLE PRECISION CHECK (confidence >= 0 AND confidence <= 1),
    PRIMARY KEY (blog_id, category_id, category_source),
    CONSTRAINT category_confidence_source CHECK (
            (category_source = 'AUTO' AND confidence IS NOT NULL)
            OR (category_source = 'MANUAL' AND confidence IS NULL)
        )
);
CREATE TABLE comments (
    id UUID PRIMARY KEY,
    blog_id UUID NOT NULL REFERENCES blogs(id) ON DELETE CASCADE,
    author_id UUID REFERENCES users(id) ON DELETE SET NULL,
    parent_id UUID,
    content TEXT,
    likes_count BIGINT NOT NULL DEFAULT 0 CHECK (likes_count >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (id, blog_id),
    FOREIGN KEY (parent_id, blog_id) REFERENCES comments(id, blog_id) ON DELETE CASCADE,
    CHECK (parent_id IS DISTINCT FROM id),
    CONSTRAINT comment_content_state CHECK (
        (deleted_at IS NULL AND content IS NOT NULL AND char_length(btrim(content)) BETWEEN 1 AND 2000)
        OR (deleted_at IS NOT NULL AND content IS NULL)
    ),
    CHECK (author_id IS NOT NULL OR deleted_at IS NOT NULL)
);
CREATE TABLE blog_likes (
    blog_id UUID NOT NULL REFERENCES blogs(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (blog_id, user_id)
);
CREATE TABLE comment_likes (
    comment_id UUID NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (comment_id, user_id)
);
CREATE TABLE blog_shares (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blog_id UUID NOT NULL REFERENCES blogs(id) ON DELETE CASCADE,
    shared_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, blog_id)
);
CREATE TABLE blog_images (
    blog_id UUID NOT NULL REFERENCES blogs(id) ON DELETE CASCADE,
    asset_id UUID PRIMARY KEY REFERENCES media_assets(id) ON DELETE RESTRICT,
    position SMALLINT NOT NULL CHECK (position >= 0 AND position < 10),
    CONSTRAINT blog_image_position UNIQUE (blog_id, position) DEFERRABLE INITIALLY IMMEDIATE
);
CREATE TABLE media_cleanup_jobs (
    id UUID PRIMARY KEY,
    asset_id UUID NOT NULL UNIQUE REFERENCES media_assets(id) ON DELETE CASCADE,
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RUNNING', 'DEAD')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    available_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT cleanup_lease_state CHECK (
        (status = 'RUNNING' AND lease_until IS NOT NULL)
        OR (status IN ('PENDING', 'DEAD') AND lease_until IS NULL)
    )
);

-- Thông báo tương tác dùng chung; read_at NULL nghĩa là chưa đọc.
CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    actor_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blog_id UUID NOT NULL REFERENCES blogs(id) ON DELETE CASCADE,
    comment_id UUID,
    type VARCHAR(20) NOT NULL CHECK (type IN (
        'BLOG_LIKE', 'COMMENT_LIKE', 'BLOG_COMMENT', 'COMMENT_REPLY', 'BLOG_SHARE'
    )),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at TIMESTAMPTZ,
    FOREIGN KEY (comment_id, blog_id) REFERENCES comments(id, blog_id) ON DELETE CASCADE,
    -- Giữ lịch sử khi bỏ thích/gỡ share; thao tác lại không tạo thông báo trùng.
    CONSTRAINT notifications_interaction_unique
        UNIQUE NULLS NOT DISTINCT (recipient_id, actor_id, blog_id, type, comment_id),
    CONSTRAINT notifications_no_self CHECK (recipient_id <> actor_id),
    CONSTRAINT notifications_comment_type CHECK (
        (type IN ('BLOG_LIKE', 'BLOG_SHARE') AND comment_id IS NULL)
        OR (type IN ('COMMENT_LIKE', 'BLOG_COMMENT', 'COMMENT_REPLY') AND comment_id IS NOT NULL)
    )
);
