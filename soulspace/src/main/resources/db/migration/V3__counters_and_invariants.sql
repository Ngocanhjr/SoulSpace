CREATE FUNCTION validate_comment() RETURNS trigger
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
DECLARE
    parent_deleted TIMESTAMPTZ;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.id IS DISTINCT FROM OLD.id OR NEW.blog_id IS DISTINCT FROM OLD.blog_id
           OR NEW.parent_id IS DISTINCT FROM OLD.parent_id THEN
            RAISE EXCEPTION 'COMMENT_STRUCTURE_IMMUTABLE' USING ERRCODE = '23514';
        END IF;
        IF NEW.author_id IS DISTINCT FROM OLD.author_id AND NEW.author_id IS NOT NULL THEN
            RAISE EXCEPTION 'COMMENT_AUTHOR_IMMUTABLE' USING ERRCODE = '23514';
        END IF;
        IF OLD.deleted_at IS NOT NULL AND NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
            RAISE EXCEPTION 'COMMENT_DELETION_IMMUTABLE' USING ERRCODE = '23514';
        END IF;
        IF NEW.content IS DISTINCT FROM OLD.content OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at
           OR NEW.author_id IS DISTINCT FROM OLD.author_id THEN
            NEW.version := OLD.version + 1;
            NEW.updated_at := clock_timestamp();
        ELSE
            -- Counter updates and repeated deletion must not invalidate an editor's version.
            NEW.version := OLD.version;
            NEW.updated_at := OLD.updated_at;
        END IF;
        RETURN NEW;
    END IF;

    PERFORM 1 FROM blogs WHERE id = NEW.blog_id FOR UPDATE;
    IF NEW.parent_id IS NOT NULL THEN
        SELECT deleted_at INTO parent_deleted FROM comments
        WHERE id = NEW.parent_id AND blog_id = NEW.blog_id FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'COMMENT_PARENT_NOT_FOUND' USING ERRCODE = '23503';
        END IF;
        IF parent_deleted IS NOT NULL THEN
            RAISE EXCEPTION 'COMMENT_DELETED' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER comments_validate BEFORE INSERT OR UPDATE ON comments
FOR EACH ROW EXECUTE FUNCTION validate_comment();

CREATE FUNCTION update_relation_counter() RETURNS trigger
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
DECLARE
    delta INTEGER := CASE WHEN TG_OP = 'INSERT' THEN 1 ELSE -1 END;
BEGIN
    CASE TG_TABLE_NAME
        WHEN 'blog_likes' THEN
            UPDATE blogs SET likes_count = likes_count + delta
            WHERE id = CASE WHEN TG_OP = 'INSERT' THEN NEW.blog_id ELSE OLD.blog_id END;
        WHEN 'blog_shares' THEN
            UPDATE blogs SET shares_count = shares_count + delta
            WHERE id = CASE WHEN TG_OP = 'INSERT' THEN NEW.blog_id ELSE OLD.blog_id END;
        WHEN 'comment_likes' THEN
            UPDATE comments SET likes_count = likes_count + delta
            WHERE id = CASE WHEN TG_OP = 'INSERT' THEN NEW.comment_id ELSE OLD.comment_id END;
    END CASE;
    RETURN NULL;
END;
$$;
CREATE TRIGGER blog_likes_counter AFTER INSERT OR DELETE ON blog_likes
FOR EACH ROW EXECUTE FUNCTION update_relation_counter();
CREATE TRIGGER comment_likes_counter AFTER INSERT OR DELETE ON comment_likes
FOR EACH ROW EXECUTE FUNCTION update_relation_counter();
CREATE TRIGGER blog_shares_counter AFTER INSERT OR DELETE ON blog_shares
FOR EACH ROW EXECUTE FUNCTION update_relation_counter();

CREATE FUNCTION reject_relation_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'RELATION_IMMUTABLE_USE_INSERT_DELETE' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER blog_likes_immutable BEFORE UPDATE ON blog_likes
FOR EACH ROW EXECUTE FUNCTION reject_relation_update();
CREATE TRIGGER comment_likes_immutable BEFORE UPDATE ON comment_likes
FOR EACH ROW EXECUTE FUNCTION reject_relation_update();
CREATE TRIGGER blog_shares_immutable BEFORE UPDATE ON blog_shares
FOR EACH ROW EXECUTE FUNCTION reject_relation_update();

CREATE FUNCTION update_comment_counter() RETURNS trigger
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
DECLARE
    delta INTEGER;
    target UUID;
BEGIN
    IF TG_OP = 'INSERT' THEN
        delta := CASE WHEN NEW.deleted_at IS NULL THEN 1 ELSE 0 END;
        target := NEW.blog_id;
    ELSIF TG_OP = 'DELETE' THEN
        delta := CASE WHEN OLD.deleted_at IS NULL THEN -1 ELSE 0 END;
        target := OLD.blog_id;
    ELSE
        delta := (CASE WHEN NEW.deleted_at IS NULL THEN 1 ELSE 0 END)
               - (CASE WHEN OLD.deleted_at IS NULL THEN 1 ELSE 0 END);
        target := NEW.blog_id;
    END IF;
    IF delta <> 0 THEN
        UPDATE blogs SET comments_count = comments_count + delta WHERE id = target;
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER comments_counter AFTER INSERT OR DELETE OR UPDATE OF deleted_at ON comments
FOR EACH ROW EXECUTE FUNCTION update_comment_counter();

CREATE FUNCTION guard_comment_like() RETURNS trigger
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
DECLARE
    target_blog UUID;
    target_deleted TIMESTAMPTZ;
BEGIN
    SELECT blog_id INTO target_blog FROM comments WHERE id = NEW.comment_id;
    PERFORM 1 FROM blogs WHERE id = target_blog FOR UPDATE;
    SELECT deleted_at INTO target_deleted FROM comments WHERE id = NEW.comment_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'COMMENT_NOT_FOUND' USING ERRCODE = '23503';
    END IF;
    IF target_deleted IS NOT NULL THEN
        RAISE EXCEPTION 'COMMENT_DELETED' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER comment_like_active BEFORE INSERT ON comment_likes
FOR EACH ROW EXECUTE FUNCTION guard_comment_like();

CREATE FUNCTION check_deleted_comment_likes() RETURNS trigger
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
BEGIN
    IF EXISTS (SELECT FROM comments c WHERE c.id = NEW.id AND c.deleted_at IS NOT NULL
               AND (c.likes_count <> 0 OR EXISTS (SELECT FROM comment_likes l WHERE l.comment_id = c.id))) THEN
        RAISE EXCEPTION 'DELETED_COMMENT_HAS_LIKES' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER deleted_comment_no_likes
AFTER INSERT OR UPDATE ON comments DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW WHEN (NEW.deleted_at IS NOT NULL) EXECUTE FUNCTION check_deleted_comment_likes();

CREATE FUNCTION lock_image_blog() RETURNS trigger
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.blog_id IS DISTINCT FROM OLD.blog_id OR NEW.asset_id IS DISTINCT FROM OLD.asset_id) THEN
        RAISE EXCEPTION 'IMAGE_LINK_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN
        PERFORM 1 FROM blogs WHERE id = OLD.blog_id FOR UPDATE;
        RETURN OLD;
    END IF;
    PERFORM 1 FROM blogs WHERE id = NEW.blog_id FOR UPDATE;
    RETURN NEW;
END;
$$;
CREATE TRIGGER image_blog_lock BEFORE INSERT OR UPDATE OR DELETE ON blog_images
FOR EACH ROW EXECUTE FUNCTION lock_image_blog();

CREATE FUNCTION check_nonempty_blog() RETURNS trigger
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
DECLARE
    target UUID;
    body TEXT;
BEGIN
    IF TG_TABLE_NAME = 'blogs' THEN
        target := CASE WHEN TG_OP = 'DELETE' THEN OLD.id ELSE NEW.id END;
    ELSE
        target := CASE WHEN TG_OP = 'DELETE' THEN OLD.blog_id ELSE NEW.blog_id END;
    END IF;
    SELECT content INTO body FROM blogs WHERE id = target FOR UPDATE;
    IF FOUND AND length(btrim(body)) = 0 AND NOT EXISTS (SELECT FROM blog_images WHERE blog_id = target) THEN
        RAISE EXCEPTION 'BLOG_REQUIRES_TEXT_OR_IMAGE' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER blogs_nonempty AFTER INSERT OR UPDATE OF content ON blogs
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_nonempty_blog();
CREATE CONSTRAINT TRIGGER blog_images_nonempty AFTER INSERT OR UPDATE OR DELETE ON blog_images
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_nonempty_blog();

CREATE FUNCTION guard_media_identity() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.purpose IS DISTINCT FROM OLD.purpose
       OR NEW.provider_public_id IS DISTINCT FROM OLD.provider_public_id
       OR (OLD.provider_asset_id IS NOT NULL AND NEW.provider_asset_id IS DISTINCT FROM OLD.provider_asset_id) THEN
        RAISE EXCEPTION 'MEDIA_IDENTITY_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'DELETE_PENDING' AND NEW.state <> 'DELETE_PENDING' THEN
        RAISE EXCEPTION 'MEDIA_PENDING_DELETION' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'ATTACHED' AND NEW.state NOT IN ('ATTACHED', 'DELETE_PENDING') THEN
        RAISE EXCEPTION 'ATTACHED_MEDIA_STATE_CANNOT_REVERT' USING ERRCODE = '23514';
    END IF;
    IF OLD.provider_asset_id IS NOT NULL
       AND (NEW.format IS DISTINCT FROM OLD.format OR NEW.size_bytes IS DISTINCT FROM OLD.size_bytes) THEN
        RAISE EXCEPTION 'CONFIRMED_MEDIA_METADATA_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER media_identity_immutable BEFORE UPDATE ON media_assets
FOR EACH ROW EXECUTE FUNCTION guard_media_identity();
