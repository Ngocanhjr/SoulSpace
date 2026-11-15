import { useCallback, useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { MoreHorizontal, Plus, LockKeyhole, Globe, LogOut, Settings, ArrowUpRight } from "lucide-react";
import { api, apiErrorMessage, buildApiUrl } from "../../config/api";
import { clearSession, refreshSession, signOut, useAuth } from "../../config/auth";
import { DEFAULT_AVATAR_URL } from "../../config/assets";
import { toast } from "sonner";
import Timestamp from "../../Components/Timestamp";
import Edit from "../../Components/EditProfile/Edit";
import EditAvt from "../../Components/EditAvatar/EditAvt";
import { Button } from "../../Components/ui/button";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from "../../Components/ui/dropdown-menu";
import { AlertDialog, AlertDialogContent, AlertDialogHeader, AlertDialogTitle, AlertDialogDescription, AlertDialogFooter } from "../../Components/ui/alert-dialog";
import "./UserPage.css";

const MAX_IMAGES = 10;
const MAX_IMAGE_BYTES = 10 * 1024 * 1024;
const MAX_CONTENT_LENGTH = 5_000;
const imageUrl = (url) => url?.startsWith("/api/") ? buildApiUrl(url) : url || DEFAULT_AVATAR_URL;

function PostEditor({ post, onClose, onSave }) {
  const [content, setContent] = useState(post.content || "");
  const [published, setPublished] = useState(post.published);
  const [remaining, setRemaining] = useState(post.imageContentUrls || []);
  const [files, setFiles] = useState([]);
  const [previews, setPreviews] = useState([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    const urls = files.map((file) => URL.createObjectURL(file));
    setPreviews(urls);
    return () => urls.forEach((url) => URL.revokeObjectURL(url));
  }, [files]);

  function select(event) {
    const selected = Array.from(event.target.files);
    event.target.value = "";
    if (remaining.length + files.length + selected.length > MAX_IMAGES || selected.some((file) => !["image/jpeg", "image/png"].includes(file.type) || file.size > MAX_IMAGE_BYTES)) {
      setError("Up to 10 JPEG/PNG images, each no larger than 10 MiB.");
      return;
    }
    setError("");
    setFiles((current) => [...current, ...selected]);
  }

  async function submit(event) {
    event.preventDefault();
    if (busy) return;
    if ((!content.trim() && !remaining.length && !files.length) || Array.from(content.trim()).length > MAX_CONTENT_LENGTH) {
      setError("Add text or images. Text must be no longer than 5,000 characters.");
      return;
    }
    setBusy(true);
    setError("");
    const body = new FormData();
    body.append("blogId", post.id);
    body.append("content", content);
    body.append("published", String(published));
    (post.imageContentUrls || []).filter((url) => !remaining.includes(url)).forEach((url) => body.append("removeImagesUrl", url));
    files.forEach((file) => body.append("newImages", file));
    try {
      const { data } = await api.patch("/api/v1/blogs-details", body);
      onSave({ ...post, content: data.content, published: data.published, imageContentUrls: data.successUrls, updatedAt: data.updatedAt });
      if (data.failedUploadFiles?.length) toast.warning(`Post saved. ${data.failedUploadFiles.length} images failed to upload.`);
      else toast.success("Post saved.");
      onClose();
    } catch (failure) {
      setError(apiErrorMessage(failure));
    } finally { setBusy(false); }
  }

  return <AlertDialog open onOpenChange={(open) => { if (!open && !busy) onClose(); }}>
    <AlertDialogContent className="profile-dialog">
      <AlertDialogHeader>
        <AlertDialogTitle>Edit post</AlertDialogTitle>
        <AlertDialogDescription>Text, images, and visibility are saved together.</AlertDialogDescription>
      </AlertDialogHeader>
      <form className="profile-form" onSubmit={submit} aria-busy={busy}>
        <label>Text<textarea autoFocus value={content} onChange={(event) => setContent(event.target.value)} disabled={busy} /></label>
        <label>Who can see this?<select value={String(published)} onChange={(event) => setPublished(event.target.value === "true")} disabled={busy}>
          <option value="true">Public</option><option value="false">Only me</option>
        </select></label>
        {(remaining.length > 0 || files.length > 0) && <div className="profile-edit-images">
          {remaining.map((url) => <figure key={url}><img src={imageUrl(url)} alt="Post image" /><button type="button" disabled={busy} onClick={() => setRemaining((current) => current.filter((item) => item !== url))}>Remove image</button></figure>)}
          {files.map((file, index) => <figure key={`${file.name}-${index}`}><img src={previews[index]} alt={file.name} /><button type="button" disabled={busy} onClick={() => setFiles((current) => current.filter((_, position) => position !== index))}>Remove new image</button></figure>)}
        </div>}
        <label>Add images<input type="file" multiple accept="image/jpeg,image/png" onChange={select} disabled={busy} /></label>
        <p className="profile-form-hint">{remaining.length + files.length}/10 images · Images are removed when you save your changes.</p>
        {error && <p role="alert" className="profile-form-error">{error}</p>}
        <AlertDialogFooter><Button type="button" variant="outline" disabled={busy} onClick={onClose}>Cancel</Button><Button type="submit" disabled={busy}>{busy ? "Saving…" : "Save post"}</Button></AlertDialogFooter>
      </form>
    </AlertDialogContent>
  </AlertDialog>;
}

export default function UserPage() {
  const { user: sessionUser, ready } = useAuth();
  const id = sessionUser?.id;
  const navigate = useNavigate();
  const [profile, setProfile] = useState(null);
  const [posts, setPosts] = useState([]);
  const [shares, setShares] = useState([]);
  const [errors, setErrors] = useState({});
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState("posts");
  const [dialog, setDialog] = useState(null);
  const [currentPassword, setCurrentPassword] = useState("");
  const [actionError, setActionError] = useState("");
  const [busy, setBusy] = useState(false);

  const load = useCallback(async (signal) => {
    if (!id) return;
    setLoading(true);
    const results = await Promise.allSettled([
      api.get(`/api/v1/users/${id}`, { signal }),
      api.get(`/api/v1/blogs/${id}/all`, { signal }),
      api.get(`/api/v1/users/${id}/shared-blogs`, { signal }),
    ]);
    if (signal?.aborted) return;
    const nextErrors = {};
    const names = ["profile", "posts", "shares"];
    const setters = [setProfile, setPosts, setShares];
    results.forEach((result, index) => {
      if (result.status === "fulfilled") setters[index](result.value.data);
      else nextErrors[names[index]] = apiErrorMessage(result.reason, "Unable to load data. Please try again.");
    });
    setErrors(nextErrors);
    setLoading(false);
  }, [id]);

  useEffect(() => {
    const controller = new AbortController();
    setProfile(null);
    setPosts([]);
    setShares([]);
    setDialog(null);
    setCurrentPassword("");
    if (ready && id) load(controller.signal);
    return () => controller.abort();
  }, [id, ready, load]);

  function closeDialog() {
    if (busy) return;
    setDialog(null);
    setActionError("");
    setCurrentPassword("");
  }

  function savedProfile(data) {
    setProfile((current) => ({ ...current, ...data }));
    setPosts((current) => current.map((post) => ({ ...post, username: data.username, userAvatarUrl: data.userAvatarUrl })));
    setShares((current) => current.map((post) => post.originalUserId === id ? { ...post, username: data.username, userAvatarUrl: data.userAvatarUrl } : post));
    refreshSession().catch(() => toast.warning("Profile saved. Unable to refresh your session; please reload the page."));
  }

  function reloadShares() {
    api.get(`/api/v1/users/${id}/shared-blogs`)
      .then(({ data }) => { setShares(data); setErrors((current) => ({ ...current, shares: null })); })
      .catch((failure) => setErrors((current) => ({ ...current, shares: apiErrorMessage(failure) })));
  }

  function savedPost(post) {
    setPosts((current) => current.map((item) => item.id === post.id ? post : item));
    reloadShares();
  }

  async function logout() {
    if (busy) return;
    setBusy(true);
    try { await signOut(); navigate("/signin"); }
    catch (failure) { toast.error(apiErrorMessage(failure)); }
    finally { setBusy(false); }
  }

  async function confirm(event) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setActionError("");
    try {
      if (dialog.type === "account") {
        await api.delete(`/api/v1/users/${id}`, { data: { currentPassword } });
        clearSession();
        toast.success("Account deleted.");
        navigate("/signin", { replace: true });
      } else if (dialog.type === "delete") {
        await api.delete(`/api/v1/blogs/${dialog.post.id}`);
        setPosts((current) => current.filter((post) => post.id !== dialog.post.id));
        setShares((current) => current.filter((post) => post.blogId !== dialog.post.id));
        toast.success("Post deleted.");
      } else {
        await api.delete(`/api/v1/users/${id}/shared-blogs/${dialog.post.blogId}`);
        setShares((current) => current.filter((post) => post.blogId !== dialog.post.blogId));
        toast.success("Share removed.");
      }
      setDialog(null);
      setCurrentPassword("");
    } catch (failure) { setActionError(apiErrorMessage(failure)); }
    finally { setBusy(false); }
  }

  async function access(post) {
    if (busy) return;
    setBusy(true);
    try {
      const { data } = await api.patch("/api/v1/blogs/access", { blogId: post.id, published: !post.published });
      setPosts((current) => current.map((item) => item.id === post.id ? { ...item, published: data.published, updatedAt: data.updatedAt } : item));
      reloadShares();
      toast.success(data.published ? "Your post is now public." : "Only you can see this post.");
    } catch (failure) { toast.error(apiErrorMessage(failure)); }
    finally { setBusy(false); }
  }

  if (!ready) return <div className="profile-state" role="status">Checking your session…</div>;
  if (!id) return null;
  if (!profile) return <div className="profile-state" aria-busy={loading}>{loading ? "Loading profile…" : <><p role="alert">{errors.profile || "Unable to load your profile."}</p><Button onClick={() => load()}>Try again</Button></>}</div>;

  const sharedTab = tab === "shares";
  const items = sharedTab ? shares : posts;
  return <main className="profile-page">
    <header className="profile-topbar"><h1>Profile</h1><DropdownMenu><DropdownMenuTrigger asChild><Button variant="ghost" size="icon" aria-label="Account settings" disabled={busy}><Settings size={19} /></Button></DropdownMenuTrigger>
      <DropdownMenuContent align="end"><DropdownMenuItem onSelect={() => setDialog({ type: "password" })}>Change password</DropdownMenuItem><DropdownMenuItem onSelect={logout}><LogOut size={15} /> Sign out</DropdownMenuItem><DropdownMenuSeparator /><DropdownMenuItem variant="destructive" onSelect={() => setDialog({ type: "account" })}>Delete account</DropdownMenuItem></DropdownMenuContent></DropdownMenu></header>
    <section className="profile-summary">
      <button className="profile-avatar-button" onClick={() => setDialog({ type: "avatar" })} aria-label="Change avatar"><img src={imageUrl(profile.userAvatarUrl)} alt="Your avatar" /><span>Edit photo</span></button>
      <div className="profile-identity"><p className="profile-eyebrow">YOUR SPACE</p><h2>{profile.fullname}</h2><p className="profile-handle">@{profile.username}</p>
        {profile.dob && <p className="profile-birthday">Born on {profile.dob.split("-").reverse().join("/")} <LockKeyhole size={12} aria-label="Only me" /></p>}
      </div>
      <div className="profile-summary-actions"><Button variant="outline" onClick={() => setDialog({ type: "profile" })}>Edit profile</Button><Button variant="ghost" asChild><Link to={`/user/${id}`}>View public profile <ArrowUpRight size={14} /></Link></Button></div>
    </section>
    <nav className="profile-tabs" aria-label="Profile content"><button aria-pressed={!sharedTab} onClick={() => setTab("posts")}>Posts <span>{posts.length}</span></button><button aria-pressed={sharedTab} onClick={() => setTab("shares")}>Shared <span>{shares.length}</span></button><Link to="/post" className="profile-create" aria-label="Create post"><Plus size={19} /></Link></nav>
    <section className="profile-posts" aria-label={sharedTab ? "Shared posts" : "Your posts"} aria-busy={loading}>
      {(errors[tab] || errors.profile) && <div className="profile-inline-error" role="alert"><p>{errors[tab] || errors.profile}</p><Button variant="outline" onClick={() => load()} disabled={loading}>Reload</Button></div>}
      {loading && <p role="status" className="profile-loading">Loading…</p>}
      {!items.length && !loading && !errors[tab] && <div className="profile-empty"><h3>{sharedTab ? "No shared posts yet" : "Your story starts here"}</h3><p>{sharedTab ? "Posts you share will appear here." : "Share what is on your mind, or keep it just for yourself."}</p><Button variant="outline" asChild><Link to={sharedTab ? "/" : "/post"}>{sharedTab ? "Explore the feed" : "Write your first post"}</Link></Button></div>}
      {items.map((post) => {
        const postId = sharedTab ? post.blogId : post.id;
        return <article className="profile-post" key={postId}>
          {sharedTab && <p className="profile-share-label">You shared · <Timestamp value={post.sharedAt} /></p>}
          <div className="profile-post-heading"><Link to={`/user/${sharedTab ? post.originalUserId : id}`} className="profile-post-author"><img src={imageUrl(post.userAvatarUrl)} alt="" /><span><strong>@{post.username}</strong><span className="profile-post-meta"><Timestamp value={sharedTab ? post.originalCreatedAt : post.createdAt} />{post.published ? <><Globe size={12} /> Public</> : <><LockKeyhole size={12} /> Only me</>}</span></span></Link>
            <DropdownMenu><DropdownMenuTrigger asChild><Button variant="ghost" size="icon" aria-label="Post options" disabled={busy}><MoreHorizontal size={20} /></Button></DropdownMenuTrigger><DropdownMenuContent align="end">{sharedTab ? <DropdownMenuItem onSelect={() => setDialog({ type: "unshare", post })}>Remove share</DropdownMenuItem> : <><DropdownMenuItem onSelect={() => setDialog({ type: "post", post })}>Edit post</DropdownMenuItem><DropdownMenuItem onSelect={() => access(post)}>{post.published ? "Make private" : "Make public"}</DropdownMenuItem><DropdownMenuSeparator /><DropdownMenuItem variant="destructive" onSelect={() => setDialog({ type: "delete", post })}>Delete post</DropdownMenuItem></>}</DropdownMenuContent></DropdownMenu>
          </div>
          <Link to={`/post/${postId}`} className="profile-post-body">{post.content && <p>{post.content}</p>}{post.imageContentUrls?.length > 0 && <div className="profile-post-images">{post.imageContentUrls.map((url, index) => <img src={imageUrl(url)} alt={`Post image ${index + 1}`} key={url} loading="lazy" />)}</div>}</Link>
          <Link to={`/post/${postId}`} className="profile-post-footer"><span>{post.likesCount || 0} likes · {post.commentsCount || 0} comments · {post.sharesCount || 0} shares</span><ArrowUpRight size={15} aria-hidden="true" /></Link>
        </article>;
      })}
    </section>
    {(dialog?.type === "profile" || dialog?.type === "password") && <Edit key={dialog.type} mode={dialog.type} user={profile} onClose={closeDialog} onSave={savedProfile} />}
    {dialog?.type === "avatar" && <EditAvt userId={id} currentAvatar={imageUrl(profile.userAvatarUrl)} onClose={closeDialog} onSave={savedProfile} />}
    {dialog?.type === "post" && <PostEditor post={dialog.post} onClose={closeDialog} onSave={savedPost} />}
    {["account", "delete", "unshare"].includes(dialog?.type) && <AlertDialog open onOpenChange={(open) => { if (!open) closeDialog(); }}><AlertDialogContent className="profile-dialog">
      <AlertDialogHeader><AlertDialogTitle>{dialog.type === "account" ? "Delete account?" : dialog.type === "delete" ? "Delete post?" : "Remove this share?"}</AlertDialogTitle><AlertDialogDescription>{dialog.type === "account" ? "Your account, posts, and interactions will be deleted. Your comments on other posts will become deleted placeholders to preserve replies. This cannot be undone." : dialog.type === "delete" ? "This post, its comments, and related interactions will be deleted. This cannot be undone." : "The original post will remain. Only your share will be removed."}</AlertDialogDescription></AlertDialogHeader>
      <form className="profile-form" onSubmit={confirm} aria-busy={busy}>{dialog.type === "account" && <label>Current password<input autoFocus type="password" autoComplete="current-password" required value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} disabled={busy} /></label>}{actionError && <p role="alert" className="profile-form-error">{actionError}</p>}<AlertDialogFooter><Button type="button" variant="outline" disabled={busy} onClick={closeDialog}>Cancel</Button><Button type="submit" className="profile-danger" disabled={busy}>{busy ? "Processing…" : dialog.type === "unshare" ? "Remove share" : "Confirm deletion"}</Button></AlertDialogFooter></form>
    </AlertDialogContent></AlertDialog>}
  </main>;
}
