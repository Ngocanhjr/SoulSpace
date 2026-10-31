-- One row per public original blog; shares do not create extra feed entries.
CREATE VIEW public.v_public_blog_feed WITH (security_invoker = true) AS
SELECT b.id AS blog_id,
       b.author_id,
       u.username,
       u.fullname,
       u.avatar_media_id,
       b.content,
       b.likes_count,
       b.comments_count,
       b.shares_count,
       b.created_at,
       b.updated_at
FROM public.blogs b
         JOIN public.users u ON u.id = b.author_id
WHERE b.published = true;

COMMENT
ON VIEW public.v_public_blog_feed IS
    'Public original blogs with current author profile; query with explicit ordering and pagination.';

-- Provisioning default privileges include DML on new relations; this view is read-only.
REVOKE ALL ON TABLE public.v_public_blog_feed FROM PUBLIC, soulspace_runtime;
GRANT SELECT ON TABLE public.v_public_blog_feed TO soulspace_runtime;
